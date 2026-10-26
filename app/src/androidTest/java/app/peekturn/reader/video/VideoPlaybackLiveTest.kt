package app.peekturn.reader.video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.peekturn.reader.R
import androidx.compose.ui.graphics.toArgb
import app.peekturn.reader.ui.peekturnColorScheme
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.model.ReaderPreferences
import app.peekturn.reader.reader.withTypography
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicit opt-in device check, not a metadata-only source probe. Uses real extraction, captions,
 * independent audio, Media3 preparation and advancing playback. Never logs URLs or source text.
 * Run with instrumentation argument videoLiveId=<public YouTube id with English captions>.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlaybackLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun <T> onMain(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun hasDescription(label: String): Boolean {
        fun contains(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (node.contentDescription?.toString() == label) return true
            return (0 until node.childCount).any { index -> node.getChild(index)?.let(::contains) == true }
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::contains) == true
    }

    private fun capture(name: String, expectedPaper: Int? = null) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (true) {
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            // The outer reading margin proves the new theme reached a rendered frame, not only storage.
            val settled = expectedPaper == null || bitmap.getPixel(1, bitmap.height / 2) == expectedPaper
            if (settled) {
                val file = java.io.File(context.getExternalFilesDir("ui-checks"), "$name.png")
                file.outputStream().use { output -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output) }
                bitmap.recycle()
                return
            }
            bitmap.recycle()
            check(SystemClock.elapsedRealtime() < deadline) { "Reading appearance did not reach the rendered frame" }
            SystemClock.sleep(50)
        }
    }

    @Test fun realVideoReachesPlaybackAndReadWatchSwitchesKeepTheSession() {
        val id = InstrumentationRegistry.getArguments().getString("videoLiveId")
        assumeTrue("Set videoLiveId to opt into real network and playback", id != null && YouTubeUrls.isVideoId(id))
        val connected = CountDownLatch(1)
        var model: VideoViewModel? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                model = (binder as VideoPlaybackService.LocalBinder).service.model
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        var bound = false
        var restoreAppearance: Pair<ReaderPreferences, String>? = null
        val started = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launch<VideoActivity>(VideoActivity.importIntent(context, "https://www.youtube.com/watch?v=$id"))
        try {
            bound = context.bindService(Intent(context, VideoPlaybackService::class.java)
                .setAction(VideoPlaybackService.ACTION_LOCAL), connection, Context.BIND_AUTO_CREATE)
            assertTrue("Local playback service did not bind", connected.await(15, TimeUnit.SECONDS))
            val vm = requireNotNull(model)
            fun awaitStage(stage: String, timeout: Long = 60_000, needsCaptions: Boolean = false, condition: (VideoPlaybackState) -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + timeout
                while (SystemClock.elapsedRealtime() < deadline) {
                    val state = onMain { vm.state.value }
                    val failed = state.phase as? VideoPhase.Failed
                    assertNull("$stage: video failure=${failed?.kind}; elapsedMs=${SystemClock.elapsedRealtime() - started}", failed)
                    if (needsCaptions && state.captionStatus in listOf(VideoCaptionStatus.Failed, VideoCaptionStatus.Absent, VideoCaptionStatus.NoEnglish)) {
                        fail("$stage: captions=${state.captionStatus}; category=${state.captionFailure}")
                    }
                    if (condition(state)) {
                        println("live-video stage=$stage elapsedMs=${SystemClock.elapsedRealtime() - started}")
                        return
                    }
                    SystemClock.sleep(100)
                }
                val state = onMain { vm.state.value }
                fail("$stage timed out; phase=${state.phase.javaClass.simpleName}; captions=${state.captionStatus}")
            }
            awaitStage("resolved", 180_000) { it.phase is VideoPhase.Ready }
            awaitStage("media-ready") { onMain { vm.player.playbackState == Player.STATE_READY } }
            onMain { vm.setReadMode(false) }
            awaitStage("watch") { !it.audioOnly && !it.readMode && !it.modeSaving && onMain { vm.player.playbackState == Player.STATE_READY } }
            onMain {
                vm.seekTo(5_000)
                vm.setSpeed(1.25f)
                if (!vm.player.playWhenReady) vm.togglePlay()
            }
            val watchStart = onMain { vm.player.currentPosition }
            awaitStage("watch-clock-advanced", 30_000) {
                onMain { vm.player.isPlaying && vm.player.currentPosition > watchStart + 1_000 }
            }
            awaitStage("english-captions", needsCaptions = true) { it.captionStatus == VideoCaptionStatus.Ready && it.displayedCues.isNotEmpty() }
            assertTrue("Source has no independent audio for Read", onMain { vm.state.value.audioAvailable })
            onMain { vm.player.pause() }
            capture("live-video-watch")
            val paused = onMain { vm.player.currentPosition }
            onMain { vm.setReadMode(true) }
            awaitStage("read-ready") { it.readMode && !it.modeSaving && onMain { vm.player.playbackState == Player.STATE_READY } }
            onMain { assertFalse(vm.state.value.toolsVisible) }
            val immersive = (context.applicationContext as PeekturnApp).graph.store.settings.value.reading.immersive
            awaitStage("read-system-bars", timeout = 5_000) {
                var matches = false
                scenario.onActivity { activity ->
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    matches = insets != null && insets.isVisible(WindowInsetsCompat.Type.statusBars()) == !immersive &&
                        insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == !immersive
                }
                matches
            }
            var watchLabel = ""
            scenario.onActivity { watchLabel = it.getString(R.string.video_ui_watch_video) }
            onMain { vm.toggleTools() }
            awaitStage("read-tools-shown", timeout = 5_000) { it.toolsVisible && hasDescription(watchLabel) }
            capture("live-video-read-tools")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            awaitStage("read-tools-hidden", timeout = 5_000) { !it.toolsVisible && !hasDescription(watchLabel) }
            if (InstrumentationRegistry.getArguments().getString("checkReadingAppearance") == "true") {
                val store = (context.applicationContext as PeekturnApp).graph.store
                val original = store.settings.value
                restoreAppearance = original.reading to original.appTheme
                val reading = original.reading.copy(fontFamily = "literata", fontSize = 120.0,
                    lineHeight = 1.6, paragraphSpacing = 0.7, letterSpacing = 0.0, pageMargins = 1.0)
                assertTrue(runBlocking { vm.saveReadingAppearance(reading, "warm") })
                assertEquals(reading, store.settings.value.reading)
                assertEquals("warm", store.settings.value.appTheme)
                capture("live-video-read-shared-literata", peekturnColorScheme("warm").background.toArgb())
                onMain {
                    assertFalse(vm.player.playWhenReady)
                    assertEquals(paused.toDouble(), vm.player.currentPosition.toDouble(), 1_000.0)
                }
            }
            onMain {
                assertTrue(vm.state.value.audioOnly)
                assertFalse(vm.player.playWhenReady)
                assertEquals(paused.toDouble(), vm.player.currentPosition.toDouble(), 1_000.0)
                assertEquals(1.25f, vm.player.playbackParameters.speed)
                vm.togglePlay()
            }
            capture("live-video-read")
            val readStart = onMain { vm.player.currentPosition }
            awaitStage("read-clock-advanced", 30_000) {
                onMain { vm.player.isPlaying && vm.player.currentPosition > readStart + 1_000 }
            }
            onMain { vm.player.pause(); vm.setReadMode(false) }
            awaitStage("watch-restored") { !it.readMode && !it.audioOnly && !it.modeSaving && onMain { vm.player.playbackState == Player.STATE_READY } }
            onMain { assertFalse(vm.player.playWhenReady) }
            awaitStage("watch-system-bars-restored", timeout = 5_000) {
                var visible = false
                scenario.onActivity { activity ->
                    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    visible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) == true &&
                        insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                }
                visible
            }
            // A warm Activity stop/start must not resolve the same ready session again.
            val player = onMain { vm.player }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            onMain {
                assertSame(player, vm.player)
                assertTrue(vm.state.value.phase is VideoPhase.Ready)
                assertFalse(vm.player.playWhenReady)
            }
        } finally {
            restoreAppearance?.let { (reading, theme) ->
                runBlocking {
                    (context.applicationContext as PeekturnApp).graph.store.updateSettings {
                        it.withTypography(reading).copy(appTheme = theme)
                    }
                }
            }
            model?.let { vm -> onMain { vm.player.pause() } }
            if (bound) context.unbindService(connection)
            scenario.close()
        }
    }
}
