package app.peekturn.reader.video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requires a real completed audio download. Test the toolbar action, not a substitute Settings route. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoReadToWatchLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as PeekturnApp).graph
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { search(it) }?.let { return it }
            return null
        }
        val labeled = instrumentation.uiAutomation.rootInActiveWindow?.let(::search) ?: return null
        var action: AccessibilityNodeInfo? = labeled
        while (action != null) {
            if (action.className?.toString() == "android.widget.Button" || action.isClickable) return action
            action = action.parent
        }
        return labeled
    }
    private fun await(stage: String, timeout: Long = 90_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail("$stage timed out")
    }

    @Test fun audioDownloadDoesNotDisableWatchAndSwitchesWithoutDeletingTheDownload() {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("readToWatchId")
        assumeTrue(id != null && YouTubeUrls.isVideoId(id))
        val offline = args.getString("expectOfflineFailure") == "true"
        val item = requireNotNull(runBlocking { graph.store.findVideoBySource(id!!) }) {
            "Live fixture missing: run VideoDownloadLiveTest download-audio first"
        }
        runBlocking {
            graph.store.selectVideoPresentation(item.id, true, readMode = true)
            graph.store.saveVideoPosition(item.id, 180_000)
        }
        val downloaded = requireNotNull(runBlocking { graph.videoDownloads.session(item) }) {
            "Live fixture needs a completed audio download"
        }
        assertTrue("Fixture must be audio-only, not an already downloaded picture", downloaded.resolved.qualities.isEmpty())
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
            await("downloaded Read ready") { main { vm.state.value.readMode && vm.player.playbackState == Player.STATE_READY } }
            main {
                vm.seekTo(60_000)
                vm.setSpeed(1.25f)
                if (offline) vm.togglePlay()
                if (!vm.state.value.toolsVisible) vm.toggleTools()
            }
            fun playFor(durationMs: Long) {
                var played = 0L
                val deadline = SystemClock.elapsedRealtime() + 45_000
                while (played < durationMs && SystemClock.elapsedRealtime() < deadline) {
                    val start = SystemClock.elapsedRealtime()
                    val playing = main { vm.player.isPlaying }
                    SystemClock.sleep(50)
                    if (playing && main { vm.player.isPlaying }) played += SystemClock.elapsedRealtime() - start
                }
                assertTrue("The real player must actually play", played >= durationMs)
            }
            if (!offline) {
                main { vm.togglePlay() }
                await("Read really plays") { main { vm.player.isPlaying } }
                playFor(4_000)
                main { vm.player.pause() }
                assertEquals("Short Read playback must not replace resume", 180_000L,
                    runBlocking { graph.store.findVideo(item.id)!!.positionMs })
            }
            val positionBeforeSwitch = main { vm.player.currentPosition }
            var label = ""
            activity.onActivity { label = it.getString(R.string.video_ui_watch_video) }
            await("Watch action visible") { find(label) != null }
            val watch = requireNotNull(find(label))
            assertTrue("A saved audio stream must not disable the return-to-video action", watch.isEnabled)
            assertTrue(watch.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.waitForIdleSync()
            if (offline) {
                // Do not require a particular network exception: airplane-mode failures vary by resolver.
                await("failed switch finishes", 120_000) { main { !vm.state.value.modeSaving && vm.state.value.watchSwitchFailure != null } }
                main {
                    assertTrue(vm.state.value.readMode)
                    assertTrue(vm.state.value.audioOnly)
                    assertTrue(vm.state.value.phase is VideoPhase.Ready)
                    assertTrue("An unavailable picture must not pause saved audio", vm.player.playWhenReady)
                    assertTrue(vm.player.currentPosition >= 60_000)
                }
                await("saved audio still plays") { main { vm.player.isPlaying } }
                val start = main { vm.player.currentPosition }
                SystemClock.sleep(1_500)
                main { assertTrue(vm.player.currentPosition > start + 1_000) }
            } else {
                await("Watch ready", 180_000) { main {
                    assertFalse("Video preparation failed", vm.state.value.phase is VideoPhase.Failed)
                    !vm.state.value.readMode && !vm.state.value.audioOnly && !vm.state.value.modeSaving && vm.player.playbackState == Player.STATE_READY
                } }
                main {
                    assertEquals(positionBeforeSwitch.toDouble(), vm.player.currentPosition.toDouble(), 500.0)
                    assertEquals(1.25f, vm.player.playbackParameters.speed)
                    assertFalse(vm.player.playWhenReady)
                    assertTrue(vm.state.value.qualities.isNotEmpty())
                    vm.togglePlay()
                }
                await("video really plays") { main { vm.player.isPlaying } }
                val start = main { vm.player.currentPosition }
                playFor(7_000)
                main {
                    assertTrue(vm.player.currentPosition > start + 7_000)
                    vm.player.pause()
                    vm.onBackground()
                }
                val paused = main { vm.player.currentPosition }
                await("ten seconds across Read and Watch persist progress", timeout = 5_000) {
                    val saved = runBlocking { graph.store.findVideo(item.id)!!.positionMs }
                    kotlin.math.abs(saved - paused) < 500
                }
            }
            assertNotNull("Switching presentation must retain the audio download", runBlocking { graph.videoDownloads.session(item) })
            println("read-to-watch actual-toolbar offline=$offline passed")
        } finally {
            model?.let { vm -> main { vm.player.pause() } }
            activity.close()
            if (bound) context.unbindService(connection)
            runBlocking { graph.store.selectVideoPresentation(item.id, true, readMode = true) }
        }
    }
}
