package app.margin.reader.speech

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/** Attribution kept while a Commons recording is the current pronunciation. */
data class RecordingCredit(
    val creator: String?,
    val sourceName: String,
    val sourceUrl: String,
    val licenseName: String,
    /** Blank when Commons states a licence without a separate deed address. */
    val licenseUrl: String
)

/** Bounded audio for the requested accent with the attribution its licence requires. */
class RecordedWordAudio(val audio: ByteArray, val credit: RecordingCredit)

/** Recorded audio for one word and accent; null means no usable recording, so system speech speaks. */
fun interface WordAudioSource {
    suspend fun lookup(term: String, accent: String): RecordedWordAudio?
}

private const val COMMONS_BASE = "https://commons.wikimedia.org"
private const val COMMONS_PAGE_HOST = "commons.wikimedia.org"
private const val COMMONS_MEDIA_HOST = "upload.wikimedia.org"
private const val COMMONS_MEDIA_PATH = "/wikipedia/commons/"
private const val SOURCE_NAME = "Wikimedia Commons"
/** Wikimedia asks automated clients to identify themselves; this invents no contact address. */
private val USER_AGENT = "Margin/${app.margin.reader.BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"
private const val MAX_LOOKUP_TERM_LENGTH = 48
private const val MAX_METADATA_BYTES = 512L * 1024
private const val MAX_AUDIO_BYTES = 2L * 1024 * 1024
private val plainWord = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*")
private const val TERM_EDGES = "\"'.,;:!?()[]{}<>«»„“”‘’—–…·"
private val publicDomainTerms = Regex("public domain|\\bcc0\\b|/zero/|/publicdomain/|/mark/")
private val attributionTerms = Regex("(^|[^a-z-])cc[- ]?by(?:[- ]?sa)?(?![a-z0-9-])|creative commons attribution|/licenses/by(?:-sa)?/")
private val nonFreeTerms = Regex("non[- ]?commercial|no[- ]?deriv|noderivs|\\bnc\\b|\\bnd\\b")

/**
 * The only text this app sends to a pronunciation service: one plain English word. Phrases, sentences
 * and surrounding book text never leave the device.
 */
internal fun wordLookupTerm(text: String): String? {
    val term = text.trim().trim { it in TERM_EDGES }.replace('\u2019', '\'').replace('\u2018', '\'')
    return term.takeIf { it.isNotEmpty() && it.length <= MAX_LOOKUP_TERM_LENGTH && plainWord.matches(it) }
}

/** Commons keeps pronunciation recordings at conventional region-specific file titles. */
internal fun recordingTitle(term: String, accent: String): String? = when (accent) {
    "US" -> "File:En-us-$term.ogg"
    "UK" -> "File:En-uk-$term.ogg"
    else -> null
}

internal data class CommonsRecording(
    val mediaUrl: String,
    val sourceUrl: String,
    val creator: String?,
    val licenseName: String?,
    val licenseUrl: String?
)

/**
 * Reads the requested Commons file and nothing else: a missing page, another word or another accent's
 * file is never presented as the requested US or UK pronunciation, and the media address is the one
 * Commons reports on its own media host rather than one assembled here.
 */
internal fun commonsRecording(body: String, title: String, mediaHost: String = COMMONS_MEDIA_HOST): CommonsRecording? {
    val root = try { JsonParser.parseString(body) } catch (_: Exception) { return null }
    val pages = ((root as? JsonObject)?.get("query") as? JsonObject)?.get("pages") as? JsonArray ?: return null
    val page = pages.firstOrNull { it is JsonObject && sameTitle(it.string("title"), title) } as? JsonObject ?: return null
    if (page.has("missing")) return null
    val info = (page.get("imageinfo") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    val mediaUrl = commonsMediaUrl(info.string("url"), mediaHost) ?: return null
    val sourceUrl = info.string("descriptionurl") ?: return null
    val metadata = info.get("extmetadata") as? JsonObject
    // Only Artist names the creator: Commons Credit text such as "Own work" or "Shtooka Project" is a
    // project note, and an attribution-required licence must never be satisfied by it.
    val creator = metadata?.metadataValue("Artist")?.let(::cleanCredit)?.takeIf(String::isNotEmpty)
    val licenseName = listOf("LicenseShortName", "UsageTerms")
        .firstNotNullOfOrNull { key -> metadata?.metadataValue(key)?.let(::cleanCredit)?.takeIf(String::isNotEmpty) }
    return CommonsRecording(mediaUrl, sourceUrl, creator, licenseName, metadata?.metadataValue("LicenseUrl"))
}

/**
 * Credit a recording must carry before it may play: a licence this app may use, a Commons page we can
 * link, and — unless the licence waives attribution — a named creator. Source and licence stay exactly
 * as Commons states them, and a licence address that is not HTTPS is dropped rather than shown.
 */
internal fun recordingCredit(recording: CommonsRecording): RecordingCredit? {
    val licenseName = recording.licenseName?.takeIf { it.isNotBlank() } ?: return null
    val sourceUrl = commonsPageUrl(recording.sourceUrl) ?: return null
    if (!freeLicense(licenseName, recording.licenseUrl)) return null
    if (!attributionWaived(licenseName, recording.licenseUrl) && recording.creator.isNullOrBlank()) return null
    return RecordingCredit(recording.creator, SOURCE_NAME, sourceUrl, licenseName, httpsLink(recording.licenseUrl).orEmpty())
}

/** Licences this app may play: Creative Commons Attribution (with or without ShareAlike), CC0 and public domain. */
internal fun freeLicense(licenseName: String, licenseUrl: String?): Boolean {
    val terms = "$licenseName ${licenseUrl.orEmpty()}".lowercase()
    return publicDomainTerms.containsMatchIn(terms) ||
        (attributionTerms.containsMatchIn(terms) && !nonFreeTerms.containsMatchIn(terms))
}

/** Public-domain terms do not require a named creator; Creative Commons attribution does. */
internal fun attributionWaived(licenseName: String, licenseUrl: String?): Boolean =
    publicDomainTerms.containsMatchIn("$licenseName ${licenseUrl.orEmpty()}".lowercase())

/** Commons stores artist credits as wiki text; keep the visible names only. */
internal fun cleanCredit(value: String): String = Jsoup.parse(value).text().trim()

/** Commons extmetadata wraps every field as {"value": "..."}. */
private fun JsonObject.metadataValue(key: String): String? =
    ((get(key) as? JsonObject)?.get("value") ?: get(key))
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement.string(key: String): String? =
    (this as? JsonObject)?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.trim()?.takeIf { it.isNotEmpty() }

/**
 * MediaWiki only folds the first letter of a page name, so every other letter of a file title stays
 * exactly as requested: a different word, spelling or accent is never accepted as a substitute.
 */
private fun sameTitle(actual: String?, requested: String): Boolean {
    val left = actual?.replace('_', ' ')?.trim() ?: return false
    val right = requested.replace('_', ' ').trim()
    if (left.length != right.length) return false
    return left.substring(1) == right.substring(1) && left.first().equals(right.first(), ignoreCase = true)
}

/** Only the media address Commons itself reports for the requested page, served by its own media host. */
private fun commonsMediaUrl(raw: String?, mediaHost: String): String? {
    val url = raw?.let { runCatching { it.toHttpUrl() }.getOrNull() } ?: return null
    if (!url.isHttps || url.host != mediaHost || !url.encodedPath.startsWith(COMMONS_MEDIA_PATH)) return null
    return url.newBuilder().query(null).fragment(null).build().toString()
}

private fun commonsPageUrl(raw: String?): String? {
    val url = raw?.let { runCatching { it.toHttpUrl() }.getOrNull() } ?: return null
    return url.toString().takeIf { url.isHttps && url.host == COMMONS_PAGE_HOST }
}

private fun httpsLink(raw: String?): String? {
    val url = raw?.let { runCatching { it.toHttpUrl() }.getOrNull() } ?: return null
    return url.toString().takeIf { url.isHttps }
}

/**
 * Recorded single-word pronunciations from the Wikimedia Commons Action API: one bounded, key-free
 * request for the metadata and one for the audio, both cancelled with their coroutine.
 */
class WordAudioClient(
    http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build(),
    private val commonsBase: HttpUrl = COMMONS_BASE.toHttpUrl(),
    private val mediaHost: String = COMMONS_MEDIA_HOST
) : WordAudioSource {

    /** A recording must come from the address Commons stated, never from wherever a redirect points. */
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun lookup(term: String, accent: String): RecordedWordAudio? = withContext(Dispatchers.IO) {
        val word = wordLookupTerm(term) ?: return@withContext null
        val title = recordingTitle(word, accent) ?: return@withContext null
        val body = fetch(request(title)) { response -> response.boundedText(MAX_METADATA_BYTES) } ?: return@withContext null
        val recording = commonsRecording(body, title, mediaHost) ?: return@withContext null
        val credit = recordingCredit(recording) ?: return@withContext null
        val audio = fetch(Request.Builder().url(recording.mediaUrl).header("User-Agent", USER_AGENT).build()) { response ->
            response.boundedBytes(MAX_AUDIO_BYTES)
        } ?: return@withContext null
        RecordedWordAudio(audio, credit)
    }

    private fun request(title: String): Request = Request.Builder().url(
        commonsBase.newBuilder().addPathSegment("w").addPathSegment("api.php")
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .addQueryParameter("prop", "imageinfo")
            .addQueryParameter("iiprop", "url|extmetadata")
            .addQueryParameter(
                "iiextmetadatafilter",
                "Artist|Credit|LicenseShortName|UsageTerms|LicenseUrl|Copyrighted|AttributionRequired"
            )
            .addQueryParameter("titles", title)
            .build()
    ).header("Accept", "application/json").header("User-Agent", USER_AGENT).build()

    /** Bounded by the client timeouts and cancelled with its coroutine; any problem means "no recording". */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private suspend fun <T : Any> fetch(request: Request, read: (Response) -> T?): T? {
        val call = http.newCall(request)
        val job = currentCoroutineContext()[Job]!!
        val cancellation = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        return try {
            call.execute().use { response -> if (response.isSuccessful) read(response) else null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            null
        } finally {
            cancellation.dispose()
            call.cancel()
        }
    }
}

/** Reads at most [max] bytes: the body is never buffered past the limit, whatever the service sends. */
private fun Response.boundedBytes(max: Long): ByteArray? {
    val body = body ?: return null
    if (body.contentLength() > max) return null
    val source = body.source()
    if (source.request(max + 1)) return null
    return source.readByteArray().takeIf { it.isNotEmpty() }
}

private fun Response.boundedText(max: Long): String? = boundedBytes(max)?.toString(Charsets.UTF_8)
