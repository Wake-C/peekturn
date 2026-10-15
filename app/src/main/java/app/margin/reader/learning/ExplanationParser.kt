package app.margin.reader.learning

import app.margin.reader.model.Explanation
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader

/** A small incremental parser for the fixed answer schema. Never publishes an unfinished string. */
internal class ExplanationParser {
    private val text = StringBuilder()
    private val fields = linkedMapOf<String, String>()
    private val examples = mutableListOf<String>()
    private var position = 0
    private var phase = 0
    private var key = ""
    private var inExamples = false
    private var needsValue = false
    private var ended = false
    private val keys = setOf("term", "partOfSpeech", "ukIpa", "usIpa", "meaning", "context", "examples")

    fun append(fragment: String): Explanation {
        requireResponse(text.length + fragment.length <= 128_000) { "Answer exceeded the supported size." }
        text.append(fragment)
        parse()
        return answer()
    }

    /** Completed validated values remain useful even if a later token in the same chunk fails. */
    fun snapshot(): Explanation = answer()

    fun finish(): Explanation {
        parse()
        requireResponse(ended && position == text.length) { "The provider returned incomplete JSON." }
        requireResponse(fields.keys == keys - "examples" && examples.size in 1..2) { "The answer is missing required fields or examples." }
        requireResponse(listOf("term", "partOfSpeech", "meaning", "context").all { !fields[it].isNullOrBlank() }) { "The answer contains empty required fields." }
        return answer()
    }

    private fun answer() = Explanation(fields["term"].orEmpty(), fields["partOfSpeech"].orEmpty(), fields["ukIpa"].orEmpty(), fields["usIpa"].orEmpty(), fields["meaning"].orEmpty(), fields["context"].orEmpty(), examples.toList())

    private fun whitespace() { while (position < text.length && text[position] in " \r\n\t") position++ }
    private fun string(): String? {
        requireResponse(text[position] == '"') { "Expected a JSON string." }
        var cursor = position + 1
        var escaped = false
        while (cursor < text.length) {
            val character = text[cursor]
            requireResponse(character >= ' ') { "Invalid control character in JSON." }
            if (character == '"' && !escaped) {
                val encoded = text.substring(position, cursor + 1)
                val decoded = JsonReader(StringReader(encoded)).use { reader ->
                    reader.strictness = Strictness.STRICT
                    reader.nextString()
                }
                position = cursor + 1
                return decoded
            }
            escaped = character == '\\' && !escaped
            cursor++
        }
        return null
    }

    private fun parse() {
        while (true) {
            whitespace()
            if (position == text.length) return
            requireResponse(!ended) { "Unexpected content after the answer." }
            val character = text[position]
            when (phase) {
                0 -> { requireResponse(character == '{') { "Expected a JSON object." }; position++; phase = 1 }
                1 -> {
                    // An empty object or trailing comma is not a valid answer.
                    key = string() ?: return
                    requireResponse(key in keys && key !in fields && !(key == "examples" && inExamples)) { "Unexpected or repeated answer field." }
                    phase = 2
                }
                2 -> { requireResponse(character == ':') { "Expected a JSON field separator." }; position++; phase = 3 }
                3 -> if (key == "examples") {
                    requireResponse(character == '[') { "Expected an examples array." }
                    position++; inExamples = true; needsValue = true; phase = 5
                } else {
                    fields[key] = string() ?: return
                    phase = 4
                }
                4 -> when (character) {
                    ',' -> { position++; phase = 1 }
                    '}' -> { position++; ended = true }
                    else -> failResponse("Expected the end of a field.")
                }
                5 -> {
                    if (character == ']' && examples.isEmpty()) { position++; phase = 4 }
                    else if (needsValue) {
                        val example = string() ?: return
                        requireResponse(example.isNotBlank() && examples.size < 2) { "Expected one or two complete examples." }
                        examples += example; needsValue = false
                    } else when (character) {
                        ',' -> { position++; needsValue = true; phase = 6 }
                        ']' -> { position++; phase = 4 }
                        else -> failResponse("Expected the end of an example.")
                    }
                }
                6 -> { phase = 5; requireResponse(character == '"') { "Expected another example." } }
            }
        }
    }
}
