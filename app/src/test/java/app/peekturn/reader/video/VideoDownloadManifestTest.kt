package app.peekturn.reader.video

import android.app.Application
import android.util.AtomicFile
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35])
class VideoDownloadManifestTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oldAudio = VideoDownloadStoredPart("session-old-audio", "audio/mp4")
    private val oldVideo = VideoDownloadStoredPart("session-old-video", "video/mp4")
    private val newAudio = VideoDownloadStoredPart("session-new-audio", "audio/mp4")
    private val newVideo = VideoDownloadStoredPart("session-new-video", "video/mp4")
    private val newCompanion = VideoDownloadStoredPart("session-new-companion", "audio/mp4")

    private fun manifest(
        audio: VideoDownloadStoredPart = oldAudio,
        video: VideoDownloadStoredPart? = null,
        companion: VideoDownloadStoredPart? = null,
        height: Int = 0,
        fallback: VideoDownloadSnapshot? = null,
        retained: List<String> = emptyList()
    ) = VideoDownloadManifest("dQw4w9WgXcQ", audio, video, companion, height, null,
        (retained + listOfNotNull(audio, video, companion).map { it.key }).distinct(), fallback)

    private fun interruptedWrite(base: File) {
        // Closing without finishWrite/failWrite models a process death after the write began.
        AtomicFile(base).startWrite().use { it.write("{incomplete".toByteArray()) }
    }

    @Test fun interruptedReplacementRecoversTheLastCompleteManifest() {
        val directory = temporary.newFolder()
        val store = VideoDownloadManifestStore(directory)
        val saved = manifest()
        store.write(1, saved)
        interruptedWrite(File(directory, "entry-1.json"))

        // Recreate the owner, as on a cold process restart. On SDK 28 this recovers .bak;
        // on current Android it discards .new and retains the committed base file.
        assertEquals(saved, VideoDownloadManifestStore(directory).read(1))
        assertFalse(File(directory, "entry-1.json.bak").exists())
        assertFalse(File(directory, "entry-1.json.new").exists())
    }

    @Test fun manifestsFromBeforeFallbackSupportRemainReadable() {
        val directory = temporary.newFolder()
        File(directory, "entry-1.json").writeText(
            """{"videoId":"dQw4w9WgXcQ","audio":{"key":"session-old-audio","mime":"audio/mp4"},"height":0,"keys":["session-old-audio"]}"""
        )
        val restored = requireNotNull(VideoDownloadManifestStore(directory).read(1))
        assertNull(restored.fallback)
        assertNull(restored.audioFallback)
        assertEquals(oldAudio, restored.playableSnapshot { it == oldAudio }?.audio)
    }

    @Test fun failedVideoUpgradeKeepsCompletedAudioAcrossNewSessionKeysAndRestart() {
        val directory = temporary.newFolder()
        val store = VideoDownloadManifestStore(directory)
        val saved = manifest()
        store.write(1, saved)
        val old = requireNotNull(store.read(1))
        val replacement = manifest(newAudio, newVideo, newCompanion, 1080,
            fallback = old.playableSnapshot { it == oldAudio }, retained = old.keys)
        store.write(1, replacement)

        // The new transfer failed before any part completed. The old key is still fully cached.
        val restored = requireNotNull(VideoDownloadManifestStore(directory).read(1))
        val playable = requireNotNull(restored.playableSnapshot { it == oldAudio })
        assertEquals(oldAudio, playable.audio)
        assertNull(playable.video)
        assertEquals(oldAudio.key, playable.audio!!.source().videoCacheKey)
        assertEquals(setOf(oldAudio.key, newAudio.key, newVideo.key, newCompanion.key), restored.keys.toSet())
    }

    @Test fun incompleteNewVideoCannotBorrowOldAudioOrClaimTheNewQuality() {
        val saved = manifest(oldAudio, oldVideo, oldAudio, 720)
        val complete = setOf(oldAudio, oldVideo, newAudio, newVideo)
        val replacement = manifest(newAudio, newVideo, newCompanion, 1080,
            fallback = saved.playableSnapshot { it in complete }, retained = saved.keys)

        val playable = requireNotNull(replacement.playableSnapshot { it in complete })
        assertEquals(720, playable.height)
        assertEquals(oldVideo, playable.video)
        assertEquals(oldAudio, playable.videoAudio)
        assertEquals(oldAudio, playable.audio)
    }

    @Test fun aCompleteNewBundleReplacesTheOldFallbackAsOneCoherentUnit() {
        val saved = manifest(oldAudio, oldVideo, oldAudio, 720)
        val complete = setOf(oldAudio, oldVideo, newAudio, newVideo, newCompanion)
        val replacement = manifest(newAudio, newVideo, newCompanion, 1080,
            fallback = saved.playableSnapshot { it in complete }, retained = saved.keys)

        val playable = requireNotNull(replacement.playableSnapshot { it in complete })
        assertEquals(1080, playable.height)
        assertEquals(newVideo, playable.video)
        assertEquals(newCompanion, playable.videoAudio)
        assertEquals(newAudio, playable.audio)
    }

    @Test fun readCanUseIndependentSavedAudioWhileExplicitWatchSelectsTheCompletePictureBundle() {
        val complete = setOf(oldAudio, newVideo, newCompanion)
        val replacement = manifest(newAudio, newVideo, newCompanion, 1080,
            fallback = manifest().playableSnapshot { it in complete }, retained = listOf(oldAudio.key))

        // A complete picture+companion is not evidence that the independent Read track exists.
        val read = requireNotNull(replacement.playableSnapshot(audioRequired = true) { it in complete })
        assertEquals(oldAudio, read.audio)
        assertNull(read.video)

        // requestWatchSource supplies explicit Watch intent even if the saved item was in Read.
        val watch = requireNotNull(replacement.playableSnapshot(audioRequired = false) { it in complete })
        assertEquals(newVideo, watch.video)
        assertEquals(newCompanion, watch.videoAudio)
        assertEquals(1080, watch.height)
        assertNull(watch.audio)
        assertNull(replacement.copy(fallback = null).playableSnapshot(audioRequired = true) { it in complete })
    }

    @Test fun repeatedFailedResolutionsKeepOneFlatFallbackAndNeverAcceptPartialOldMedia() {
        val saved = manifest(oldAudio, oldVideo, oldAudio, 720)
        val complete = setOf(oldAudio, oldVideo)
        var pending = saved
        repeat(4) {
            pending = manifest(newAudio, newVideo, newCompanion, 1080, retained = pending.keys)
                .preservingCompleted(pending) { it in complete }
        }
        assertEquals(saved.playableSnapshot { true }, pending.fallback)
        assertNull("One coherent fallback must not duplicate its audio and captions", pending.audioFallback)
        assertEquals(oldVideo, pending.playableSnapshot { it in complete }?.video)
        assertNull(pending.playableSnapshot { false })
    }

    @Test fun repeatedRefreshesRetainIndependentReadAudioAndItsCaptionsAlongsideACompletePicture() {
        val directory = temporary.newFolder()
        val complete = setOf(oldAudio, newVideo, newCompanion)
        val oldCaptions = CaptionDocument("dQw4w9WgXcQ", "en", "en", false, "old",
            listOf(CaptionCue(0, 0, 1_000, "Original saved audio.")))
        val pictureCaptions = oldCaptions.copy(sourceHash = "new",
            cues = listOf(CaptionCue(0, 0, 2_000, "Current saved picture.")))
        val savedAudio = manifest().copy(captions = oldCaptions)
        var pending = manifest(newAudio, newVideo, newCompanion, 1080, retained = savedAudio.keys)
            .copy(captions = pictureCaptions).preservingCompleted(savedAudio) { it in complete }

        repeat(4) {
            // A later resolution must capture both separately usable modes, not only Watch.
            pending = manifest(newAudio, newVideo, newCompanion, 1080, retained = pending.keys)
                .copy(captions = pictureCaptions).preservingCompleted(pending) { it in complete }
            VideoDownloadManifestStore(directory).write(1, pending)
            pending = requireNotNull(VideoDownloadManifestStore(directory).read(1))

            val read = requireNotNull(pending.playableSnapshot(audioRequired = true) { it in complete })
            assertEquals(oldAudio, read.audio)
            assertEquals(oldCaptions, read.captions)
            assertNull(read.video)
            val watch = requireNotNull(pending.playableSnapshot { it in complete })
            assertEquals(newVideo, watch.video)
            assertEquals(newCompanion, watch.videoAudio)
            assertEquals(pictureCaptions, watch.captions)
            assertEquals(1080, watch.height)
            assertEquals(VideoDownloadAudioSnapshot(oldAudio, oldCaptions), pending.audioFallback)
            assertTrue(pending.keys.contains(oldAudio.key))
        }

        val removed = mutableListOf<String>()
        VideoDownloadManifestStore(directory).remove(1) { removed += it }
        assertEquals(pending.keys.toSet(), removed.toSet())
    }

    @Test fun removeRecoversTheManifestAndDeletesAllRetainedKeysAndAtomicFiles() {
        val directory = temporary.newFolder()
        val store = VideoDownloadManifestStore(directory)
        val saved = manifest(newAudio, newVideo, newCompanion, 1080,
            fallback = manifest().playableSnapshot { true }, retained = listOf(oldAudio.key))
        store.write(1, saved)
        interruptedWrite(File(directory, "entry-1.json"))
        val removed = mutableListOf<String>()

        store.remove(1) { removed += it }

        assertEquals(saved.keys.toSet(), removed.toSet())
        assertNull(store.read(1))
        assertFalse(File(directory, "entry-1.json").exists())
        assertFalse(File(directory, "entry-1.json.bak").exists())
        assertFalse(File(directory, "entry-1.json.new").exists())
    }
}
