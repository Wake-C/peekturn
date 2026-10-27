package app.peekturn.reader.video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.MainActivity
import app.peekturn.reader.PeekturnApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run download online, force-stop the app, disable networking, then run the matching offline phase. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoDownloadLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as PeekturnApp).graph
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun await(stage: String, timeout: Long = 90_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) { println("download-live stage=$stage"); return }
            SystemClock.sleep(100)
        }
        fail("$stage timed out")
    }

    @Test fun realDownloadAndColdOfflinePlayback() {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("downloadLiveId")
        val phase = args.getString("downloadPhase")
        assumeTrue("Set downloadLiveId and downloadPhase", id != null && YouTubeUrls.isVideoId(id) && phase != null)
        when (phase) {
            "download-audio", "download-video", "resume" -> {
                val home = ActivityScenario.launch<MainActivity>(MainActivity.videoIntent(context))
                try {
                    val item = runBlocking {
                        graph.store.findVideoBySource(id!!) ?: graph.store.upsertVideo(graph.videoSource.resolve(id).metadata)
                    }
                    main { graph.videoDownloads.initialize() }
                    await("download-index-ready") { main { graph.videoDownloads.manager.isInitialized } }
                    val audioOnly = phase != "download-video"
                    runBlocking { graph.store.selectVideoPresentation(item.id, audioOnly, readMode = audioOnly) }
                    if (phase != "resume") main { graph.videoDownloads.download(item, audioOnly, 240) }
                    await("download-started") {
                        graph.videoDownloads.states.value[item.id]?.phase in listOf(VideoDownloadPhase.Downloading, VideoDownloadPhase.Complete)
                    }
                    home.moveToState(Lifecycle.State.CREATED)
                    await("download-complete-in-background", 480_000) {
                        val state = graph.videoDownloads.states.value[item.id]
                        assertNotEquals("Download failed; inspect bounded developer log", VideoDownloadPhase.Failed, state?.phase)
                        state?.phase == VideoDownloadPhase.Complete && state.audioOnly == audioOnly
                    }
                    val state = graph.videoDownloads.states.value[item.id]!!
                    assertTrue("Real media bytes must be saved", state.bytes > 0)
                    assertTrue("This test target must have saved captions", state.captionsSaved)
                    println("download-live complete audioOnly=$audioOnly bytes=${state.bytes}")
                    val download = graph.videoDownloads.manager.downloadIndex.getDownload("video-${item.id}")!!
                    assertEquals("peekturn-download", download.request.uri.scheme)
                    assertNull(download.request.uri.query)
                } finally { home.close() }
            }
            "offline-audio", "offline-video", "stream-instead" -> {
                val item = runBlocking { graph.store.findVideoBySource(id!!) }!!
                if (phase == "offline-audio") runBlocking { graph.store.selectVideoPresentation(item.id, true, readMode = true) }
                if (phase == "offline-video") runBlocking { graph.store.selectVideoPresentation(item.id, false, readMode = false) }
                // This must finish from the persisted download, before any Activity/service/player exists.
                runBlocking { graph.videos.clearCaptions(item.videoId, mergedOnly = false) }
                val session = runBlocking { graph.videos.open(item.id) }
                assertTrue(session.resolved.playback.videoUrl.startsWith("peekturn-offline://"))
                assertTrue(session.captions!!.cues.isNotEmpty())
                val connected = CountDownLatch(1)
                var model: VideoViewModel? = null
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        model = (binder as VideoPlaybackService.LocalBinder).service.model
                        connected.countDown()
                    }
                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                val activity = ActivityScenario.launch<VideoActivity>(VideoActivity.intent(context, item.id, resumeOnLaunch = true))
                val bound = context.bindService(Intent(context, VideoPlaybackService::class.java).setAction(VideoPlaybackService.ACTION_LOCAL),
                    connection, Context.BIND_AUTO_CREATE)
                try {
                    assertTrue(connected.await(15, TimeUnit.SECONDS))
                    val vm = requireNotNull(model)
                    fun ready(stage: String) = await(stage) {
                        main {
                            assertFalse("Offline media failed: ${vm.state.value.phase}", vm.state.value.phase is VideoPhase.Failed)
                            vm.state.value.phase is VideoPhase.Ready && vm.player.playbackState == Player.STATE_READY && !vm.state.value.modeSaving
                        }
                    }
                    ready("offline-ready")
                    if (phase == "stream-instead") {
                        main { vm.streamInstead() }
                        ready("online-stream-ready")
                        main { assertFalse(vm.state.value.offlinePlayback) }
                        assertNotNull(runBlocking { graph.videoDownloads.session(item) })
                    }
                    main {
                        assertFalse(vm.player.playWhenReady)
                        vm.setSpeed(1.25f)
                        vm.seekTo(60_000)
                        vm.togglePlay()
                    }
                    await("offline-playing") { main { vm.player.isPlaying } }
                    val start = main { vm.player.currentPosition }
                    SystemClock.sleep(11_000)
                    val end = main { vm.player.currentPosition }
                    assertTrue("Offline playback clock must advance", end > start + 10_000)
                    main { vm.player.pause(); vm.onBackground() }
                    await("offline-progress-saved") { runBlocking { graph.store.findVideo(item.id)!!.positionMs } > 70_000 }
                    if (phase == "offline-video") {
                        main { assertTrue(vm.state.value.qualities.isNotEmpty()); vm.setReadMode(true) }
                        ready("offline-read")
                        main {
                            assertTrue(vm.state.value.readMode)
                            assertFalse(vm.player.playWhenReady)
                            assertEquals(1.25f, vm.player.playbackParameters.speed)
                            assertEquals(end.toDouble(), vm.player.currentPosition.toDouble(), 1_000.0)
                            vm.setReadMode(false)
                        }
                        ready("offline-watch-restored")
                        main { assertFalse(vm.state.value.audioOnly) }
                    } else if (phase == "offline-audio") main { assertTrue(vm.state.value.audioOnly); assertTrue(vm.state.value.readMode) }
                    val checkpoint = runBlocking { graph.store.findVideo(item.id)!!.positionMs }
                    val farPosition = main { (vm.player.duration - 20_000).coerceAtLeast(0) }
                    main { vm.seekTo(farPosition); vm.togglePlay() }
                    await("offline-end-seek-playing") { main { vm.player.isPlaying && vm.player.currentPosition >= farPosition } }
                    val farStart = main { vm.player.currentPosition }
                    SystemClock.sleep(1_500)
                    main {
                        assertTrue("Downloaded tail must really play", vm.player.isPlaying && vm.player.currentPosition > farStart + 1_000)
                        vm.player.pause()
                        vm.onBackground()
                    }
                    SystemClock.sleep(300)
                    assertEquals("Unqualified tail seek must not overwrite progress", checkpoint,
                        runBlocking { graph.store.findVideo(item.id)!!.positionMs })
                    println("download-live offline phase=$phase clockAdvanceMs=${end - start}; tailSeekMs=$farPosition")
                } finally {
                    model?.let { vm -> main { vm.player.pause() } }
                    if (bound) context.unbindService(connection)
                    activity.close()
                }
            }
            "remove", "cancel", "cancel-active", "interrupt" -> {
                val item = runBlocking { graph.store.findVideoBySource(id!!) }!!
                val home = ActivityScenario.launch<MainActivity>(MainActivity.videoIntent(context))
                try {
                    main { graph.videoDownloads.initialize() }
                    await("remove-index-ready") { main { graph.videoDownloads.manager.isInitialized } }
                    main { graph.videoDownloads.remove(item.id) }
                    await("removed") { main { graph.videoDownloads.manager.downloadIndex.getDownload("video-${item.id}") == null } }
                    assertNull(runBlocking { graph.videoDownloads.session(item) })
                    assertEquals(item.positionMs, runBlocking { graph.store.findVideo(item.id)!!.positionMs })
                    if (phase in listOf("cancel", "cancel-active", "interrupt")) {
                        if (phase == "interrupt") runBlocking { graph.store.selectVideoPresentation(item.id, true, readMode = true) }
                        main { graph.videoDownloads.download(item, true, 240) }
                        await("cancellable-job-ready") {
                            val download = main { graph.videoDownloads.manager.downloadIndex.getDownload("video-${item.id}") }
                            assertNotEquals(androidx.media3.exoplayer.offline.Download.STATE_FAILED, download?.state)
                            if (phase == "cancel") download != null && download.state == androidx.media3.exoplayer.offline.Download.STATE_QUEUED
                            else download != null && download.bytesDownloaded > 0 && download.state == androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING
                        }
                        if (phase == "interrupt") {
                            println("download-live ready for process interruption; partial bytes saved")
                        } else {
                            main { graph.videoDownloads.remove(item.id) }
                            await("queued-job-cancelled") { main { graph.videoDownloads.manager.downloadIndex.getDownload("video-${item.id}") == null } }
                            assertEquals(item.positionMs, runBlocking { graph.store.findVideo(item.id)!!.positionMs })
                        }
                    }
                } finally { home.close() }
            }
            else -> fail("Unknown phase")
        }
    }
}
