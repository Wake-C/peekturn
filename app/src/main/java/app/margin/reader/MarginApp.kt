package app.margin.reader

import android.app.Application
import android.content.Context
import app.margin.reader.data.MarginStore
import app.margin.reader.data.OfflineDictionary
import app.margin.reader.learning.ExplanationCache
import app.margin.reader.learning.ExplanationClient
import app.margin.reader.reader.EpubEngine
import app.margin.reader.reader.EpubImporter
import app.margin.reader.narration.SentenceAudioCache
import app.margin.reader.speech.PronunciationPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex

class MarginApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        AppLanguage.apply(this, graph.store.settings.value.uiLanguage)
    }
}
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = MarginStore(context.applicationContext)
    val engine = EpubEngine(context.applicationContext)
    val importer = EpubImporter(context.applicationContext, engine, store)
    val explanations = ExplanationClient()
    /** Complete explanation answers survive reader exit and app restart for their absolute 15 days. */
    internal val explanationCache = ExplanationCache(
        ExplanationCache.directory(context.applicationContext.noBackupFilesDir)
    )
    val dictionary = OfflineDictionary(context.applicationContext)
    val speech = PronunciationPlayer(context.applicationContext)

    /**
     * Persistent sentence audio for continuous narration and the app-wide gate that permits only one
     * provider synthesis request at a time, even across playback-session switches. Popup word and
     * example speech keeps its separate memory-only player above.
     */
    internal val sentenceAudio = SentenceAudioCache(SentenceAudioCache.directory(context.applicationContext.noBackupFilesDir))
    internal val narrationProviderGate = Mutex()
}
