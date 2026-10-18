package app.peekturn.reader.speech

import androidx.annotation.StringRes
import app.peekturn.reader.R
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

/**
 * Attribution the app can show for audio that came from a public provider: where it was fetched from.
 * Neither source this app reads states a creator or a licence, so none is modelled here and none is
 * ever invented; the source name and link are the whole credit.
 */
data class AudioCredit(val sourceName: String, val sourceUrl: String)

/** The public voice service this app reads single-word audio from. It states no creator or licence. */
private const val WORD_AUDIO_SOURCE = "Youdao Dictionary"
private const val WORD_AUDIO_SOURCE_URL = "https://dict.youdao.com"

/**
 * What Settings tells users about every online audio route: which service receives the text, that it
 * is free and key-free, that every endpoint is unofficial and unlicensed, and that system speech
 * speaks whenever they have nothing. The app claims no licence for any provider's bytes.
 */
@StringRes
internal val AUDIO_SOURCE_NOTICE: Int = R.string.speech_source_notice

/**
 * Bounded audio for one request, and the attribution that must accompany it. [noticeRes] carries anything
 * about the clip the reader should be told — the fallback's single English accent, for instance — so an
 * honest caveat travels with the bytes rather than being guessed from the credit.
 */
class AudioClip(
    val audio: ByteArray,
    val credit: AudioCredit,
    /** Localized caveat to show with the clip; the player resolves it with the application context. */
    @StringRes val noticeRes: Int? = null
)

/** Audio for one word and accent; null means no usable audio, so the next route speaks instead. */
fun interface WordAudioSource {
    suspend fun lookup(term: String, accent: String): AudioClip?
}

/** The endpoint is unofficial; this names the app without inventing a contact address. */
private val USER_AGENT = "Peekturn/${app.peekturn.reader.BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"
private const val VOICE_PATH = "dictvoice"
private const val MAX_LOOKUP_TERM_LENGTH = 48

/** No answer from either audio service may hold more than this in memory. */
internal const val MAX_AUDIO_BYTES = 2L * 1024 * 1024
private val plainWord = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*")
private const val TERM_EDGES = "\"'.,;:!?()[]{}<>«»„“”‘’—–…·"

/** The credit for every clip from the dictionary service: source name and address only. */
private val WORD_AUDIO_CREDIT = AudioCredit(WORD_AUDIO_SOURCE, WORD_AUDIO_SOURCE_URL)

/**
 * The only word this app sends to the dictionary service: one plain English word. Phrases, sentences
 * and surrounding book text never reach it.
 */
internal fun wordLookupTerm(text: String): String? {
    val term = text.trim().trim { it in TERM_EDGES }.replace('\u2019', '\'').replace('\u2018', '\'')
    return term.takeIf { it.isNotEmpty() && it.length <= MAX_LOOKUP_TERM_LENGTH && plainWord.matches(it) }
}

/** The provider's accent parameter: 1 is UK, 2 is US, matching the mapping open-source word trainers use. */
internal fun voiceType(accent: String): String? = when (accent) {
    "US" -> "2"
    "UK" -> "1"
    else -> null
}

/**
 * The provider answers a lookup it cannot voice with an HTTP error body, and a hostile or broken
 * intermediary could answer anything at all, so only real MPEG frames are handed to the decoder: an
 * MPEG frame sync or an ID3 tag. Anything else counts as no audio rather than as something to play.
 */
internal fun mpegAudio(bytes: ByteArray): Boolean = bytes.size >= 3 && (
    bytes[0] == 0x49.toByte() && bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte() ||
        bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0
    )

/**
 * Single-word audio from the public Youdao Dictionary voice endpoint: one bounded, key-free request
 * per word, cancelled with its coroutine. Unavailable, oversized, malformed or unexpected answers all
 * mean "no audio", so the next route — the speech services, then installed device speech — speaks.
 */
class YoudaoWordAudioClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build(),
    private val baseUrl: HttpUrl = WORD_AUDIO_SOURCE_URL.toHttpUrl()
) : WordAudioSource {

    /** A redirect must never carry the requested word to a host the app did not choose to send it to. */
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun lookup(term: String, accent: String): AudioClip? = withContext(Dispatchers.IO) {
        val word = wordLookupTerm(term) ?: return@withContext null
        val type = voiceType(accent) ?: return@withContext null
        val url = baseUrl.newBuilder()
            .addPathSegment(VOICE_PATH)
            .addQueryParameter("audio", word)
            .addQueryParameter("type", type)
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "audio/mpeg")
            .header("User-Agent", USER_AGENT)
            .build()
        val audio = fetchAudio(http, request) { response -> response.boundedBytes(MAX_AUDIO_BYTES)?.takeIf(::mpegAudio) }
            ?: return@withContext null
        AudioClip(audio, WORD_AUDIO_CREDIT)
    }
}

/**
 * One bounded GET shared by the key-free fetch routes, word audio and the sentence fallback: cancelled with its coroutine, and any problem at all
 * means "no audio" so the caller keeps the text and speaks it another way.
 */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
internal suspend fun <T : Any> fetchAudio(http: OkHttpClient, request: Request, read: (Response) -> T?): T? {
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

/** Reads at most [max] bytes: the body is never buffered past the limit, whatever the service sends. */
internal fun Response.boundedBytes(max: Long): ByteArray? {
    val body = body ?: return null
    if (body.contentLength() > max) return null
    val source = body.source()
    if (source.request(max + 1)) return null
    return source.readByteArray().takeIf { it.isNotEmpty() }
}
