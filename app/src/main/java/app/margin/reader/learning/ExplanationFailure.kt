package app.margin.reader.learning

/**
 * App-owned failure kinds. The surface localizes one of these instead of translating provider text,
 * so every language keeps the same actionable distinctions.
 */
enum class ExplanationFailureCategory {
    Configuration,
    Authentication,
    Quota,
    Connectivity,
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
