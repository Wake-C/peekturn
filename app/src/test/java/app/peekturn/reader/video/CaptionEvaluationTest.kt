package app.peekturn.reader.video

import com.google.gson.Gson
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Re-run local candidates against an explicitly provided real caption snapshot, without network. */
class CaptionEvaluationTest {
    @Test fun exportCandidates() {
        val path = System.getenv("PEEKTURN_VIDEO_PROBE_DIR")
        assumeTrue(!path.isNullOrBlank())
        val root = File(path!!)
        val document = Gson().fromJson(File(root, "source-captions.json").readText(), CaptionDocument::class.java)
        SentenceAlgorithm.entries.forEach { algorithm ->
            File(root, "rules-${algorithm.name.lowercase()}.json").writeText(
                Gson().toJson(sentenceCaptions(document, algorithm)))
        }
    }
}
