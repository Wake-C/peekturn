package app.margin.reader

import android.app.Application
import android.content.Context
import app.margin.reader.data.MarginStore
import app.margin.reader.data.OfflineDictionary
import app.margin.reader.learning.ExplanationClient
import app.margin.reader.reader.EpubEngine
import app.margin.reader.reader.EpubImporter
import app.margin.reader.speech.PronunciationPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MarginApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = MarginStore(context.applicationContext)
    val engine = EpubEngine(context.applicationContext)
    val importer = EpubImporter(context.applicationContext, engine, store)
    val explanations = ExplanationClient()
    val dictionary = OfflineDictionary(context.applicationContext)
    val speech = PronunciationPlayer(context.applicationContext)
}
