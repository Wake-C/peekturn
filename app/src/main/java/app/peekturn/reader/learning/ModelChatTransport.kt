package app.peekturn.reader.learning

import app.peekturn.reader.model.ModelConfig
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.stream.MalformedJsonException
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The JSON contract a caller asks the model for. [name] only matters to providers that accept a
 * named strict schema; [json] is also the schema text appended to the instruction elsewhere.
 */
data class ChatSchema(val name: String, val json: JsonObject)

/**
 * The only place that talks to an OpenAI-compatible chat-completions provider: endpoint
 * normalization, credential checks, request envelope, SSE decoding, cancellation and failure
 * categories. Callers own their prompts, their output schema and how the text is interpreted, so
 * explanations and caption grouping cannot drift into two endpoint or recovery rules.
 *
 * The schema is sent as a strict `json_schema` response format only where the provider supports it,
 * and appended to the first system message otherwise. Never logs credentials, prompts or content.
 */
class ModelChatTransport(private val http: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
    .callTimeout(180, TimeUnit.SECONDS).build()) {

    /**
     * Assistant content deltas in provider order. Usage-only records emit nothing, and the stream
     * completes only after a terminal `finish_reason`; anything else throws an app-authored
     * [ExplanationFailure]. Cancellation closes the socket and rethrows [CancellationException].
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    fun stream(config: ModelConfig, messages: List<JsonObject>, schema: ChatSchema): Flow<String> = flow {
        try {
            val call = http.newCall(request(config, messages, schema))
            val job = currentCoroutineContext()[Job]!!
            // A blocking socket read must be cancelled before this coroutine can finish.
            val cancellation = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause != null) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) failHttp(response.code)
                    requireResponse(
                        response.header("Content-Type").orEmpty().substringBefore(';').trim()
                            .equals("text/event-stream", true),
                        ExplanationFailureCategory.Protocol
                    ) { "Provider did not return an event stream. Choose a streaming chat-completions provider." }
                    val source = response.body?.source()
                        ?: failResponse("Provider returned an empty response.", ExplanationFailureCategory.IncompleteResponse)
                    var finished = false
                    var done = false
                    val data = StringBuilder()
                    suspend fun event() {
                        if (data.isEmpty()) return
                        val payload = data.toString().removeSuffix("\n")
                        data.setLength(0)
                        if (payload == "[DONE]") {
                            requireResponse(finished, ExplanationFailureCategory.IncompleteResponse) { "Stream ended without a completion reason." }
                            done = true
                            return
                        }
                        requireResponse(!done, ExplanationFailureCategory.Protocol) { "Unexpected data after the stream ended." }
                        val envelope = JsonParser.parseString(payload).asJsonObject
                        requireResponse(!envelope.has("error"), ExplanationFailureCategory.ProviderRejected) { "Provider reported an error while streaming." }
                        val choices = envelope.getAsJsonArray("choices")
                            ?: failResponse("Invalid provider stream envelope.", ExplanationFailureCategory.Protocol)
                        if (choices.size() == 0) return // Usage-only stream record.
                        val choice = choices[0].asJsonObject
                        val delta = choice.getAsJsonObject("delta")
                        if (delta != null) {
                            val refusal = delta.get("refusal")
                            requireResponse(refusal == null || refusal.isJsonNull || refusal.asString.isBlank(), ExplanationFailureCategory.Refusal) { "The provider refused this request." }
                            val content = delta.get("content")
                            if (content != null && !content.isJsonNull) {
                                requireResponse(!finished && content.isJsonPrimitive && content.asJsonPrimitive.isString,
                                    ExplanationFailureCategory.Protocol) { "Invalid content in provider stream." }
                                emit(content.asString)
                            }
                            // reasoning_content and other thinking channels are deliberately not read.
                        }
                        val reason = choice.get("finish_reason")
                        if (reason != null && !reason.isJsonNull) {
                            requireResponse(reason.asString == "stop", when (reason.asString) {
                                "content_filter" -> ExplanationFailureCategory.Refusal
                                "length" -> ExplanationFailureCategory.IncompleteResponse
                                else -> ExplanationFailureCategory.ProviderRejected
                            }) { if (reason.asString == "length") "Provider truncated the answer at its token limit. Retry or change model." else "Provider did not complete the answer (${safeReason(reason.asString)})." }
                            finished = true
                        }
                    }
                    while (!source.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8LineStrict(256_000)
                        if (line.isEmpty()) { event(); if (done) break }
                        else if (line.startsWith("data:")) {
                            data.append(line.substring(5).removePrefix(" ")).append('\n')
                            requireResponse(data.length <= 256_000, ExplanationFailureCategory.Protocol) { "Provider stream record exceeded the supported size." }
                        }
                    }
                    if (data.isNotEmpty()) event()
                    requireResponse(finished, ExplanationFailureCategory.IncompleteResponse) { "Connection ended before a terminal completion. The answer is incomplete." }
                    currentCoroutineContext().ensureActive()
                }
            } finally { cancellation.dispose(); call.cancel() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw chatFailure(failure)
        }
    }.flowOn(Dispatchers.IO)

    /** The whole answer text after a validated terminal completion; identical failure semantics. */
    suspend fun complete(config: ModelConfig, messages: List<JsonObject>, schema: ChatSchema): String {
        val content = StringBuilder()
        stream(config, messages, schema).collect { content.append(it) }
        return content.toString()
    }

    private fun request(config: ModelConfig, messages: List<JsonObject>, schema: ChatSchema): Request {
        requireResponse(config.apiKey.isNotBlank(), ExplanationFailureCategory.Configuration) { "Add your provider API key in Settings. Offline Dictionary remains available." }
        requireResponse(config.model.isNotBlank(), ExplanationFailureCategory.Configuration) { "Choose a model in Settings." }
        requireResponse(ModelConfig.validTemperature(config.temperature), ExplanationFailureCategory.Configuration) { "Set a temperature between 0 and 2 in Settings." }
        requireResponse(config.apiKey.trim().all { it.code in 33..126 }, ExplanationFailureCategory.Configuration) { "Invalid API key. Check Settings." }
        val base = config.endpoint.trim().toHttpUrlOrNull() ?: failResponse("Enter a valid provider base URL in Settings.", ExplanationFailureCategory.Configuration)
        requireResponse(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null, ExplanationFailureCategory.Configuration) { "Provider URL must not contain credentials, query parameters or a fragment." }
        requireResponse(base.isHttps || base.host == "localhost" || base.host == "127.0.0.1" || base.host == "::1", ExplanationFailureCategory.Configuration) { "Use HTTPS for your provider URL." }
        val path = base.encodedPath.trimEnd('/')
        val endpoint = base.newBuilder().encodedPath(when {
            path.endsWith("/chat/completions") -> path
            path.endsWith("/v1") -> "$path/chat/completions"
            path.isEmpty() -> "/v1/chat/completions"
            else -> "$path/chat/completions"
        }).build()
        val strictSchema = base.host == "api.openai.com"
        val body = JsonObject().apply {
            addProperty("model", config.model.trim()); addProperty("stream", true)
            addProperty("temperature", config.temperature)
            add("messages", JsonArray().apply {
                messages.forEachIndexed { index, message ->
                    val value = message.deepCopy()
                    val content = value.get("content")
                    if (!strictSchema && index == 0 && value.get("role")?.takeIf { it.isJsonPrimitive }?.asString == "system" &&
                        content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString
                    ) {
                        // Non-strict providers honor JSON mode but ignore json_schema, so the schema
                        // travels inside the instruction instead of being lost.
                        value.addProperty("content", content.asString + " Required JSON schema: " + schema.json)
                    }
                    add(value)
                }
            })
            add("response_format", JsonObject().apply {
                addProperty("type", if (strictSchema) "json_schema" else "json_object")
                if (strictSchema) add("json_schema", JsonObject().apply {
                    addProperty("name", schema.name); addProperty("strict", true); add("schema", schema.json)
                })
            })
        }
        return Request.Builder().url(endpoint).header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Accept", "text/event-stream").post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    private fun failHttp(code: Int): Nothing {
        // Only the status is used: an error body is never read, logged or paraphrased, so private
        // provider text cannot reach the UI.
        val category = when (code) {
            401, 403 -> ExplanationFailureCategory.Authentication
            402 -> ExplanationFailureCategory.Balance
            400, 422 -> ExplanationFailureCategory.InvalidRequest
            429 -> ExplanationFailureCategory.Quota
            else -> ExplanationFailureCategory.ProviderRejected
        }
        val message = when (code) {
            401, 403 -> "Provider rejected the API key or access permissions. Check Settings."
            402 -> "Provider reports an insufficient account balance. Add credit with the provider or switch profiles."
            400, 422 -> "Provider rejected the request parameters, model, temperature or JSON output mode. Check compatibility in Settings."
            429 -> "Provider rate or quota limit reached. Retry when available."
            in 500..599 -> "Provider service failed (HTTP $code). Retry later."
            else -> "Provider request failed (HTTP $code)."
        }
        failResponse(message, category)
    }

    private fun safeReason(reason: String) = when (reason) { "content_filter" -> "content filter"; "tool_calls" -> "unexpected tool call"; else -> "unsupported finish reason" }
}

/**
 * Maps any caught failure to an app-authored message and category. Provider text, JSON and
 * exceptions never cross this seam, and a known [ExplanationFailure] keeps its own category.
 */
internal fun chatFailure(failure: Exception): ExplanationFailure {
    val known = failure as? ExplanationFailure
    if (known != null && !known.message.isNullOrBlank()) return known
    if (known != null) return ExplanationFailure("Provider response decoding failed. Try again.", known.category)
    val category = when (failure) {
        is EOFException -> ExplanationFailureCategory.IncompleteResponse
        is JsonParseException, is MalformedJsonException, is ClassCastException, is IllegalStateException,
        is IndexOutOfBoundsException, is NoSuchElementException -> ExplanationFailureCategory.MalformedResponse
        is IOException -> ExplanationFailureCategory.Connectivity
        else -> ExplanationFailureCategory.Unknown
    }
    val message = if (category == ExplanationFailureCategory.Connectivity) {
        "Connection failed. Check the provider URL and network, then try again."
    } else {
        "Provider response decoding failed. Try again."
    }
    return ExplanationFailure(message, category)
}
