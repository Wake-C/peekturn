package app.peekturn.reader.narration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only complete MPEG frame sequences may reach the cache or the decoder. */
class Mp3AudioTest {

    private fun frame() = ByteArray(417).also {
        it[0] = 0xFF.toByte()
        it[1] = 0xFB.toByte()
        it[2] = 0x90.toByte()
        it[3] = 0x64
    }

    @Test fun oneCompleteFrameIsAudio() {
        assertTrue(Mp3Audio.complete(frame()))
    }

    @Test fun aTruncatedFrameIsNotComplete() {
        assertFalse(Mp3Audio.complete(frame().copyOf(416)))
        assertFalse(Mp3Audio.complete(byteArrayOf(0xFF.toByte(), 0xFB.toByte())))
    }

    @Test fun id3TagsAroundFramesAreAcceptedAndStripped() {
        val id3v2 = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0
        )
        val id3v1 = byteArrayOf('T'.code.toByte(), 'A'.code.toByte(), 'G'.code.toByte()) + ByteArray(125)
        val withTags = id3v2 + frame() + frame() + id3v1

        assertTrue(Mp3Audio.complete(withTags))
        val portion = requireNotNull(Mp3Audio.audioPortion(withTags))
        assertArrayEquals(frame() + frame(), portion)
        // Each provider answer may carry its own leading tag; concatenation strips each one.
        assertArrayEquals(frame() + frame(), Mp3Audio.concat(listOf(id3v2 + frame(), id3v2 + frame())))
    }

    @Test fun concatenatingChunksKeepsEveryFrame() {
        val joined = requireNotNull(Mp3Audio.concat(listOf(frame(), frame())))
        assertEquals(834, joined.size)
        assertTrue(Mp3Audio.complete(joined))
    }

    @Test fun concatenatingAnInvalidChunkFailsWithoutProducingAudio() {
        assertNull(Mp3Audio.concat(listOf(frame(), frame().copyOf(100))))
        assertNull(Mp3Audio.concat(emptyList()))
    }

    @Test fun durationIsTheSumOfRealFrameTimeNotTheFileSize() {
        // One MPEG-1 Layer III frame is 1152 samples at 44.1 kHz: 26 ms, not the 417-byte length.
        assertEquals(26L, Mp3Audio.durationMs(frame()))
        assertEquals(52L, Mp3Audio.durationMs(frame() + frame()))
        assertNull(Mp3Audio.durationMs(frame().copyOf(416)))
        assertNull(Mp3Audio.durationMs(byteArrayOf()))
    }
}
