package ru.neurostenographer

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** PCM16 RIFF/RF64: позиционирование Long, ограниченный буфер независимо от длины файла. */
internal class PcmChunks(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private var offset = 0L
    private var frames = 0L
    private var rate = 0
    private var channels = 0
    val durationMs: Long get() = frames * 1000L / rate
    init {
        try {
            val kind = ascii(4)
            require(kind == "RIFF" || kind == "RF64") { "Ожидается WAV RIFF/RF64" }
            u32(); require(ascii(4) == "WAVE") { "Ожидается WAVE" }
            var code = -1; var bits = 0; var largeData = -1L
            while (input.filePointer + 8 <= input.length()) {
                val id = ascii(4); var length = u32(); val start = input.filePointer
                if (id == "ds64") {
                    require(length >= 28); i64(); largeData = i64(); require(largeData >= 0)
                }
                if (id == "fmt ") {
                    require(length >= 16); code = u16(); channels = u16(); rate = u32().toInt()
                    u32(); u16(); bits = u16()
                }
                if (id == "data") {
                    if (length == 0xffffffffL && kind == "RF64") { require(largeData >= 0); length = largeData }
                    require(code == 1 && bits == 16) { "Для WAV нужен PCM16; сжатые записи импортируйте как MP3/M4A" }
                    require(channels in 1..2 && rate in 8000..192000) { "Неподдерживаемые каналы/частота WAV" }
                    require(length > 0 && length <= input.length() - start && length % (channels * 2) == 0L) { "Неполный PCM WAV" }
                    offset = start; frames = length / (channels * 2); break
                }
                require(length <= input.length() - start) { "Повреждённый WAV chunk" }
                input.seek(start + length + (length and 1))
            }
            require(frames > 0) { "В WAV нет аудио" }
        } catch (error: Exception) { input.close(); throw AudioInputException(error.message ?: "Повреждённый WAV") }
    }
    fun read(startMs: Long, lengthMs: Long): FloatArray {
        val first = startMs * rate / 1000
        val last = minOf(frames, (startMs + lengthMs) * rate / 1000)
        if (last <= first) return FloatArray(0)
        val count = ((last - first) * 16000 / rate).toInt()
        val samples = FloatArray(count)
        val frameBytes = channels * 2
        val buffer = ByteArray(65536 / frameBytes * frameBytes)
        input.seek(offset + first * frameBytes)
        var sourceFrame = 0L; var out = 0; var left = last - first
        while (left > 0 && out < count) {
            val n = minOf(left, (buffer.size / frameBytes).toLong()).toInt()
            input.readFully(buffer, 0, n * frameBytes)
            for (i in 0 until n) {
                val target = sourceFrame * 16000 / rate
                var value = 0f
                for (c in 0 until channels) {
                    val p = i * frameBytes + c * 2
                    value += ((buffer[p].toInt() and 255) or (buffer[p+1].toInt() shl 8)).toShort() / 32768f / channels
                }
                while (out < count && out <= target) samples[out++] = value
                sourceFrame++
            }
            left -= n
        }
        while (out < count) { samples[out] = if (out > 0) samples[out-1] else 0f; out++ }
        return samples
    }
    private fun ascii(n: Int) = ByteArray(n).also { input.readFully(it) }.toString(Charsets.US_ASCII)
    private fun u16() = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    private fun u32() = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()))
    private fun i64() = java.lang.Long.reverseBytes(input.readLong())
    override fun close() = input.close()
}

internal interface AudioChunkSource : Closeable {
    val durationMs: Long
    fun read(startMs: Long, lengthMs: Long): FloatArray
}

internal fun openAudioChunks(file: File): AudioChunkSource {
    val magic = RandomAccessFile(file,"r").use { input -> ByteArray(4).also { input.readFully(it) }.toString(Charsets.US_ASCII) }
    if (magic == "RIFF" || magic == "RF64") {
        val pcm = PcmChunks(file)
        return object : AudioChunkSource {
            override val durationMs get() = pcm.durationMs
            override fun read(startMs: Long, lengthMs: Long) = pcm.read(startMs, lengthMs)
            override fun close() = pcm.close()
        }
    }
    return CodecChunks(file)
}
