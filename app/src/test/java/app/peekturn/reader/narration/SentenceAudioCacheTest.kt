package app.peekturn.reader.narration

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private fun wave(dataSize: Int = 4_000) = WaveFixtures.wave(dataSize = dataSize)

    /**
     * A real rendered wave, not a synthetic header: the stored clip must be the complete stream,
     * carry the duration the decoder will hear, and a truncated copy of it must never hit.
     */
    @Test fun aCompletePcmWaveIsStoredWithItsRealDurationAndATruncatedClipNeverHits() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 100_000)
        val rendered = File(directory, "render.wav")
        rendered.writeBytes(wave())

        val stored = requireNotNull(cache.putWave(key('7'), rendered))
        assertEquals(250L, stored.durationMs)
        assertFalse(stored.fromCache)
        assertTrue(stored.file.name.matches(Regex("[0-9a-f]{64}\\.wav")))
        assertTrue("the rendered source became the cached clip", !rendered.exists())

        val hit = requireNotNull(cache.get(key('7'), NarrationAudioFormat.Wave))
        assertTrue(hit.fromCache)
        assertEquals(250L, hit.durationMs)
        assertEquals(stored.file, hit.file)

        // A new store instance must find the clip without any in-memory state.
        val reopened = SentenceAudioCache(directory, maxBytes = 100_000)
        val persisted = requireNotNull(reopened.get(key('7'), NarrationAudioFormat.Wave))
        assertEquals(250L, persisted.durationMs)
        assertEquals(stored.file, persisted.file)

        val complete = persisted.file.readBytes()
        persisted.file.writeBytes(complete.copyOf(complete.size - 100))
        assertNull(
            "a truncated wave is not playable audio",
            cache.get(key('7'), NarrationAudioFormat.Wave)
        )
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun aValidatedMp3IsStoredUnderItsOpaqueKeyWithItsFrameDuration() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        val stored = requireNotNull(cache.put(key('a'), frame()))
        assertEquals(frame().size.toLong(), stored.file.length())
        assertTrue(stored.file.name.matches(Regex("[0-9a-f]{64}\\.mp3")))
        assertEquals(26L, stored.durationMs)
        assertFalse(stored.fromCache)

        val hit = requireNotNull(cache.get(key('a')))
        assertTrue(hit.fromCache)
        assertEquals(stored.file, hit.file)
        assertEquals(26L, hit.durationMs)
    }

    @Test fun theDefaultBudgetIsFourGiBLongAndDoesNotOverflowInt() = runBlocking {
        assertEquals(4L * 1024 * 1024 * 1024, NARRATION_CACHE_BYTES)
        assertTrue(NARRATION_CACHE_BYTES > Int.MAX_VALUE.toLong())
        val cache = SentenceAudioCache(directory)
        assertEquals(frame().size.toLong(), requireNotNull(cache.put(key('0'), frame())).file.length())
        assertEquals(frame().size.toLong(), cache.sizeBytes())
    }

    @Test fun accountingIsInitializedOnceFromExistingClipsAndSurvivesRecreation() = runBlocking {
        val first = SentenceAudioCache(directory, maxBytes = 10_000)
        assertNotNull(first.put(key('1'), frame()))
        assertNotNull(first.putWave(key('2'), File(directory, "one.wav").apply { writeBytes(wave()) }))
        assertNotNull(first.get(key('1')))
        assertEquals((frame().size + wave().size).toLong(), first.sizeBytes())

        val reopened = SentenceAudioCache(directory, maxBytes = 10_000)
        assertEquals((frame().size + wave().size).toLong(), reopened.sizeBytes())
        assertNotNull(reopened.get(key('1')))
        assertNotNull(reopened.get(key('2'), NarrationAudioFormat.Wave))
    }

    @Test fun aCorruptClipIsRemovedAndItsBytesLeaveTheAccounting() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        val file = requireNotNull(cache.put(key('3'), frame())).file
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

    @Test fun aMalformedWaveIsNotStoredAndCannotHit() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        val source = File(directory, "broken.wav")
        source.writeBytes(wave().copyOf(120))
        assertNull(cache.putWave(key('6'), source))
        assertNull(cache.get(key('6'), NarrationAudioFormat.Wave))
        assertEquals(0L, cache.sizeBytes())
        assertTrue("an unusable source stays the caller's to delete", source.exists())
    }

    @Test fun aWaveAboveThePerClipCeilingIsRefusedBeforeItIsParsed() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = Long.MAX_VALUE)
        val source = File(directory, "oversized.wav")
        RandomAccessFile(source, "rw").use { it.setLength(NARRATION_MAX_SENTENCE_AUDIO_BYTES + 1) }
        assertNull(cache.putWave(key('8'), source))
        assertTrue(!File(directory, "${key('8')}.wav").exists())
        assertEquals(0L, cache.sizeBytes())
    }

    @Test fun theLeastRecentlyUsedEntryIsEvictedAtTheBound() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 900)
        val first = requireNotNull(cache.put(key('c'), frame())).file
        first.setLastModified(1_000)
        val second = requireNotNull(cache.put(key('d'), frame())).file
        second.setLastModified(2_000)
        val third = requireNotNull(cache.put(key('e'), frame())).file
        third.setLastModified(3_000)

        assertNull("the oldest entry made room for the newest", cache.get(key('c')))
        assertNotNull(cache.get(key('d')))
        assertNotNull(cache.get(key('e')))
        // One over-bound write trims to the 95% low-water mark, not just below the hard cap.
        assertEquals((frame().size * 2).toLong(), cache.sizeBytes())
        assertTrue(cache.sizeBytes() <= 855L)
    }

    @Test fun bothFormatsShareOneQuotaAndOneLeastRecentlyUsedOrder() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 1_200)
        val mp3 = requireNotNull(cache.put(key('1'), frame())).file
        mp3.setLastModified(1_000)
        val firstWave = requireNotNull(
            cache.putWave(key('2'), File(directory, "first.wav").apply { writeBytes(wave(400)) })
        ).file
        firstWave.setLastModified(2_000)
        val secondWave = requireNotNull(
            cache.putWave(key('3'), File(directory, "second.wav").apply { writeBytes(wave(400)) })
        ).file
        secondWave.setLastModified(3_000)

        // 417 + 444 + 444 crossed 1200, so the oldest MP3 made room and both waves remain.
        assertNull(cache.get(key('1')))
        assertNotNull(cache.get(key('2'), NarrationAudioFormat.Wave))
        assertNotNull(cache.get(key('3'), NarrationAudioFormat.Wave))
        assertEquals((444L * 2), cache.sizeBytes())
    }

    @Test fun readingAnEntryRefreshesItsLruPosition() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 900)
        val first = requireNotNull(cache.put(key('f'), frame())).file
        first.setLastModified(1_000)
        val second = requireNotNull(cache.put(key('1'), frame())).file
        second.setLastModified(2_000)
        // Touch the first key by reading it, then add another entry; the untouched second key goes.
        assertNotNull(cache.get(key('f')))
        val third = requireNotNull(cache.put(key('a'), frame())).file
        third.setLastModified(3_000)
        assertNotNull(cache.get(key('f')))
        assertNotNull(cache.get(key('a')))
        assertNull(cache.get(key('1')))
    }

    @Test fun clearRemovesEveryCachedClipInBothFormats() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        assertNotNull(cache.put(key('a'), frame()))
        assertNotNull(cache.putWave(key('b'), File(directory, "clip.wav").apply { writeBytes(wave()) }))
        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.get(key('a')))
        assertNull(cache.get(key('b'), NarrationAudioFormat.Wave))
    }

    @Test fun aWriteBegunBeforeClearCannotRepopulateTheCache() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        val epoch = cache.epoch()
        val source = File(directory, "stale.wav").apply { writeBytes(wave()) }
        cache.clear()

        assertNull(cache.putWave(key('9'), source, expectedEpoch = epoch))
        assertNull(cache.put(key('9'), frame(), expectedEpoch = epoch))
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.get(key('9'), NarrationAudioFormat.Wave))
        assertNull(cache.get(key('9')))
        assertTrue("a rejected source was not consumed", source.exists())
    }

    @Test fun differentIdentitiesNeverCollideAcrossFormats() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 100_000)
        val shortWave = requireNotNull(
            cache.putWave(key('1'), File(directory, "short.wav").apply { writeBytes(wave(400)) })
        )
        val longWave = requireNotNull(
            cache.putWave(key('2'), File(directory, "long.wav").apply { writeBytes(wave(8_000)) })
        )
        val mp3 = requireNotNull(cache.put(key('3'), frame()))

        assertEquals(25L, shortWave.durationMs)
        assertEquals(500L, longWave.durationMs)
        assertEquals(26L, mp3.durationMs)
        assertNotEquals(shortWave.file, longWave.file)
        assertEquals(shortWave.file, cache.get(key('1'), NarrationAudioFormat.Wave)?.file)
        assertEquals(longWave.file, cache.get(key('2'), NarrationAudioFormat.Wave)?.file)
        assertEquals(mp3.file, cache.get(key('3'))?.file)
        assertNull("a wave key is not an MP3 hit", cache.get(key('1')))
        assertNull("an MP3 key is not a wave hit", cache.get(key('3'), NarrationAudioFormat.Wave))
    }

    @Test fun aKeyThatCouldCarryTextIsRefused() = runBlocking {
        val cache = SentenceAudioCache(directory, maxBytes = 10_000)
        assertNull(cache.put("Today is a perfect day", frame()))
        assertNull(cache.put("../secret", frame()))
        assertNull(cache.putWave("../secret", File(directory, "x.wav").apply { writeBytes(wave()) }))
        assertTrue(directory.listFiles().orEmpty().none { it.name.contains("perfect") })
    }
}
