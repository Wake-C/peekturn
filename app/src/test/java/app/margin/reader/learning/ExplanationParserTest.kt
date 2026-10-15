package app.margin.reader.learning

import app.margin.reader.model.LookupContext
import org.junit.Assert.*
import org.junit.Test

class ExplanationParserTest {
    private val answer = """{"term":"turn down","partOfSpeech":"phrasal verb","ukIpa":"","usIpa":"","meaning":"Refuse an offer.","context":"Here the person refuses.","examples":["She turned down the offer.","He turned it down."]}"""

    @Test fun clipsOnlyAvailableContextAtThirtyWords() {
        val words = (1..45).map { "word$it" }
        val original = LookupContext("word", words.joinToString("\t \n"), words.joinToString("  "), "Title", "Chapter")
        val clipped = clipContext(original)
        assertEquals(words.takeLast(30).joinToString(" "), clipped.before)
        assertEquals(words.take(30).joinToString(" "), clipped.after)
        assertEquals(original.copy(before = clipped.before, after = clipped.after), clipped)
        assertEquals("", clipContext(LookupContext("phrase", " \n", "")).before)
        assertEquals("short snippet", clipContext(LookupContext("phrase", "short snippet")).before)
        val exactlyThirty = words.take(30).joinToString(" ")
        assertEquals(exactlyThirty, clipContext(LookupContext("phrase", exactlyThirty, exactlyThirty)).after)
    }

    @Test fun everyCharacterBoundaryPublishesOnlyClosedFieldsAndExamples() {
        val parser = ExplanationParser()
        val completedExamples = listOf("She turned down the offer.", "He turned it down.")
        answer.forEachIndexed { index, character ->
            val partial = parser.append(character.toString())
            partial.examples.forEach { assertTrue(it in completedExamples) }
            if (index < answer.indexOf("turn down\"") + "turn down".length) assertEquals("", partial.term)
        }
        assertEquals(completedExamples, parser.finish().examples)
    }

    @Test fun handlesEscapedQuotesAndUnicodeAcrossChunks() {
        val parser = ExplanationParser()
        val input = answer.replace("Refuse an offer.", """Say \"no\" to an offer: \u00e9.""")
        // A quoted JSON string can be split at an escape, including a unicode escape.
        input.forEach { parser.append(it.toString()) }
        assertEquals("Say \"no\" to an offer: é.", parser.finish().meaning)
    }

    @Test fun unicodeWhitespaceSeparatesContextWords() {
        val context = clipContext(LookupContext("target", before = "one\u00a0two\u2003three", after = "four\tfive\nsix"))
        assertEquals("one two three", context.before)
        assertEquals("four five six", context.after)
    }

    @Test fun refusesTruncatedAndInvalidAnswers() {
        val invalid = listOf(
            answer.dropLast(1), answer.replace("\"meaning\":\"Refuse an offer.\"", "\"meaning\":17"),
            answer.replace("\"context\":\"Here the person refuses.\",", ""),
            answer.replace("\"term\":\"turn down\"", "\"term\":\"turn down\",\"term\":\"other\""),
            answer.replace("[\"She turned down the offer.\",\"He turned it down.\"]", "[]"),
            answer + " trailing", answer.replace("\"usIpa\":\"\"", "\"usIpa\":null"),
            answer.replace("\"term\":\"turn down\"", "\"term\":\"turn down\",\"other\":\"x\""),
            answer.replace("\"meaning\":\"Refuse an offer.\"", "\"meaning\":\"\"")
        )
        invalid.forEach { input ->
            assertThrows(Exception::class.java) { ExplanationParser().apply { append(input) }.finish() }
        }
    }

    @Test fun incompleteExampleIsNeverPublished() {
        val parser = ExplanationParser()
        val partial = parser.append(answer.substringBefore("He turned") + "He")
        assertEquals(listOf("She turned down the offer."), partial.examples)
        assertThrows(IllegalArgumentException::class.java) { parser.finish() }
    }
}
