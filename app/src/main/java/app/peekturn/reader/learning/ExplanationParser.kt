package app.peekturn.reader.learning

import app.peekturn.reader.model.Explanation
import app.peekturn.reader.model.ExplanationExample
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader

/** A small incremental parser for the fixed answer schema. Never publishes an unfinished string. */
internal class ExplanationParser(private val language: ExplanationLanguage = ExplanationLanguage.English) {
    private val text = StringBuilder()
    private val fields = linkedMapOf<String, String>()
    private val seenKeys = mutableSetOf<String>()
    private val examples = mutableListOf<ExplanationExample>()
    private val exampleFields = linkedMapOf<String, String>()
    private var position = 0
    private var phase = 0
    private var key = ""
    private var needsValue = false
    private var ended = false
    private val keys = setOf("term", "partOfSpeech", "ukIpa", "usIpa", "meaning", "context", "examples", "synonyms", "antonyms")
    private val exampleKeys = setOf("text", "translation")

    fun append(fragment: String): Explanation {
        requireResponse(text.length + fragment.length <= EXPLANATION_MAX_RESPONSE_CHARACTERS) { "Answer exceeded the supported size." }
        text.append(fragment)
        parse()
        return answer()
    }

    /** Completed validated values remain useful even if a later token in the same chunk fails. */
    fun snapshot(): Explanation = answer()

    fun finish(): Explanation {
        parse()
        requireResponse(ended && position == text.length, ExplanationFailureCategory.IncompleteResponse) { "The provider returned incomplete JSON." }
        requireResponse(fields.keys == keys - "examples") { "The answer is missing required fields." }
        val answer = answer()
        requireResponse(answer.isCompleteFor(language)) { "The answer is missing required fields or examples." }
        return answer
    }

    private fun answer() = Explanation(fields["term"].orEmpty(), fields["partOfSpeech"].orEmpty(), fields["ukIpa"].orEmpty(), fields["usIpa"].orEmpty(), fields["meaning"].orEmpty(), fields["context"].orEmpty(), examples.toList(), fields["synonyms"].orEmpty(), fields["antonyms"].orEmpty())

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

    private fun completeExample() {
        position++
        requireResponse(exampleFields.keys == exampleKeys) { "An example needs both text and translation." }
        val sentence = exampleFields["text"].orEmpty()
        val translation = exampleFields["translation"].orEmpty()
        requireResponse(sentence.isNotBlank()) { "An example sentence is empty." }
        if (language == ExplanationLanguage.Chinese) {
            requireResponse(translation.isNotBlank()) { "Every example needs a Chinese translation." }
        }
        // Validate before mutating: snapshot() is published when append() throws, so an over-limit
        // list must never contain the rejected third example.
        requireResponse(examples.size < 2) { "Expected one or two complete examples." }
        examples += ExplanationExample(sentence, translation)
        needsValue = false
        phase = 5
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
                    requireResponse(key in keys && seenKeys.add(key)) { "Unexpected or repeated answer field." }
                    phase = 2
                }
                2 -> { requireResponse(character == ':') { "Expected a JSON field separator." }; position++; phase = 3 }
                3 -> if (key == "examples") {
                    requireResponse(character == '[') { "Expected an examples array." }
                    position++; needsValue = true; phase = 5
                } else {
                    val value = string() ?: return
                    if (key == "synonyms" || key == "antonyms") {
                        requireResponse(validExplanationRelation(value)) { "Expected a short, single-line word relation." }
                    }
                    fields[key] = value
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
                        requireResponse(character == '{') { "Expected an example object." }
                        position++; exampleFields.clear(); phase = 7
                    } else when (character) {
                        ',' -> { position++; needsValue = true }
                        ']' -> { position++; phase = 4 }
                        else -> failResponse("Expected the end of an example.")
                    }
                }
                7 -> {
                    key = string() ?: return
                    requireResponse(key in exampleKeys && key !in exampleFields) { "Unexpected or repeated example field." }
                    phase = 8
                }
                8 -> { requireResponse(character == ':') { "Expected an example field separator." }; position++; phase = 9 }
                9 -> { exampleFields[key] = string() ?: return; phase = 10 }
                10 -> when (character) {
                    ',' -> { position++; phase = 7 }
                    '}' -> completeExample()
                    else -> failResponse("Expected the end of an example field.")
                }
            }
        }
    }
}
