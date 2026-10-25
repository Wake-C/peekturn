package app.peekturn.reader.video

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in live source check. Captions leave the process only to an explicitly selected local dir. */
class YouTubeSourceLiveTest {
    @Test fun inspectMediaIdentityWithoutExportingSignedUrls() = runBlocking {
        val id = System.getenv("PEEKTURN_VIDEO_PROBE_ID")
        val output = System.getenv("PEEKTURN_VIDEO_PROBE_DIR")
        assumeTrue(!id.isNullOrBlank() && !output.isNullOrBlank())
        val resolved = YouTubeSource().resolve(id!!)
        fun fields(url: String): Map<String, String?> {
            val parsed = url.toHttpUrl()
            return listOf("itag", "clen", "lmt").associateWith { parsed.queryParameter(it) }
        }
        val summaries = resolved.qualities.map { quality -> mapOf(
            "height" to quality.height,
            "videoCacheable" to (quality.source.videoCacheKey != null),
            "audioCacheable" to (quality.source.audioUrl != null && quality.source.audioCacheKey != null),
            "video" to fields(quality.source.videoUrl),
            "audio" to quality.source.audioUrl?.let(::fields)
        ) }
        File(output!!).mkdirs()
        File(output, "media-source-fields.json").writeText(Gson().toJson(summaries))
        check(summaries.isNotEmpty())
    }

    @Test fun resolveAndReadEnglishCaptions() = runBlocking {
        val id = System.getenv("PEEKTURN_VIDEO_PROBE_ID")
        val output = System.getenv("PEEKTURN_VIDEO_PROBE_DIR")
        assumeTrue(!id.isNullOrBlank() && !output.isNullOrBlank())
        val source = YouTubeSource()
        val resolved = source.resolve(id!!)
        val track = preferredTrack(resolved.tracks) ?: error("No English track")
        val document = source.captions(id, track)
        check(document.cues.isNotEmpty())
        File(output!!).mkdirs()
        File(output, "source-captions.json").writeText(Gson().toJson(document))
        SentenceAlgorithm.entries.forEach { algorithm ->
            File(output, "rules-${algorithm.name.lowercase()}.json").writeText(
                Gson().toJson(sentenceCaptions(document, algorithm)))
        }
        // Never export expiring media/caption URLs.
        File(output, "source-summary.json").writeText(Gson().toJson(mapOf(
            "videoId" to id, "title" to resolved.metadata.title,
            "heights" to resolved.qualities.map { it.height }, "cueCount" to document.cues.size
        )))
    }
}
