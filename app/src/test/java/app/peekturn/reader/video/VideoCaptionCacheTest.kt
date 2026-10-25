package app.peekturn.reader.video

import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoCaptionCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var directory: File

    @Before fun setUp() {
        directory = temporary.newFolder("video-captions")
    }

    @Test fun parsedTrackIsReadBackOnlyForItsExactVideoAndTrack() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val document = document("video-one", "en-manual", cues = cues(3, "hello"))

        cache.putOriginal(document, cache.epoch("video-one"))

        assertEquals(document, cache.original("video-one", "en-manual"))
        assertNull(cache.original("video-one", "en-auto"))
        assertNull(cache.original("video-two", "en-manual"))
        assertEquals(0L, cache.sizeBytes("video-two"))
    }

    @Test fun sourceWordStartsSurviveCacheRoundTripAndTamperingIsRejected() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val doc = parseVideoCaptions("video-one", CaptionTrack("en", "en", true, "unused"),
            "WEBVTT\n\n00:00:01.000 --> 00:00:05.000\nHello<00:00:02.000><c> there.</c><00:00:03.000><c> Next.</c>\n")
        cache.putOriginal(doc, 0)
        val restored = cache.original("video-one", "en")!!
        assertEquals(doc, restored)
        assertEquals(3_000L, sentenceCaptions(restored).last().startMs)
        val file = directory.walkTopDown().first { it.isFile }
        val record = JsonParser.parseString(file.readText()).asJsonObject
        record.getAsJsonArray("cues")[0].asJsonObject.getAsJsonArray("words")[1]
            .asJsonObject.addProperty("startMs", 9_999)
        file.writeText(record.toString())
        assertNull(cache.original("video-one", "en"))
    }

    @Test fun legacyTextOnlyCaptionCacheRemainsReadable() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val doc = document("video-one", "en")
        cache.putOriginal(doc, 0)
        val file = directory.walkTopDown().first { it.isFile }
        val record = JsonParser.parseString(file.readText()).asJsonObject
        record.addProperty("format", "video-captions-v1")
        file.writeText(record.toString())
        assertEquals(doc, cache.original("video-one", "en"))
    }

    @Test fun corruptRecordIsAMissAndIsRemovedInsteadOfReturningEmptyCaptions() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val document = document("video-one", "en-manual")
        cache.putOriginal(document, 0)
        assertTrue(cache.sizeBytes("video-one") > 0)

        val stored = directory.walkTopDown().first { it.isFile }
        stored.writeText("""{"format":"video-captions-v1","videoId":"video-one"}""")

        assertNull(cache.original("video-one", "en-manual"))
        assertEquals(0L, cache.sizeBytes("video-one"))
        assertEquals(0L, directory.walkTopDown().filter { it.isFile }.count().toLong())
    }

    @Test fun changedCaptionTextCannotReuseTheOriginalFingerprint() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val document = parseVideoCaptions(
            "video-one", CaptionTrack("en-manual", "en", false, "unused"),
            "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nhello\n"
        )
        cache.putOriginal(document, 0)
        assertEquals(document, cache.original("video-one", "en-manual"))
        val stored = directory.walkTopDown().first { it.isFile }
        val record = JsonParser.parseString(stored.readText()).asJsonObject
        record.getAsJsonArray("cues")[0].asJsonObject.addProperty("text", "changed")
        stored.writeText(record.toString())

        assertNull(cache.original("video-one", "en-manual"))
        assertEquals(0L, cache.sizeBytes("video-one"))
    }

    @Test fun fullClearRejectsProducersThatStartedBeforeIt() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val stale = cache.epoch("video-one")
        val untouched = cache.epoch("video-two")
        cache.putOriginal(document("video-two", "en-manual"), untouched)

        cache.clear("video-one", mergedOnly = false)
        cache.putOriginal(document("video-one", "en-manual"), stale)

        assertNull(cache.original("video-one", "en-manual"))
        assertEquals(stale + 1, cache.epoch("video-one"))
        assertEquals(untouched, cache.epoch("video-two"))
        assertNotNull(cache.original("video-two", "en-manual"))

        // The generation a new producer observes is accepted again.
        cache.putOriginal(document("video-one", "en-manual"), cache.epoch("video-one"))
        assertNotNull(cache.original("video-one", "en-manual"))
    }

    @Test fun clearingMergedOnlyKeepsParsedCaptionsAndStopsStaleGroupWriters() = runBlocking {
        val cache = VideoCaptionCache(directory)
        // Three cues, so the second group really spans cue ids 1..2 of this document.
        val document = document("video-one", "en-manual", cues = cues(3, "line"))
        cache.putOriginal(document, 0)
        val groups = listOf(CaptionGroup(0, 0), CaptionGroup(1, 2))
        cache.putGroups(document, "merge-key", groups, 0)
        assertEquals(groups, cache.groups(document, "merge-key"))
        assertTrue(cache.sizeBytes("video-one", mergedOnly = true) > 0)

        cache.clear("video-one", mergedOnly = true)

        assertNull(cache.groups(document, "merge-key"))
        assertEquals(0L, cache.sizeBytes("video-one", mergedOnly = true))
        assertNotNull(cache.original("video-one", "en-manual"))
        assertTrue(cache.sizeBytes("video-one") > 0)

        cache.putGroups(document, "merge-key", groups, 0)
        assertNull("A stale generation must not repopulate a cleared cache", cache.groups(document, "merge-key"))

        cache.putGroups(document, "merge-key", groups, cache.epoch("video-one"))
        assertEquals(groups, cache.groups(document, "merge-key"))
    }

    @Test fun groupRecordsAreBoundToTheDocumentVersionAndMergeIdentity() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val document = document("video-one", "en-manual", sourceHash = "a".repeat(64))
        val groups = listOf(CaptionGroup(0, 1))
        cache.putGroups(document, "identity-a", groups, 0)

        assertEquals(groups, cache.groups(document, "identity-a"))
        assertNull("Another model identity is a different result", cache.groups(document, "identity-b"))
        assertNull("A changed source version is a different document",
            cache.groups(document.copy(sourceHash = "b".repeat(64)), "identity-a"))
        assertNull("Another track is a different document",
            cache.groups(document.copy(trackId = "en-auto"), "identity-a"))
        assertNull(cache.groups(document.copy(videoId = "video-two"), "identity-a"))
    }

    @Test fun structurallyImpossibleGroupsAreTreatedAsMisses() = runBlocking {
        val cache = VideoCaptionCache(directory)
        // Three real cues (ids 0..2), so only the deliberately corrupt records above are rejected.
        val document = document("video-one", "en-manual", cues = cues(3, "line"))

        cache.putGroups(document, "unknown-cue", listOf(CaptionGroup(0, 9)), 0)
        assertNull(cache.groups(document, "unknown-cue"))

        cache.putGroups(document, "reversed", listOf(CaptionGroup(1, 0)), 0)
        assertNull(cache.groups(document, "reversed"))

        cache.putGroups(document, "unordered", listOf(CaptionGroup(1, 1), CaptionGroup(0, 0)), 0)
        assertNull(cache.groups(document, "unordered"))

        val valid = listOf(CaptionGroup(0, 0), CaptionGroup(1, 2))
        cache.putGroups(document, "valid", valid, 0)
        assertEquals(valid, cache.groups(document, "valid"))
    }

    @Test fun replacingGroupsForTheSameIdentityKeepsOnlyTheNewest() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val document = document("video-one", "en-manual", cues = cues(3, "line"))

        cache.putGroups(document, "identity", listOf(CaptionGroup(0, 2)), 0)
        val newer = listOf(CaptionGroup(0, 0), CaptionGroup(1, 2))
        cache.putGroups(document, "identity", newer, 0)

        assertEquals(newer, cache.groups(document, "identity"))
    }

    @Test fun sizesAreSeparatedByKindAndVideo() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val first = document("video-one", "en-manual", cues = cues(4, "first"))
        val second = document("video-two", "en-manual", cues = cues(4, "first"))
        cache.putOriginal(first, 0)
        cache.putOriginal(second, 0)
        val groups = listOf(CaptionGroup(0, 1), CaptionGroup(2, 3))
        cache.putGroups(first, "identity", groups, 0)

        val originalBytes = cache.sizeBytes("video-one") - cache.sizeBytes("video-one", mergedOnly = true)
        assertTrue(originalBytes > 0)
        assertTrue(cache.sizeBytes("video-one", mergedOnly = true) > 0)
        assertEquals(originalBytes + cache.sizeBytes("video-one", mergedOnly = true), cache.sizeBytes("video-one"))
        assertEquals(cache.sizeBytes("video-one") - cache.sizeBytes("video-one", mergedOnly = true),
            cache.sizeBytes("video-two"))
        assertEquals(0L, cache.sizeBytes("video-three", mergedOnly = true))
    }

    @Test fun budgetEvictsTheLeastRecentlyUsedEntryOnly() = runBlocking {
        var clock = 1_000L
        val budgetProbe = VideoCaptionCache(temporary.newFolder("probe"), now = { clock })
        val document = document("video-one", "en-manual", cues = cues(4, "probe"))
        budgetProbe.putOriginal(document, 0)
        val unit = budgetProbe.sizeBytes("video-one")
        assertTrue(unit > 0)

        val cache = VideoCaptionCache(temporary.newFolder("bounded"), maxBytes = unit * 2 + unit / 2, now = { clock })
        cache.putOriginal(document.copy(videoId = "video-one"), cache.epoch("video-one"))
        clock += 100
        cache.putOriginal(document.copy(videoId = "video-two"), cache.epoch("video-two"))
        clock += 100
        // Reading is a use: video-one becomes more recently used than video-two.
        assertNotNull(cache.original("video-one", "en-manual"))
        clock += 100
        cache.putOriginal(document.copy(videoId = "video-three"), cache.epoch("video-three"))

        assertNotNull("The recently read entry survives", cache.original("video-one", "en-manual"))
        assertNull("The least recently used entry is evicted", cache.original("video-two", "en-manual"))
        assertNotNull(cache.original("video-three", "en-manual"))
        assertTrue(cache.sizeBytes("video-one") + cache.sizeBytes("video-two") + cache.sizeBytes("video-three") <= unit * 2 + unit / 2)
    }

    @Test fun failedDeletionIsReportedAndLeavesTheReadableCacheIntact() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val document = document("video-one", "en-manual")
        cache.putOriginal(document, 0)
        val parent = requireNotNull(directory.walkTopDown().first { it.isFile }.parentFile)
        assertTrue(parent.setWritable(false, false))
        try {
            val failure = runCatching { cache.clear("video-one", mergedOnly = false) }.exceptionOrNull()
            assertTrue("A failed delete must not report success", failure is java.io.IOException)
            assertEquals(document, cache.original("video-one", "en-manual"))
        } finally {
            parent.setWritable(true)
        }
    }

    @Test fun existingFormattingNeverCrossesTrackOrSourceVersion() = runBlocking {
        val cache = VideoCaptionCache(directory)
        val manual = document("video-one", "en-manual", cues = cues(3, "spoken"))
        val automatic = manual.copy(trackId = "en-auto", automatic = true)
        val joined = listOf(CaptionGroup(0, 1), CaptionGroup(2, 2))
        val separate = manual.cues.map { CaptionGroup(it.id, it.id) }
        cache.putGroups(manual, "older-model", joined, 0)
        cache.putGroups(automatic, "newer-model", separate, 0)

        assertEquals(applyCaptionGroups(manual, joined), cache.latestMerged(manual))
        assertNull(cache.latestMerged(document("video-one", "en-manual", cues = cues(3, "changed"))))
        assertEquals(automatic.cues, cache.latestMerged(automatic))
        assertEquals(separate, cache.groups(automatic, "newer-model"))
    }

    private fun document(
        videoId: String,
        trackId: String,
        sourceHash: String? = null,
        cues: List<CaptionCue> = cues(2, "line")
    ) = CaptionDocument(videoId, trackId, "en", false, sourceHash ?: captionSourceHash(cues), cues)

    private fun cues(count: Int, prefix: String) = (0 until count).map { index ->
        CaptionCue(index, index * 1_000L, index * 1_000L + 900L, "$prefix $index")
    }
}
