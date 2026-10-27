package app.peekturn.reader

import android.app.Application
import android.app.NotificationManager
import app.peekturn.reader.speech.SystemSpeechLifecycle
import android.content.Context
import app.peekturn.reader.data.PeekturnStore
import app.peekturn.reader.data.OfflineDictionary
import app.peekturn.reader.diagnostics.DeveloperLog
import app.peekturn.reader.diagnostics.developerLogSnapshot
import app.peekturn.reader.learning.ExplanationCache
import app.peekturn.reader.learning.ExplanationClient
import app.peekturn.reader.reader.EpubEngine
import app.peekturn.reader.reader.EpubImporter
import app.peekturn.reader.narration.SentenceAudioCache
import java.io.File
import app.peekturn.reader.speech.PronunciationPlayer
import app.peekturn.reader.video.CaptionMergeClient
import app.peekturn.reader.video.VideoCaptionCache
import app.peekturn.reader.video.VideoMediaCache
import app.peekturn.reader.video.VideoRepository
import app.peekturn.reader.video.VideoDownloads
import app.peekturn.reader.video.YouTubeSource
import app.peekturn.reader.video.defaultVideoHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

class PeekturnApp : Application() {
    /**
     * Created lazily but independently of AppGraph, so the crash handler exists before the rest of
     * the graph can throw. AppGraph reuses this exact instance instead of opening a second writer.
     */
    private val developerLog by lazy {
        DeveloperLog(
            DeveloperLog.directory(noBackupFilesDir),
            snapshot = { developerLogSnapshot(applicationContext) }
        )
    }
    val graph: AppGraph by lazy { AppGraph(this, developerLog = developerLog) }
    override fun onCreate() {
        super.onCreate()
        developerLog.installCrashHandler()
        developerLog.recordEvent("app", "start")
        AppLanguage.apply(this, graph.store.settings.value.uiLanguage)
        registerActivityLifecycleCallbacks(SystemSpeechLifecycle())
    }
}
class AppGraph(
    context: Context,
    val seedBuiltinBook: Boolean = true,
    /** Support diagnostics exist before any feature that can fail, including the speech player. */
    val developerLog: DeveloperLog = DeveloperLog(
        DeveloperLog.directory(context.applicationContext.noBackupFilesDir),
        snapshot = { developerLogSnapshot(context.applicationContext) }
    )
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = PeekturnStore(context.applicationContext)
    val engine = EpubEngine(context.applicationContext)
    val importer = EpubImporter(context.applicationContext, engine, store)
    val explanations = ExplanationClient()
    /** Complete explanation answers survive reader exit and app restart for their absolute 15 days. */
    internal val explanationCache = ExplanationCache(
        ExplanationCache.directory(context.applicationContext.noBackupFilesDir)
    )
    val dictionary = OfflineDictionary(context.applicationContext)
    val videoSource = YouTubeSource(defaultVideoHttpClient { event ->
        developerLog.recordEvent("video-http", event)
    })
    val videoCaptions = VideoCaptionCache(File(context.applicationContext.noBackupFilesDir, "video-captions"))
    internal val videoMediaCache by lazy {
        VideoMediaCache(context.applicationContext,
            onFailure = { failure -> developerLog.recordFailure("video", "media-cache", failure) },
            onEvent = { event -> developerLog.recordEvent("video", event) })
    }
    val captionMerger = CaptionMergeClient()
    // Media bodies may take much longer than source-resolution calls. Bound connection/idle
    // reads, not the total transfer; Media3 owns cancellation and retries. Playback and
    // explicit downloads run independently, without a shared priority gate.
    internal val videoMediaHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    internal val videoDownloads by lazy { VideoDownloads(context.applicationContext, this, scope) }
    val videos = VideoRepository(store, videoSource, videoCaptions, captionMerger,
        offlineSession = { item -> videoDownloads.session(item) })
    val speech = PronunciationPlayer(
        context.applicationContext,
        onDiagnostic = developerLog::recordSystemSpeech
    )

    /**
     * Narration files share one disk budget. Online endpoints remain serialized; system file
     * requests have a separate app-wide bound of two, including sessions being replaced.
     */
    internal val sentenceAudio = SentenceAudioCache(SentenceAudioCache.directory(context.applicationContext.noBackupFilesDir))
    internal val narrationProviderGate = Mutex()
    internal val systemSynthesisGate = Semaphore(2)

    init {
        // Whole-book prepared audio shipped in 1.3.0 and was removed. Its private directory and
        // notification channel are obsolete; delete only that directory, never books or the shared
        // sentence cache. A failure is logged without paths and retried on the next start.
        scope.launch {
            withContext(Dispatchers.IO) {
                val legacy = File(context.applicationContext.noBackupFilesDir, "prepared-audio")
                try {
                    if (legacy.exists() && !legacy.deleteRecursively()) {
                        developerLog.recordFailure("narration", "saved-audio-cleanup")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    developerLog.recordFailure("narration", "saved-audio-cleanup")
                }
            }
            try {
                context.applicationContext.getSystemService(NotificationManager::class.java)?.apply {
                    // The removed foreground service used id 0x5041 and channel "prepared_audio".
                    cancel(0x5041)
                    deleteNotificationChannel("prepared_audio")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                developerLog.recordFailure("narration", "saved-audio-notification-cleanup")
            }
        }
        var provider = store.settings.value.sentenceSpeechProvider
        scope.launch {
            store.settings.collect { settings ->
                if (settings.sentenceSpeechProvider != provider) {
                    provider = settings.sentenceSpeechProvider
                    speech.stop()
                }
            }
        }
    }
}
