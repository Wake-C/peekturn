package app.peekturn.reader

import android.app.Application
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
import app.peekturn.reader.narration.PreparedAudioManager
import app.peekturn.reader.narration.PreparedAudioStore
import java.io.File
import app.peekturn.reader.speech.PronunciationPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore

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
    val speech = PronunciationPlayer(
        context.applicationContext,
        onDiagnostic = developerLog::recordSystemSpeech,
        beforeSpeech = {
            preparedAudio.setSpeechActive(true)
            preparedAudio.pause()
        }
    )

    /**
     * Narration files share one disk budget. Online endpoints remain serialized; system file
     * requests have a separate app-wide bound of two, including sessions being replaced.
     */
    internal val sentenceAudio = SentenceAudioCache(SentenceAudioCache.directory(context.applicationContext.noBackupFilesDir))
    internal val narrationProviderGate = Mutex()
    internal val systemSynthesisGate = Semaphore(2)
    internal val preparedAudioStore = PreparedAudioStore(
        File(context.applicationContext.noBackupFilesDir, "prepared-audio")
    )
    internal val preparedAudio = PreparedAudioManager(
        context = context.applicationContext,
        store = preparedAudioStore,
        books = store,
        engine = engine,
        cache = sentenceAudio,
        providerGate = narrationProviderGate,
        systemSynthesisGate = systemSynthesisGate,
        onDiagnostic = developerLog::recordNarration
    )

    init {
        scope.launch {
            try {
                preparedAudioStore.refresh()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                developerLog.recordFailure("narration", "saved-audio-load", failure)
            }
        }
        scope.launch {
            speech.state.collect { preparedAudio.setSpeechActive(it.playing) }
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
