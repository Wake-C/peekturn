package app.peekturn.reader.narration

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToLong

/**
 * RIFF/WAVE coverage for locally rendered sentence clips. Android's system speech engine writes a
 * complete WAV stream, so a cached clip is accepted only when its header, chunk lengths and PCM
 * frame alignment all agree with the bytes actually present. Validation streams the file: a clip is
 * never read into one byte array, because the renderer already wrote the bytes once.
 *
 * The parser understands standard RIFF chunk layout, skips chunks it does not use (LIST, fact,
 * bext, ...) and requires the audio data chunk to be complete. PCM 8/16-bit and IEEE float 32-bit
 * are the encodings Android's WAV extractor can decode; every other encoding is rejected.
 */
internal object WaveAudio {

    /** How the sample bytes in the data chunk are interpreted. */
    internal enum class Encoding { Pcm, IeeeFloat }

    /** A validated stream: one consistent sample format and the exact data chunk bounds. */
    internal data class Format(
        val encoding: Encoding,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataBytes: Long
    ) {
        val blockAlign: Int get() = channels * (bitsPerSample / 8)
        val frameCount: Long get() = dataBytes / blockAlign
        val durationMs: Long get() = (frameCount * 1000.0 / sampleRate).roundToLong()
    }

    /** Real duration of a complete wave, or null when the file is not playable as rendered. */
    fun durationMs(file: File): Long? = inspect(file)?.durationMs

    /**
     * The validated format of [file], or null for a missing, truncated, malformed, unsupported or
     * empty stream. Never throws for damaged input.
     */
    fun inspect(file: File): Format? = runCatching {
        if (!file.isFile) return null
        RandomAccessFile(file, "r").use { input -> inspect(input) }
    }.getOrNull()

    /** Reads and checks the RIFF header, every chunk boundary and the PCM frame alignment. */
    private fun inspect(input: RandomAccessFile): Format? {
        val fileLength = input.length()
        if (fileLength < RIFF_HEADER_BYTES) return null
        // The same per-clip ceiling the cache enforces, so direct callers cannot bypass it.
        if (fileLength > NARRATION_MAX_SENTENCE_AUDIO_BYTES) return null
        val header = ByteArray(RIFF_HEADER_BYTES)
        input.readFully(header)
        if (!header.matches(0, "RIFF") || !header.matches(8, "WAVE")) return null
        val riffSize = header.littleEndianInt(4).toLong() and 0xFFFFFFFFL
        if (riffSize < 4L) return null
        val riffEnd = 8L + riffSize
        // A declared stream that runs past the file is truncation, not a shorter clip.
        if (riffEnd > fileLength) return null

        var format: Format? = null
        var position = 12L
        while (position < riffEnd) {
            if (riffEnd - position < CHUNK_HEADER_BYTES) return null
            input.seek(position)
            val chunkHeader = ByteArray(CHUNK_HEADER_BYTES)
            input.readFully(chunkHeader)
            val size = chunkHeader.littleEndianInt(4).toLong() and 0xFFFFFFFFL
            val bodyStart = position + CHUNK_HEADER_BYTES
            if (size > riffEnd - bodyStart) return null
            val bodyEnd = bodyStart + size
            // RIFF pads odd-sized chunks; the padding byte belongs to the container.
            val paddedEnd = bodyEnd + (size and 1L)
            if (paddedEnd > riffEnd) return null

            when {
                chunkHeader.matches(0, "fmt ") -> {
                    if (format != null) return null
                    format = readFormat(input, bodyStart, size) ?: return null
                }
                chunkHeader.matches(0, "data") -> {
                    val parsed = format ?: return null
                    if (size <= 0L || size % parsed.blockAlign != 0L) return null
                    return parsed.copy(dataOffset = bodyStart, dataBytes = size)
                }
                // Unknown, ancillary chunks are part of standard files; their bytes are skipped.
            }
            position = paddedEnd
        }
        return null
    }

    /** Parses one `fmt ` chunk, including the extensible sub-format used by some engines. */
    private fun readFormat(input: RandomAccessFile, offset: Long, size: Long): Format? {
        if (size < 16L || size > MAX_FORMAT_CHUNK_BYTES) return null
        val bytes = ByteArray(size.toInt())
        input.seek(offset)
        input.readFully(bytes)
        var tag = bytes.littleEndianShort(0)
        val channels = bytes.littleEndianShort(2)
        val sampleRate = bytes.littleEndianInt(4)
        val byteRate = bytes.littleEndianInt(8).toLong() and 0xFFFFFFFFL
        val blockAlign = bytes.littleEndianShort(12)
        val bitsPerSample = bytes.littleEndianShort(14)

        if (tag == WAVE_FORMAT_EXTENSIBLE) {
            // WAVE_FORMAT_EXTENSIBLE carries the real tag in the first two GUID bytes and the
            // number of meaningful bits separately; PCM and float are the only sub-formats kept.
            if (size < 40L || !bytes.matchesExtensibleSubFormat()) return null
            tag = bytes.littleEndianShort(24)
            val validBits = bytes.littleEndianShort(18)
            if (validBits != 0 && validBits != bitsPerSample) return null
        }

        val encoding = when (tag) {
            WAVE_FORMAT_PCM -> Encoding.Pcm
            WAVE_FORMAT_IEEE_FLOAT -> Encoding.IeeeFloat
            else -> return null
        }
        if (channels !in 1..MAX_CHANNELS) return null
        if (sampleRate <= 0 || sampleRate > MAX_SAMPLE_RATE) return null
        val allowedBits = when (encoding) {
            Encoding.Pcm -> bitsPerSample == 8 || bitsPerSample == 16
            Encoding.IeeeFloat -> bitsPerSample == 32
        }
        if (!allowedBits) return null
        val expectedBlockAlign = channels * (bitsPerSample / 8)
        if (blockAlign != expectedBlockAlign) return null
        if (byteRate != sampleRate.toLong() * blockAlign) return null
        return Format(
            encoding = encoding,
            channels = channels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            dataOffset = 0L,
            dataBytes = 0L
        )
    }

    private fun ByteArray.matches(offset: Int, tag: String): Boolean {
        if (offset + tag.length > size) return false
        for (index in tag.indices) {
            if (this[offset + index] != tag[index].code.toByte()) return false
        }
        return true
    }

    private fun ByteArray.littleEndianShort(offset: Int): Int {
        if (offset + 2 > size) return -1
        return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.littleEndianInt(offset: Int): Int {
        if (offset + 4 > size) return -1
        return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
    }

    /** The standard PCM/float GUID tail after the two-byte format tag. */
    private fun ByteArray.matchesExtensibleSubFormat(): Boolean {
        if (EXTENSIBLE_GUID_TAIL.size + EXTENSIBLE_TAG_OFFSET > size) return false
        for (index in EXTENSIBLE_GUID_TAIL.indices) {
            if (this[EXTENSIBLE_TAG_OFFSET + index] != EXTENSIBLE_GUID_TAIL[index]) return false
        }
        return true
    }

    private const val RIFF_HEADER_BYTES = 12
    private const val CHUNK_HEADER_BYTES = 8
    private const val MAX_FORMAT_CHUNK_BYTES = 64L
    private const val MAX_CHANNELS = 8
    private const val MAX_SAMPLE_RATE = 384_000
    private const val EXTENSIBLE_TAG_OFFSET = 26
    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_IEEE_FLOAT = 3
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private val EXTENSIBLE_GUID_TAIL = byteArrayOf(
        0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71
    )
}
