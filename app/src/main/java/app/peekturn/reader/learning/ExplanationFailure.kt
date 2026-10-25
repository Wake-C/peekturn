package app.peekturn.reader.learning

/**
 * App-owned failure kinds. The surface localizes one of these instead of translating provider text,
 * so every language keeps the same actionable distinctions.
 *
 * [Balance] is a payment or credit failure (HTTP 402), [InvalidRequest] a rejected parameter set
 * (HTTP 400/422), and [Protocol] a chat-completions streaming or envelope violation.
 * [MalformedResponse] stays reserved for answer content this app cannot decode.
 */
enum class ExplanationFailureCategory {
    Configuration,
    Authentication,
    Balance,
    Quota,
    InvalidRequest,
    Connectivity,
    Protocol,
    MalformedResponse,
    IncompleteResponse,
    Refusal,
    ProviderRejected,
    Unknown
}

/** Only app-authored messages may cross into UI; transport/JSON exceptions can contain private data. */
internal class ExplanationFailure(
    message: String,
    val category: ExplanationFailureCategory = ExplanationFailureCategory.MalformedResponse
) : IllegalArgumentException(message)

internal inline fun requireResponse(
    condition: Boolean,
    category: ExplanationFailureCategory = ExplanationFailureCategory.MalformedResponse,
    message: () -> String
) {
    if (!condition) throw ExplanationFailure(message(), category)
}

internal fun failResponse(
    message: String,
    category: ExplanationFailureCategory = ExplanationFailureCategory.MalformedResponse
): Nothing = throw ExplanationFailure(message, category)
