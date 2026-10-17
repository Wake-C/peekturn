package app.margin.reader.speech

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The public speech service this app reads sentence audio from. It states no creator or licence. */
private const val SPEECH_SOURCE = "Google Translate"
private const val SPEECH_SOURCE_URL = "https://translate.google.com"

/**
 * The service stops voicing text at around two hundred characters, so anything longer is refused
 * before a request: a truncated answer is not the text the user asked to hear.
 */
internal const val MAX_SPEECH_TEXT_LENGTH = 190

/** The credit for every clip from the speech service: source name and address only. */
private val SPEECH_CREDIT = AudioCredit(SPEECH_SOURCE, SPEECH_SOURCE_URL)

/** The endpoint answers a plain browser agent; sending one is no licence claim and grants none. */
private const val USER_AGENT = "Mozilla/5.0"
private const val SPEECH_PATH = "translate_tts"

/** Speech for a phrase, sentence or example; null means no usable audio, so device speech speaks. */
fun interface SentenceAudioSource {
    suspend fun lookup(text: String, accent: String): AudioClip?
}

/**
 * The only text this app sends to the speech service: the text the user asked to hear, trimmed and no
 * longer than the service can voice in one answer. Never surrounding book context or any credential.
 */
internal fun speechLookupText(text: String): String? =
    text.trim().takeIf { it.isNotEmpty() && it.length <= MAX_SPEECH_TEXT_LENGTH }

/**
 * Speech for arbitrary text from the public Google Translate speech endpoint: one bounded, key-free
 * request per text, cancelled with its coroutine. Unavailable, redirected, oversized, malformed or
 * unexpected answers all mean "no audio", so the caller speaks with installed device speech instead.
 */
class TranslateSpeechClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build(),
    private val baseUrl: HttpUrl = SPEECH_SOURCE_URL.toHttpUrl()
) : SentenceAudioSource {

    /** A redirect must never carry the requested text to a host the app did not choose to send it to. */
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun lookup(text: String, accent: String): AudioClip? = withContext(Dispatchers.IO) {
        val spoken = speechLookupText(text) ?: return@withContext null
        // The accents this app offers are the accents this endpoint can voice, so the same mapping
        // gates the request and names its language.
        val language = speechLocale(accent)?.toLanguageTag() ?: return@withContext null
        val url = baseUrl.newBuilder()
            .addPathSegment(SPEECH_PATH)
            .addQueryParameter("ie", "UTF-8")
            .addQueryParameter("client", "tw-ob")
            .addQueryParameter("tl", language)
            .addQueryParameter("q", spoken)
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "audio/mpeg")
            .header("User-Agent", USER_AGENT)
            .build()
        val audio = fetchAudio(http, request) { response -> response.boundedBytes(MAX_AUDIO_BYTES)?.takeIf(::mpegAudio) }
            ?: return@withContext null
        AudioClip(audio, SPEECH_CREDIT)
    }
}
