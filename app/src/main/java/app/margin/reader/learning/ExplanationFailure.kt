package app.margin.reader.learning

/** Only app-authored messages may cross into UI; transport/JSON exceptions can contain private data. */
internal class ExplanationFailure(message: String) : IllegalArgumentException(message)

internal inline fun requireResponse(condition: Boolean, message: () -> String) {
    if (!condition) throw ExplanationFailure(message())
}

internal fun failResponse(message: String): Nothing = throw ExplanationFailure(message)
