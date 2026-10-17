package app.margin.reader.speech

import androidx.annotation.StringRes
import app.margin.reader.R

import java.io.InterruptedIOException
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

/** The public, key-free speech endpoint this app falls back to when the read-aloud service is silent. */
private const val BAIDU_SOURCE = "Baidu Translate"
private const val BAIDU_SOURCE_URL = "https://fanyi.baidu.com"
private const val BAIDU_SPEECH_PATH = "gettts"

/**
 * The fallback voices every request with its one English voice, whatever accent the reader chose, so a
 * clip from here is a substitution and says so. It is also the only English accent it can offer. The
 * player resolves this id with the application context, so the caveat follows the reader's language.
 */
@StringRes
internal val BAIDU_ACCENT_NOTICE: Int = R.string.speech_notice_baidu_accent

/** The credit for every clip from the fallback: source name and address only. */
private val BAIDU_CREDIT = AudioCredit(BAIDU_SOURCE, BAIDU_SOURCE_URL)

/** The endpoint answers a plain browser agent; sending one is no licence claim and grants none. */
private const val USER_AGENT = "Mozilla/5.0"

/** The route's bound, under the player's four-second route budget so this client reports its own stop. */
private const val ROUTE_BUDGET_MS = 3_700L

/**
 * Speech for arbitrary text from a public, key-free endpoint: one bounded HTTPS GET per text, the
 * simplest possible fallback when the read-aloud handshake refuses or stalls. It is unendorsed and
 * unlicensed, has one English voice, and states no creator, so the clip credits the service by name and
 * address, carries the accent caveat, and claims nothing further. Unavailable, redirected, oversized,
 * malformed or unexpected answers all mean "no audio", so device speech speaks instead, while the
 * stage that failed is reported so the reader can send back why the route was silent.
 */
class BaiduSpeechClient(
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(ROUTE_BUDGET_MS, TimeUnit.MILLISECONDS)
        .callTimeout(ROUTE_BUDGET_MS, TimeUnit.MILLISECONDS).build(),
    private val baseUrl: HttpUrl = BAIDU_SOURCE_URL.toHttpUrl()
) : SentenceAudioSource {

    override val route: String = "baidu"

    /** A redirect must never carry the requested text to a host the app did not choose to send it to. */
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    override suspend fun lookup(
        text: String,
        accent: String,
        failed: (RouteOutcome) -> Unit
    ): AudioClip? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
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
        val tracker = RouteStageTracker(webSocket = false)
        val call = http.newBuilder().eventListener(tracker).build().newCall(request)
        val job = currentCoroutineContext()[Job]!!
        val cancellation = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) call.cancel()
        }
        var responseSeen = false
        try {
            call.execute().use { response ->
                responseSeen = true
                if (!response.isSuccessful) {
                    failed(
                        RouteOutcome(
                            route, RouteStage.Response, elapsedMillis(started),
                            httpStatus = response.code
                        )
                    )
                    return@withContext null
                }
                val body = response.body
                if (body == null) {
                    failed(
                        RouteOutcome(
                            route, RouteStage.FirstByte, elapsedMillis(started),
                            detail = NO_AUDIO_BYTES
                        )
                    )
                    return@withContext null
                }
                if (body.contentLength() > MAX_AUDIO_BYTES) {
                    failed(oversized(started))
                    return@withContext null
                }
                val source = body.source()
                if (source.request(MAX_AUDIO_BYTES + 1)) {
                    failed(oversized(started))
                    return@withContext null
                }
                val audio = source.readByteArray()
                when {
                    audio.isEmpty() -> {
                        failed(
                            RouteOutcome(
                                route, RouteStage.FirstByte, elapsedMillis(started),
                                detail = NO_AUDIO_BYTES
                            )
                        )
                        null
                    }
                    !mpegAudio(audio) -> {
                        failed(
                            RouteOutcome(
                                route, RouteStage.Answer, elapsedMillis(started),
                                detail = ANSWER_NOT_AUDIO
                            )
                        )
                        null
                    }
                    else -> AudioClip(audio, BAIDU_CREDIT, noticeRes = BAIDU_ACCENT_NOTICE)
                }
            }
        } catch (cancelled: CancellationException) {
            // A newer request or a stop owns this cancellation; it is not a failure to report.
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val stage = routeStage(error, responseSeen, tracker.stage)
                ?: if (responseSeen) RouteStage.FirstByte else tracker.stage
            failed(
                RouteOutcome(
                    route, stage, elapsedMillis(started),
                    timedOut = error is InterruptedIOException
                )
            )
            null
        } finally {
            cancellation.dispose()
            call.cancel()
        }
    }

    private fun oversized(started: Long) =
        RouteOutcome(route, RouteStage.Answer, elapsedMillis(started), detail = OVERSIZED_ANSWER)
}
