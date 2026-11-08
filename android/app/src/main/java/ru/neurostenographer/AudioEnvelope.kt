package ru.neurostenographer

import java.io.InputStream
import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Огибающая PCM16 WAV: читаем поток небольшими блоками, не загружаем запись целиком. */
internal object AudioEnvelope {
    /** Быстрый путь для PCM WAV; остальные системно декодируемые форматы читаются потоково. */
    fun read(context: Context, uri: Uri, bins: Int = 160, onProgress: (FloatArray) -> Unit = {}): FloatArray {
        val wav = runCatching {
            context.contentResolver.openInputStream(uri)?.use { read(it, bins) }
        }.getOrNull()
        if (wav != null) return wav
        android.util.Log.i("AudioEnvelope", "WAV parse did not apply; using Android audio decoder")
        return readDecoded(context, uri, bins, onProgress)
    }

    private fun readDecoded(context: Context, uri: Uri, bins: Int, onProgress: (FloatArray) -> Unit): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return FloatArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            android.util.Log.i("AudioEnvelope", "Decoder opened $mime, duration=${durationUs / 1_000_000}s")
            if (durationUs > 10 * 60 * 1_000_000L) {
                return readSparse(context, uri, bins, durationUs, onProgress)
            }
            val sums = DoubleArray(bins)
            val counts = LongArray(bins)
            var lastProgressAt = 0L
            fun snapshot() = FloatArray(bins) { if (counts[it] == 0L) 0f else sqrt(sums[it] / counts[it]).toFloat() }
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0); codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var idle = 0
                while (!outputDone) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index) ?: error("Нет входного буфера декодера")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Неподдерживаемый формат PCM" }
                        }
                        in 0..Int.MAX_VALUE -> {
                            idle = 0
                            val out = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                            out.position(info.offset); out.limit(info.offset + info.size)
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size / (channels * bytesPerSample)
                            for (frame in 0 until frames) {
                                var energy = 0.0
                                repeat(channels) {
                                    val sample = if (bytesPerSample == 4) out.float.toDouble() else out.short.toDouble() / 32768.0
                                    energy += sample * sample / channels
                                }
                                val timeUs = info.presentationTimeUs + frame * 1_000_000L / rate
                                val bin = (timeUs.coerceAtLeast(0) * bins / durationUs).toInt().coerceIn(0, bins - 1)
                                sums[bin] += energy; counts[bin]++
                            }
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastProgressAt >= 250L) {
                                lastProgressAt = now
                                onProgress(snapshot())
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                        }
                        else -> { idle++; check(idle < 3000) { "Декодер аудио не отвечает" } }
                    }
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            return snapshot().also(onProgress)
        } finally { extractor.release() }
    }

    /** Для длинных файлов берём короткие окна равномерно по всей длительности. */
    private fun readSparse(context: Context, uri: Uri, bins: Int, durationUs: Long, onProgress: (FloatArray) -> Unit): FloatArray {
        val result = FloatArray(bins)
        val points = minOf(32, bins).coerceAtLeast(1)
        val windowUs = minOf(250_000L, (durationUs / points / 3).coerceAtLeast(30_000L))
        for (point in 0 until points) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val center = durationUs * (point * 2L + 1) / (points * 2L)
            val fromUs = (center - windowUs / 2).coerceAtLeast(0L)
            val toUs = (fromUs + windowUs).coerceAtMost(durationUs)
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            var energy = 0.0
            var samples = 0L
            try {
                extractor.setDataSource(context, uri, null)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return FloatArray(0)
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
                extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val decoder = MediaCodec.createDecoderByType(mime); codec = decoder
                decoder.configure(format, null, null, 0); decoder.start()
                val info = MediaCodec.BufferInfo()
                var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var inputDone = false
                var finished = false
                var stalls = 0
                while (!finished) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(5_000)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex) ?: error("Нет входного буфера декодера")
                            val pts = extractor.sampleTime
                            val size = if (pts < 0 || pts >= toUs + 100_000L) -1 else extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, maxOf(pts, toUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, pts, 0); extractor.advance()
                            }
                        }
                    }
                    when (val outputIndex = decoder.dequeueOutputBuffer(info, 5_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = decoder.outputFormat
                            rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
                        }
                        in 0..Int.MAX_VALUE -> {
                            stalls = 0
                            val out = decoder.getOutputBuffer(outputIndex)!!.order(ByteOrder.LITTLE_ENDIAN)
                            out.position(info.offset); out.limit(info.offset + info.size)
                            val bytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size / (channels * bytes)
                            for (frame in 0 until frames) {
                                var frameEnergy = 0.0
                                repeat(channels) { frameEnergy += (if (bytes == 4) out.float.toDouble() else out.short.toDouble() / 32768.0).let { it * it } / channels }
                                val timeUs = info.presentationTimeUs + frame * 1_000_000L / rate
                                if (timeUs in fromUs until toUs) { energy += frameEnergy; samples++ }
                            }
                            finished = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs >= toUs
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                        else -> { stalls++; check(stalls < 100) { "Декодер не отвечает при чтении фрагмента" } }
                    }
                }
            } finally {
                codec?.let { runCatching { it.stop() }; it.release() }
                extractor.release()
            }
            val value = if (samples == 0L) 0f else sqrt(energy / samples).toFloat()
            val start = point * bins / points
            val end = ((point + 1) * bins / points).coerceAtLeast(start + 1)
            for (bin in start until end.coerceAtMost(bins)) result[bin] = value
            onProgress(result.copyOf())
        }
        return result
    }

    fun read(input: InputStream, bins: Int = 160): FloatArray {
        fun exact(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val n = input.read(bytes, offset, size - offset)
                require(n > 0) { "Неполный WAV" }; offset += n
            }
            return bytes
        }
        fun uint(bytes: ByteArray, offset: Int) = (0..3).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 255) shl (i * 8)) }
        fun skip(size: Long) { var left = size; val buffer = ByteArray(8192); while (left > 0) { val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); require(n > 0); left -= n } }
        val header = exact(12)
        require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE")
        var pcm16 = false
        while (true) {
            val chunk = exact(8); val length = uint(chunk, 4)
            when (String(chunk, 0, 4, Charsets.US_ASCII)) {
                "fmt " -> {
                    require(length in 16..65536)
                    val format = exact(length.toInt())
                    pcm16 = format[0].toInt() == 1 && format[1].toInt() == 0 && format[14].toInt() == 16 && format[15].toInt() == 0
                }
                "data" -> {
                    require(pcm16); val total = length / 2; require(total > 0)
                    val sums = DoubleArray(bins); val counts = LongArray(bins)
                    val buffer = ByteArray(8192); var left = length; var sample = 0L
                    while (left > 0) {
                        val want = minOf(left, buffer.size.toLong()).toInt(); var offset = 0
                        while (offset < want) { val n = input.read(buffer, offset, want - offset); require(n > 0); offset += n }
                        for (i in 0 until want - 1 step 2) {
                            val value = ((buffer[i].toInt() and 255) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
                            val bin = (sample * bins / total).toInt().coerceAtMost(bins - 1)
                            sums[bin] += value * value; counts[bin]++; sample++
                        }
                        left -= want
                    }
                    return FloatArray(bins) { sqrt(sums[it] / counts[it].coerceAtLeast(1)).toFloat() }
                }
                else -> skip(length)
            }
            if (length % 2 != 0L) skip(1)
        }
    }
}
