package ru.neurostenographer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.AudioFormat
import java.io.File
import java.nio.ByteOrder

/** Android-декодер с seek к предыдущему sync frame; PCM целиком на диск/в память не распаковывается. */
internal class CodecChunks(file: File) : AudioChunkSource {
    private val extractor = MediaExtractor()
    private val format: MediaFormat
    override val durationMs: Long
    init {
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw AudioInputException("В файле нет аудиодорожки")
            extractor.selectTrack(track); format = extractor.getTrackFormat(track)
            durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
            require(durationMs > 0) { "Не удалось определить длительность записи" }
        } catch (e: Exception) { extractor.release(); throw AudioInputException(e.message ?: "Формат аудио не поддерживается") }
    }
    override fun read(startMs: Long, lengthMs: Long): FloatArray {
        val endMs = minOf(durationMs, startMs + lengthMs)
        val output = FloatArray(((endMs-startMs)*16).toInt().coerceAtLeast(0))
        if (output.isEmpty()) return output
        extractor.seekTo(startMs*1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0); codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var done = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var stalls = 0
            while (!done) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime >= (endMs+1000)*1000) {
                            codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true
                        } else { codec.queueInputBuffer(index,0,size,extractor.sampleTime,0); extractor.advance() }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f=codec.outputFormat; rate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding=if(f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "Неподдерживаемый PCM декодера" }
                    }
                    index >= 0 -> {
                        stalls=0
                        val buffer=codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset); buffer.limit(info.offset+info.size)
                        val bytes=if(encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                        val frames=info.size/(channels*bytes)
                        var previous = -1L
                        for(i in 0 until frames) {
                            var value=0f
                            repeat(channels) { value += if(bytes==4) buffer.float/channels else buffer.short/32768f/channels }
                            val timeUs=info.presentationTimeUs+i*1000000L/rate
                            val position=(timeUs-startMs*1000)*16000/1000000
                            if(timeUs >= startMs*1000 && position in 0 until output.size.toLong() && position != previous) output[position.toInt()]=value
                            // При частоте <16 кГц заполняем промежуточный отсчёт тем же значением.
                            if(rate<16000 && position>=0) for(p in position+1 until minOf(position+16000/rate,output.size.toLong())) output[p.toInt()]=value
                            previous=position
                        }
                        done=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs+frames*1000000L/rate >= endMs*1000
                        codec.releaseOutputBuffer(index,false)
                    }
                    else -> { stalls++; check(stalls<3000) { "Декодер перестал отвечать" } }
                }
            }
            return output
        } finally { runCatching { codec.stop() }; codec.release() }
    }
    override fun close() = extractor.release()
}
