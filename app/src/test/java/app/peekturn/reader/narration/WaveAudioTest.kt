package app.peekturn.reader.narration

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The WAV parser accepts only complete, aligned PCM streams the system decoder can play. */
class WaveAudioTest {

    private val directory: File = Files.createTempDirectory("peekturn-wave-audio").toFile()

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun inspect(bytes: ByteArray): WaveAudio.Format? =
        WaveAudio.inspect(File(directory, "clip.wav").apply { writeBytes(bytes) })

    @Test fun aCompletePcm16StreamReportsItsRealDurationAndSkipsUnknownChunks() {
        val format = requireNotNull(
            inspect(
                WaveFixtures.wave(
                    sampleRate = 8_000,
                    dataSize = 4_000,
                    extrasBeforeData = listOf(
                        "LIST" to ByteArray(3) { it.toByte() },
                        "fact" to byteArrayOf(0, 0, 0, 0)
                    )
                )
            )
        )
        assertEquals(WaveAudio.Encoding.Pcm, format.encoding)
        assertEquals(1, format.channels)
        assertEquals(8_000, format.sampleRate)
        assertEquals(16, format.bitsPerSample)
        assertEquals(4_000L, format.dataBytes)
        assertEquals(250L, format.durationMs)
        assertEquals(250L, WaveAudio.durationMs(File(directory, "clip.wav")))
    }

    @Test fun eightBitPcmAndFloat32AreAccepted() {
        val pcm8 = requireNotNull(
            inspect(WaveFixtures.wave(sampleRate = 8_000, bitsPerSample = 8, dataSize = 8_000))
        )
        assertEquals(WaveAudio.Encoding.Pcm, pcm8.encoding)
        assertEquals(1_000L, pcm8.durationMs)

        val float = requireNotNull(
            inspect(
                WaveFixtures.wave(
                    encodingTag = 3,
                    sampleRate = 16_000,
                    bitsPerSample = 32,
                    dataSize = 16_000
                )
            )
        )
        assertEquals(WaveAudio.Encoding.IeeeFloat, float.encoding)
        assertEquals(250L, float.durationMs)
    }

    @Test fun anExtensiblePcmSubFormatIsAccepted() {
        val format = requireNotNull(inspect(WaveFixtures.extensible(encodingTag = 1)))
        assertEquals(WaveAudio.Encoding.Pcm, format.encoding)
        assertEquals(250L, format.durationMs)
    }

    @Test fun truncatedHeadersAndDataAreRefused() {
        val complete = WaveFixtures.wave()
        assertNull(inspect(complete.copyOf(11)))
        assertNull(inspect(complete.copyOf(44)))
        assertNull(inspect(complete.copyOf(complete.size - 1)))
        // A data chunk that claims more bytes than the RIFF body contains is truncation.
        assertNull(inspect(WaveFixtures.wave(dataSize = 4_000, dataChunkSizeOverride = 4_100)))
    }

    @Test fun malformedHeaderFieldsAreRefused() {
        assertNull("zero sample rate", inspect(WaveFixtures.wave(sampleRate = 0)))
        assertNull("zero channels", inspect(WaveFixtures.wave(channels = 0)))
        assertNull("bad block alignment", inspect(WaveFixtures.wave(blockAlignOverride = 3)))
        assertNull("bad byte rate", inspect(WaveFixtures.wave(byteRateOverride = 1234)))
        assertNull("unsupported encoding", inspect(WaveFixtures.wave(encodingTag = 6)))
        assertNull("24-bit PCM is not a system WAV format", inspect(WaveFixtures.wave(bitsPerSample = 24)))
        assertNull("data before fmt", inspect(WaveFixtures.wave(fmtAfterData = true)))
        assertNull("no fmt chunk", inspect(WaveFixtures.wave(includeFmt = false)))
        assertNull("wrong RIFF tag", inspect(WaveFixtures.wave(riffTag = "RIFX")))
        assertNull("wrong WAVE tag", inspect(WaveFixtures.wave(waveTag = "AVI ")))
    }

    @Test fun emptyOrMisalignedAudioIsRefused() {
        assertNull("zero audio frames", inspect(WaveFixtures.wave(dataSize = 0)))
        assertNull(
            "a partial sample frame cannot be played",
            inspect(WaveFixtures.wave(dataSize = 4_001))
        )
    }

    @Test fun aDeclaredStreamLongerThanTheFileIsRefused() {
        assertNull(inspect(WaveFixtures.wave(riffSizeDelta = 8)))
    }
}

/**
 * Real RIFF bytes for parser tests. Every value is explicit so a malformed case changes exactly one
 * header field instead of relying on a parser to produce its own input.
 */
internal object WaveFixtures {

    private const val GUID_TAIL = "000000001000800000aa00389b71"

    fun wave(
        encodingTag: Int = 1,
        channels: Int = 1,
        sampleRate: Int = 8_000,
        bitsPerSample: Int = 16,
        dataSize: Int = 4_000,
        blockAlignOverride: Int? = null,
        byteRateOverride: Int? = null,
        dataChunkSizeOverride: Int? = null,
        riffSizeDelta: Int = 0,
        riffTag: String = "RIFF",
        waveTag: String = "WAVE",
        includeFmt: Boolean = true,
        fmtAfterData: Boolean = false,
        extrasBeforeData: List<Pair<String, ByteArray>> = emptyList()
    ): ByteArray {
        val blockAlign = blockAlignOverride ?: (channels * (bitsPerSample / 8))
        val byteRate = byteRateOverride ?: (sampleRate * blockAlign)
        val format = ByteArray(16)
        format.putShort(0, encodingTag)
        format.putShort(2, channels)
        format.putInt(4, sampleRate)
        format.putInt(8, byteRate)
        format.putShort(12, blockAlign)
        format.putShort(14, bitsPerSample)
        val samples = ByteArray(dataSize) { (it % 251).toByte() }
        return riff(
            riffTag, waveTag, samples, dataChunkSizeOverride, riffSizeDelta, includeFmt,
            fmtAfterData, extrasBeforeData,
            format
        )
    }

    fun extensible(encodingTag: Int): ByteArray {
        val format = ByteArray(40)
        format.putShort(0, 0xFFFE)
        format.putShort(2, 1)
        format.putInt(4, 8_000)
        format.putInt(8, 16_000)
        format.putShort(12, 2)
        format.putShort(14, 16)
        format.putShort(18, 16)
        format.putShort(24, encodingTag)
        // Sub-format GUID: tag is already at 24; the standard tail follows it.
        val tail = GUID_TAIL.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        tail.copyInto(format, 26)
        val samples = ByteArray(4_000) { (it % 251).toByte() }
        return riff("RIFF", "WAVE", samples, null, 0, true, false, emptyList(), format)
    }

    private fun riff(
        riffTag: String,
        waveTag: String,
        samples: ByteArray,
        dataChunkSizeOverride: Int?,
        riffSizeDelta: Int,
        includeFmt: Boolean,
        fmtAfterData: Boolean,
        extrasBeforeData: List<Pair<String, ByteArray>>,
        format: ByteArray
    ): ByteArray {
        val chunks = ArrayList<Pair<String, ByteArray>>()
        if (!fmtAfterData) {
            if (includeFmt) chunks += "fmt " to format
            chunks += extrasBeforeData
            chunks += "data" to samples
        } else {
            chunks += "data" to samples
            if (includeFmt) chunks += "fmt " to format
            chunks += extrasBeforeData
        }
        val bodySize = chunks.sumOf { 8 + it.second.size + (it.second.size and 1) }
        val bytes = ByteArray(12 + bodySize)
        bytes.putTag(0, riffTag)
        // RIFF size covers "WAVE" plus every chunk, i.e. the file length minus the first 8 bytes.
        bytes.putInt(4, 4 + bodySize + riffSizeDelta)
        bytes.putTag(8, waveTag)
        var position = 12
        for ((id, body) in chunks) {
            bytes.putTag(position, id)
            bytes.putInt(
                position + 4,
                if (id == "data") dataChunkSizeOverride ?: body.size else body.size
            )
            body.copyInto(bytes, position + 8)
            position += 8 + body.size + (body.size and 1)
        }
        return bytes
    }

    private fun ByteArray.putTag(offset: Int, tag: String) {
        for (index in tag.indices) this[offset + index] = tag[index].code.toByte()
    }

    private fun ByteArray.putShort(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = (value ushr 8 and 0xFF).toByte()
    }

    private fun ByteArray.putInt(offset: Int, value: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = (value ushr 8 and 0xFF).toByte()
        this[offset + 2] = (value ushr 16 and 0xFF).toByte()
        this[offset + 3] = (value ushr 24 and 0xFF).toByte()
    }
}
