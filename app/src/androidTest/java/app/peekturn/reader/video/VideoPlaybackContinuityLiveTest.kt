package app.peekturn.reader.video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import app.peekturn.reader.PeekturnApp
import com.google.gson.Gson
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit sustained-playback probe. No URLs, request headers, cache identities or passages leave the player. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlaybackContinuityLiveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun <T> main(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST") return value as T
    }

    @Test fun sustainedPlaybackKeepsAnAdvancingClockWithoutRebuffering() {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("videoContinuityId")
        assumeTrue("Set videoContinuityId to opt into sustained real playback", id != null && YouTubeUrls.isVideoId(id))
        val mode = args.getString("continuityMode") ?: "watch"
        require(mode in listOf("watch", "audio"))
        val seconds = (args.getString("continuitySeconds")?.toIntOrNull() ?: 180).coerceIn(30, 600)
        val requestedHeight = args.getString("continuityHeight")?.toIntOrNull() ?: 240
        val startMs = (args.getString("continuityStartMs")?.toLongOrNull() ?: 120_000).coerceAtLeast(0)
        val store = (context.applicationContext as PeekturnApp).graph.store
        val originalHeight = store.settings.value.videoQualityHeight
        val ready = CountDownLatch(1)
        var vm: VideoViewModel? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                vm = (binder as VideoPlaybackService.LocalBinder).service.model
                ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        var bound = false
        var measuring = false
        var stallStarted: Long? = null
        val stalls = mutableListOf<Long>()
        val samples = mutableListOf<Map<String, Long>>()
        val httpErrors = mutableListOf<Int>()
        var droppedFrames = 0
        var bandwidth: Long? = null
        var loaderBytes = 0L
        var elapsed = 0L
        var advanced = 0L
        var selectedHeight: Int? = null
        var videoHeight: Int? = null
        var audioBitrate: Int? = null
        var failure: String? = null
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!measuring) return
                if (playbackState == Player.STATE_BUFFERING && stallStarted == null) stallStarted = SystemClock.elapsedRealtime()
                else if (playbackState != Player.STATE_BUFFERING) {
                    stallStarted?.let { stalls += SystemClock.elapsedRealtime() - it }
                    stallStarted = null
                }
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) {
                if (measuring) droppedFrames += dropped
            }
            override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int,
                totalBytesLoaded: Long, bitrateEstimate: Long) {
                if (measuring) bandwidth = bitrateEstimate
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                if (measuring) loaderBytes += loadEventInfo.bytesLoaded
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                if (measuring && httpErrors.size < 16) {
                    val http = generateSequence<Throwable>(error) { it.cause }.take(8)
                        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    httpErrors += http?.responseCode?.takeIf { it in 100..599 } ?: 0
                }
            }
        }
        val started = SystemClock.elapsedRealtime()
        val scenario = ActivityScenario.launch<VideoActivity>(VideoActivity.importIntent(context, "https://www.youtube.com/watch?v=$id"))
        try {
            bound = context.bindService(Intent(context, VideoPlaybackService::class.java).setAction(VideoPlaybackService.ACTION_LOCAL),
                connection, Context.BIND_AUTO_CREATE)
            assertTrue("Service bind timed out", ready.await(15, TimeUnit.SECONDS))
            val model = requireNotNull(vm)
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
            await("source and player ready", 180_000) { model.state.value.phase is VideoPhase.Ready && model.player.playbackState == Player.STATE_READY }
            main { model.player.pause(); model.setAudioOnly(mode == "audio") }
            await("mode ready") { model.state.value.audioOnly == (mode == "audio") && !model.state.value.modeSaving && model.player.playbackState == Player.STATE_READY }
            if (mode == "watch") {
                val quality = main { preferredQuality(model.state.value.qualities, requestedHeight) }!!
                selectedHeight = quality.height
                main { model.selectQuality(quality) }
                await("quality ready") { !model.state.value.qualityChanging && model.player.playbackState == Player.STATE_READY }
            }
            main {
                assertTrue("Target does not have enough remaining duration", model.state.value.durationMs > startMs + seconds * 1000L + 5_000)
                model.seekTo(startMs)
                model.setSpeed(1f)
                if (!model.player.playWhenReady) model.togglePlay()
            }
            await("seek and playback ready") { model.player.playbackState == Player.STATE_READY && model.player.isPlaying }
            val beginning = main {
                model.player.addListener(listener)
                model.player.addAnalyticsListener(analytics)
                videoHeight = model.player.videoFormat?.height?.takeIf { it > 0 }
                audioBitrate = model.player.audioFormat?.averageBitrate?.takeIf { it > 0 }
                measuring = true
                model.player.currentPosition
            }
            val measureStart = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - measureStart < seconds * 1000L) {
                val sample = main {
                    val failed = model.state.value.phase as? VideoPhase.Failed
                    assertNull("Sustained playback failed: ${failed?.kind}", failed)
                    mapOf("elapsedMs" to (SystemClock.elapsedRealtime() - measureStart),
                        "positionMs" to model.player.currentPosition,
                        "bufferedMs" to model.player.totalBufferedDuration,
                        "state" to model.player.playbackState.toLong(),
                        "playing" to if (model.player.isPlaying) 1L else 0L,
                        "suppression" to model.player.playbackSuppressionReason.toLong())
                }
                samples += sample
                SystemClock.sleep(250)
            }
            elapsed = SystemClock.elapsedRealtime() - measureStart
            advanced = main { model.player.currentPosition - beginning }
            main {
                stallStarted?.let { stalls += SystemClock.elapsedRealtime() - it }
                stallStarted = null
                measuring = false
            }
            assertTrue("Unexpected clock shortfall: elapsedMs=$elapsed advancedMs=$advanced", advanced >= elapsed - 1_000)
            assertTrue("Rebuffering during continuous playback: count=${stalls.size} totalMs=${stalls.sum()}", stalls.isEmpty())
        } catch (error: Throwable) {
            failure = error.javaClass.simpleName
            throw error
        } finally {
            vm?.let { model -> main {
                measuring = false
                model.player.removeListener(listener)
                model.player.removeAnalyticsListener(analytics)
                model.player.pause()
            } }
            runBlocking { store.updateSettings { it.copy(videoQualityHeight = originalHeight) } }
            if (bound) context.unbindService(connection)
            scenario.close()
            val summary = mapOf("mode" to mode, "requestedHeight" to requestedHeight,
                "selectedHeight" to selectedHeight, "decodedHeight" to videoHeight, "audioBitrate" to audioBitrate,
                "elapsedMs" to elapsed, "advancedMs" to advanced, "totalRunMs" to SystemClock.elapsedRealtime() - started,
                "rebufferCount" to stalls.size, "rebufferMs" to stalls.sum(), "maxRebufferMs" to (stalls.maxOrNull() ?: 0),
                "droppedFrames" to droppedFrames, "lastObservedBandwidthEstimateBps" to bandwidth,
                "completedLoaderBytesIncludingCache" to loaderBytes, "httpErrors" to httpErrors,
                "minBufferedMs" to samples.minOfOrNull { it.getValue("bufferedMs") },
                "maxBufferedMs" to samples.maxOfOrNull { it.getValue("bufferedMs") }, "failureType" to failure)
            File(context.getExternalFilesDir("ui-checks"), "continuity-$mode.json")
                .writeText(Gson().toJson(mapOf("summary" to summary, "samples" to samples)))
            println("continuity-summary ${Gson().toJson(summary)}")
        }
    }
}
