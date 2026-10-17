package app.margin.reader.speech

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The public, key-free speech endpoint this app falls back to when the read-aloud service is silent. */
private const val BAIDU_SOURCE = "Baidu Translate"
private const val BAIDU_SOURCE_URL = "https://fanyi.baidu.com"
private const val BAIDU_SPEECH_PATH = "gettts"

/**
 * The fallback voices every request with its one English voice, whatever accent the reader chose, so a
 * clip from here is a substitution and says so. It is also the only English accent it can offer.
 */
internal const val BAIDU_ACCENT_NOTICE =
    "Baidu Translate has a single English voice, so the requested accent is not guaranteed."

/** The credit for every clip from the fallback: source name and address only. */
private val BAIDU_CREDIT = AudioCredit(BAIDU_SOURCE, BAIDU_SOURCE_URL)

/** The endpoint answers a plain browser agent; sending one is no licence claim and grants none. */
private const val USER_AGENT = "Mozilla/5.0"

/**
 * Speech for arbitrary text from a public, key-free endpoint: one bounded HTTPS GET per text, the
 * simplest possible fallback when the read-aloud handshake refuses or stalls. It is unendorsed and
 * unlicensed, has one English voice, and states no creator, so the clip credits the service by name and
 * address, carries the accent caveat, and claims nothing further. Unavailable, redirected, oversized,
 * malformed or unexpected answers all mean "no audio", so device speech speaks instead.
 */
class BaiduSpeechClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build(),
    private val baseUrl: HttpUrl = BAIDU_SOURCE_URL.toHttpUrl()
) : SentenceAudioSource {

    /** A redirect must never carry the requested text to a host the app did not choose to send it to. */
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun lookup(text: String, accent: String): AudioClip? = withContext(Dispatchers.IO) {
        val spoken = speechLookupText(text) ?: return@withContext null
        // This route voices one accent, but the app still only asks for the accents it offers, so a
        // request is made only for the text the reader asked to hear in an accent the app accepts.
        speechLocale(accent) ?: return@withContext null
        val url = baseUrl.newBuilder()
            .addPathSegment(BAIDU_SPEECH_PATH)
            .addQueryParameter("lan", "en")
            .addQueryParameter("spd", "3")
            .addQueryParameter("source", "web")
            .addQueryParameter("text", spoken)
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "audio/mpeg")
            .header("User-Agent", USER_AGENT)
            .build()
        val audio = fetchAudio(http, request) { response -> response.boundedBytes(MAX_AUDIO_BYTES)?.takeIf(::mpegAudio) }
            ?: return@withContext null
        AudioClip(audio, BAIDU_CREDIT, BAIDU_ACCENT_NOTICE)
    }
}
