package app.peekturn.reader.video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.PeekturnApp
import app.peekturn.reader.data.LastContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public Activity/service/player and saved-library regression, opt-in with videoProgressId. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoProgressLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = (context.applicationContext as PeekturnApp).graph.store
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    @Test fun openingBackAndShortPlaybackKeepResumeUntilTenSecondsActuallyPlay() {
        val id = InstrumentationRegistry.getArguments().getString("videoProgressId")
        assumeTrue("Set videoProgressId for the live progress regression", id != null && YouTubeUrls.isVideoId(id))
        val video = runBlocking {
            val existing = store.findVideoBySource(id!!) ?: store.upsertVideo(VideoMetadata(id,
                "Live progress test", "", 0, null))
            store.saveVideoPosition(existing.id, 120_000)
            store.findVideo(existing.id)!!
        }
        val previousSelection = runBlocking {
            store.upsertVideo(VideoMetadata("tstPrgOld01", "Progress test fixture", "", 60_000, null)).also {
                store.markVideoOpened(it.id)
            }
        }
        val connected = CountDownLatch(1)
        var vm: VideoViewModel? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                vm = (binder as VideoPlaybackService.LocalBinder).service.model
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        var scenario: ActivityScenario<VideoActivity>? = ActivityScenario.launch(VideoActivity.intent(context, video.id, resumeOnLaunch = true))
        var bound = false
        try {
            bound = context.bindService(Intent(context, VideoPlaybackService::class.java).setAction(VideoPlaybackService.ACTION_LOCAL),
                connection, Context.BIND_AUTO_CREATE)
            assertTrue(connected.await(15, TimeUnit.SECONDS))
            val model = requireNotNull(vm)
            assertTrue("Test must exercise the actual opening state", main { model.state.value.phase is VideoPhase.Loading })
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario!!.close()
            scenario = null
            SystemClock.sleep(500)
            assertEquals("Back during Opening must not overwrite the stored resume point", 120_000L,
                runBlocking { store.findVideo(video.id)!!.positionMs })
            assertEquals("Selecting a video remembers its identity even before media is ready",
                LastContent.Video(video.id), store.lastContent())
            scenario = ActivityScenario.launch(VideoActivity.intent(context, video.id, resumeOnLaunch = true))
            fun await(stage: String, timeout: Long = 90_000, condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + timeout
                while (SystemClock.elapsedRealtime() < deadline) {
                    val failed = main { model.state.value.phase as? VideoPhase.Failed }
                    assertNull("$stage: failure=${failed?.kind}", failed)
                    if (main(condition)) return
                    SystemClock.sleep(100)
                }
                fail("$stage timed out")
            }
            await("ready paused", 180_000) { model.state.value.phase is VideoPhase.Ready && model.player.playbackState == Player.STATE_READY }
            assertFalse(main { model.player.playWhenReady })
            assertEquals(120_000L, runBlocking { store.findVideo(video.id)!!.positionMs })
            main { model.togglePlay() }
            await("actual playback") { model.player.isPlaying }
            // Wall time only advances while isPlaying; buffering and pause do not count.
            fun playFor(milliseconds: Long) {
                var played = 0L
                var last = SystemClock.elapsedRealtime()
                var wasPlaying = main { model.player.isPlaying }
                val deadline = last + 60_000
                while (played < milliseconds && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(50)
                    val now = SystemClock.elapsedRealtime()
                    val playing = main { model.player.isPlaying }
                    if (playing && wasPlaying) played += now - last
                    wasPlaying = playing
                    last = now
                }
                assertTrue("Target must actually play, not only request play", played >= milliseconds)
            }
            playFor(5_000)
            main { model.togglePlay(); model.onBackground() }
            SystemClock.sleep(500)
            assertEquals("Five seconds is not enough to replace resume progress", 120_000L,
                runBlocking { store.findVideo(video.id)!!.positionMs })
            println("progress-live opening-back and five-second pause preserved 120000ms")
            main { model.onForeground(); model.togglePlay() }
            playFor(6_000)
            main { model.togglePlay(); model.onBackground() }
            val pausedAt = main { model.player.currentPosition }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var saved = 120_000L
            while (SystemClock.elapsedRealtime() < deadline) {
                saved = runBlocking { store.findVideo(video.id)!!.positionMs }
                if (saved > 129_000) break
                SystemClock.sleep(50)
            }
            assertTrue("After ten seconds actual play, resume must advance; saved=$saved", saved > 129_000)
            assertEquals(pausedAt.toDouble(), saved.toDouble(), 500.0)
            println("progress-live ten-second playback qualified; saved=$saved paused=$pausedAt")
            main { model.seekTo(0); model.onBackground() }
            SystemClock.sleep(500)
            assertEquals("A seek target is not watched progress", saved,
                runBlocking { store.findVideo(video.id)!!.positionMs })
            println("progress-live unplayed seek kept qualified checkpoint $saved")
            // Force a new open while an older ready player still has a timeline. Stop/clear callbacks
            // and Back during the next Loading state must not write its temporary zero position.
            main { model.start(video.id, null, force = true, autoPlay = false) }
            assertTrue(main { model.state.value.phase is VideoPhase.Loading })
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario!!.close()
            scenario = null
            SystemClock.sleep(500)
            assertEquals(saved, runBlocking { store.findVideo(video.id)!!.positionMs })
            println("progress-live reopen/back preserved qualified checkpoint $saved")
        } finally {
            vm?.let { model -> main { model.player.pause() } }
            scenario?.close()
            if (bound) context.unbindService(connection)
            runBlocking { store.deleteVideo(previousSelection.id) }
        }
    }
}
