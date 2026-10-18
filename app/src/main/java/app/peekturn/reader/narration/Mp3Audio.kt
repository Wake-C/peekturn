package app.peekturn.reader.narration

import java.io.ByteArrayOutputStream

/**
 * MPEG audio coverage for cached sentence clips. A provider answer is only accepted when every byte
 * after its optional tags is a complete, recognised MP3 frame: a truncated answer must mean "no
 * audio", never a shorter sentence.
 */
internal object Mp3Audio {

    private val mpeg1Layer1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val mpeg1Layer2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val mpeg1Layer3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val mpeg2Layer1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val mpeg2Layer23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    private val sampleRates = mapOf(
        3 to intArrayOf(44100, 48000, 32000),
        2 to intArrayOf(22050, 24000, 16000),
        0 to intArrayOf(11025, 12000, 8000)
    )

    /** True only when [bytes] is tags plus one or more complete frames and nothing partial. */
    fun complete(bytes: ByteArray): Boolean = audioPortion(bytes) != null

    /**
     * The frame bytes without ID3v2/ID3v1 metadata, or null when the answer is not entirely made of
     * complete frames. This is what gets concatenated for a long sentence.
     */
    fun audioPortion(bytes: ByteArray): ByteArray? {
        var start = 0
        if (bytes.size >= 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val size = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
            start = 10 + size + if ((bytes[5].toInt() and 0x10) != 0) 10 else 0
            if (start > bytes.size) return null
        }

        var end = bytes.size
        if (end - start >= 128 && bytes[end - 128] == 'T'.code.toByte() &&
            bytes[end - 127] == 'A'.code.toByte() && bytes[end - 126] == 'G'.code.toByte()
        ) {
            end -= 128
        }

        var position = start
        var first = -1
        while (position < end) {
            val length = frameLength(bytes, position, end) ?: return null
            if (first < 0) first = position
            position += length
        }
        if (first < 0 || position != end) return null
        return bytes.copyOfRange(first, end)
    }

    /** Two or more provider answers as one frame sequence, or null when any part is incomplete. */
    fun concat(chunks: List<ByteArray>): ByteArray? {
        if (chunks.isEmpty()) return null
        val output = ByteArrayOutputStream()
        for (chunk in chunks) {
            val portion = audioPortion(chunk) ?: return null
            output.write(portion)
        }
        val joined = output.toByteArray()
        return joined.takeIf(::complete)
    }

    /** The frame's total length from its header at [offset], bounded by [until]. */
    private fun frameLength(bytes: ByteArray, offset: Int, until: Int): Int? {
        if (offset + 4 > until) return null
        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null

        val version = (b1 shr 3) and 0x03
        val layer = (b1 shr 1) and 0x03
        if (version == 1 || layer == 0) return null

        val bitrateIndex = (b2 shr 4) and 0x0F
        val sampleIndex = (b2 shr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleIndex == 3) return null

        val bitrateTable = when {
            version == 3 && layer == 3 -> mpeg1Layer1
            version == 3 && layer == 2 -> mpeg1Layer2
            version == 3 -> mpeg1Layer3
            layer == 3 -> mpeg2Layer1
            else -> mpeg2Layer23
        }
        val sampleRate = sampleRates.getValue(version)[sampleIndex]
        val bitrate = bitrateTable[bitrateIndex] * 1000
        val padding = (b2 shr 1) and 1

        val length = when {
            layer == 3 -> (12 * bitrate / sampleRate + padding) * 4
            layer == 2 -> 144 * bitrate / sampleRate + padding
            version == 3 -> 144 * bitrate / sampleRate + padding
            else -> 72 * bitrate / sampleRate + padding
        }
        if (length < 4 || offset + length > until) return null
        // Reserved emphasis bits are not audio.
        if ((b3 and 0x03) == 0x02) return null
        return length
    }
}
