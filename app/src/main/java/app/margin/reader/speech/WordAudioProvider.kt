package app.margin.reader.speech

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
 * The one source this app reads states no creator and no licence, so none is modelled here and none
 * is ever invented; the source name and link are the whole credit.
 */
data class AudioCredit(val sourceName: String, val sourceUrl: String)

/** The public voice service this app reads single-word audio from. It states no creator or licence. */
private const val WORD_AUDIO_SOURCE = "Youdao Dictionary"
private const val WORD_AUDIO_SOURCE_URL = "https://dict.youdao.com"

/**
 * What Settings tells users about the word-audio route: free and key-free, but an unofficial public
 * endpoint with no availability guarantee, and it is never used for phrases. The app claims no
 * licence for the provider's bytes.
 */
internal const val WORD_AUDIO_NOTICE =
    "Word audio comes from the public Youdao Dictionary service: free and key-free, but unofficial and not guaranteed to stay available. Only the word itself is sent; phrases and examples use device speech."

/** Bounded audio for one word and the attribution that must accompany it. */
class WordAudioClip(val audio: ByteArray, val credit: AudioCredit)

/** Audio for one word and accent; null means no usable audio, so installed system speech speaks. */
fun interface WordAudioSource {
    suspend fun lookup(term: String, accent: String): WordAudioClip?
}

/** The endpoint is unofficial; this names the app without inventing a contact address. */
private val USER_AGENT = "Margin/${app.margin.reader.BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})"
private const val VOICE_PATH = "dictvoice"
private const val MAX_LOOKUP_TERM_LENGTH = 48
private const val MAX_AUDIO_BYTES = 2L * 1024 * 1024
private val plainWord = Regex("[A-Za-z]+(?:['-][A-Za-z]+)*")
private const val TERM_EDGES = "\"'.,;:!?()[]{}<>«»„“”‘’—–…·"

/** The credit for every clip from the single source this app reads: source name and address only. */
private val WORD_AUDIO_CREDIT = AudioCredit(WORD_AUDIO_SOURCE, WORD_AUDIO_SOURCE_URL)

/**
 * The only text this app sends to a pronunciation service: one plain English word. Phrases, sentences
 * and surrounding book text never leave the device.
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
 * mean "no audio", so the caller speaks with installed system speech instead.
 */
class YoudaoWordAudioClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).build(),
    private val baseUrl: HttpUrl = WORD_AUDIO_SOURCE_URL.toHttpUrl()
) : WordAudioSource {

    /** A redirect must never carry the requested word to a host the app did not choose to send it to. */
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun lookup(term: String, accent: String): WordAudioClip? = withContext(Dispatchers.IO) {
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
        val audio = fetch(request) { response -> response.boundedBytes(MAX_AUDIO_BYTES)?.takeIf(::mpegAudio) }
            ?: return@withContext null
        WordAudioClip(audio, WORD_AUDIO_CREDIT)
    }

    /** Bounded by the client timeouts and cancelled with its coroutine; any problem means "no audio". */
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
