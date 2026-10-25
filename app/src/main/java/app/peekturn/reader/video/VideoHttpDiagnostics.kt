package app.peekturn.reader.video

import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Response

/** Observes responses without changing redirects, cookies, TLS, proxy selection or retry policy. */
internal class VideoHttpDiagnostics(private val record: (String) -> Unit) : EventListener.Factory {
    private val sequence = AtomicInteger()

    override fun create(call: Call): EventListener {
        // IDs only correlate nearby records in the bounded log; they are not content identities.
        val id = sequence.updateAndGet { if (it >= 999_999) 1 else it + 1 }
        return object : EventListener() {
            private var responses = 0
            private var redirects = 0

            private fun emit(event: String) {
                try { record("c$id:$event") } catch (_: Exception) {
                    // A diagnostic writer failure must not break a working source request.
                }
            }

            override fun callStart(call: Call) { emit("start:${domain(call.request().url)}") }

            override fun responseHeadersEnd(call: Call, response: Response) {
                responses++
                if (responses > 24) return
                val location = response.header("Location")
                val redirect = response.code in 300..399 && location != null
                val target = if (redirect) response.request.url.resolve(location!!) else null
                if (redirect) redirects++
                val destination = if (!redirect) "none" else target?.let(::domain) ?: "invalid"
                emit("r$responses:s${response.code}:${domain(response.request.url)}:to:$destination")
            }

            override fun callEnd(call: Call) { emit("end:h$redirects") }

            override fun callFailed(call: Call, ioe: IOException) {
                val reason = when {
                    ioe is ProtocolException && redirects > 20 -> "follow_up_limit"
                    ioe is ProtocolException -> "protocol"
                    call.isCanceled() -> "cancelled_or_timeout"
                    else -> "io"
                }
                emit("failed:h$redirects:$reason")
            }
        }
    }

    private fun domain(url: HttpUrl): String = when (val host = url.host) {
        "consent.youtube.com" -> "youtube_consent"
        "consent.google.com" -> "google_consent"
        else -> when {
            host == "youtube.com" || host.endsWith(".youtube.com") -> "youtube"
            host == "google.com" || host.endsWith(".google.com") -> "google"
            host.endsWith(".googlevideo.com") -> "googlevideo"
            else -> "other"
        }
    }
}
