package app.peekturn.reader.narration

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persistent sentence cache: bounded LRU, atomic validated writes and no book text on disk names. */
class SentenceAudioCacheTest {

    private val directory: File = Files.createTempDirectory("peekturn-narration-cache").toFile()

    @After fun tearDown() {
        directory.deleteRecursively()
    }

    private fun frame() = ByteArray(417).also {
        it[0] = 0xFF.toByte()
        it[1] = 0xFB.toByte()
        it[2] = 0x90.toByte()
        it[3] = 0x64
    }

    private fun key(seed: Char) = seed.toString().repeat(64)

    @Test fun aValidatedMp3IsStoredUnderItsOpaqueKeyAndReadBack() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        val file = requireNotNull(cache.put(key('a'), frame()))
        assertEquals(frame().size.toLong(), file.length())
        assertTrue(file.name.matches(Regex("[0-9a-f]{64}\\.mp3")))
        assertEquals(file, cache.get(key('a')))
    }

    @Test fun theDefaultBudgetIsFourGiBLongAndDoesNotOverflowInt() = runBlocking {
        assertEquals(4L * 1024 * 1024 * 1024, NARRATION_CACHE_BYTES)
        assertTrue(NARRATION_CACHE_BYTES > Int.MAX_VALUE.toLong())
        val cache = SentenceAudioCache(directory)
        assertEquals(frame().size.toLong(), requireNotNull(cache.put(key('0'), frame())).length())
        assertEquals(frame().size.toLong(), cache.sizeBytes())
    }

    @Test fun accountingIsInitializedOnceFromExistingClipsAndSurvivesRecreation() = runBlocking {
        val first = SentenceAudioCache(directory, maxBytes = 10_000)
        assertNotNull(first.put(key('1'), frame()))
        assertNotNull(first.put(key('2'), frame()))
        assertNotNull(first.get(key('1')))
        assertEquals((frame().size * 2).toLong(), first.sizeBytes())

        val reopened = SentenceAudioCache(directory, maxBytes = 10_000)
        assertEquals((frame().size * 2).toLong(), reopened.sizeBytes())
        assertNotNull(reopened.get(key('2')))
    }

    @Test fun aCorruptClipIsRemovedAndItsBytesLeaveTheAccounting() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        val file = requireNotNull(cache.put(key('3'), frame()))
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.get(key('3')))
        assertEquals(0L, cache.sizeBytes())
        assertTrue(!file.exists())
    }

    @Test fun aLongBudgetStillHandlesSmallSyntheticClips() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = Long.MAX_VALUE)
        assertNotNull(cache.put(key('4'), frame()))
        assertNotNull(cache.put(key('5'), frame()))
        assertEquals((frame().size * 2).toLong(), cache.sizeBytes())
        assertNotNull(cache.get(key('4')))
    }

    @Test fun invalidAudioIsNeverWritten() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        assertNull(cache.put(key('b'), frame().copyOf(100)))
        assertNull(cache.get(key('b')))
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun theLeastRecentlyUsedEntryIsEvictedAtTheBound() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 900)
        val first = requireNotNull(cache.put(key('c'), frame()))
        first.setLastModified(1_000)
        val second = requireNotNull(cache.put(key('d'), frame()))
        second.setLastModified(2_000)
        val third = requireNotNull(cache.put(key('e'), frame()))
        third.setLastModified(3_000)

        assertNull("the oldest entry made room for the newest", cache.get(key('c')))
        assertNotNull(cache.get(key('d')))
        assertNotNull(cache.get(key('e')))
        // One over-bound write trims to the 95% low-water mark, not just below the hard cap.
        assertEquals((frame().size * 2).toLong(), cache.sizeBytes())
        assertTrue(cache.sizeBytes() <= 855L)
    }

    @Test fun readingAnEntryRefreshesItsLruPosition() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 900)
        val first = requireNotNull(cache.put(key('f'), frame()))
        first.setLastModified(1_000)
        val second = requireNotNull(cache.put(key('1'), frame()))
        second.setLastModified(2_000)
        // Touch the first key by reading it, then add another entry; the untouched second key goes.
        assertNotNull(cache.get(key('f')))
        val third = requireNotNull(cache.put(key('a'), frame()))
        third.setLastModified(3_000)
        assertNotNull(cache.get(key('f')))
        assertNotNull(cache.get(key('a')))
        assertNull(cache.get(key('1')))
    }

    @Test fun clearRemovesEveryCachedClip() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        assertNotNull(cache.put(key('a'), frame()))
        assertNotNull(cache.put(key('b'), frame()))
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.get(key('a')))
    }

    @Test fun aKeyThatCouldCarryTextIsRefused() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        assertNull(cache.put("Today is a perfect day", frame()))
        assertNull(cache.put("../secret", frame()))
    }
}
