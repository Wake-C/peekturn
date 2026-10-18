package app.margin.reader.learning

import app.margin.reader.model.ExplanationExample
import app.margin.reader.model.LookupContext
import org.junit.Assert.*
import org.junit.Test

class ExplanationParserTest {
    private val answer = """{"term":"turn down","partOfSpeech":"phrasal verb","ukIpa":"","usIpa":"","meaning":"Refuse an offer.","context":"Here the person refuses.","examples":[{"text":"She turned down the offer.","translation":""},{"text":"He turned it down.","translation":""}]}"""
    private val chineseAnswer = """{"term":"turn down","partOfSpeech":"phrasal verb","ukIpa":"/tɜːn daʊn/","usIpa":"/tɝːn daʊn/","meaning":"拒绝","context":"这里表示拒绝某个提议。","examples":[{"text":"She turned down the offer.","translation":"她拒绝了那个提议。"},{"text":"He turned it down.","translation":"他拒绝了它。"}]}"""

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
        val completedExamples = listOf(
            ExplanationExample("She turned down the offer."),
            ExplanationExample("He turned it down.")
        )
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
            answer.replace(Regex("\"examples\":\\[.*]}"), "\"examples\":[]}"),
            answer + " trailing", answer.replace("\"usIpa\":\"\"", "\"usIpa\":null"),
            answer.replace("\"term\":\"turn down\"", "\"term\":\"turn down\",\"other\":\"x\""),
            answer.replace("\"meaning\":\"Refuse an offer.\"", "\"meaning\":\"\"")
        )
        invalid.forEach { input ->
            assertThrows(Exception::class.java) { ExplanationParser().apply { append(input) }.finish() }
        }
    }

    @Test fun parserSeparatesIncompleteFromMalformedAnswers() {
        val incomplete = assertThrows(ExplanationFailure::class.java) {
            ExplanationParser().apply { append(answer.dropLast(1)) }.finish()
        }
        assertEquals(ExplanationFailureCategory.IncompleteResponse, incomplete.category)
        val malformed = assertThrows(ExplanationFailure::class.java) {
            ExplanationParser().apply { append(answer.replace("\"meaning\":\"Refuse an offer.\"", "\"meaning\":17")) }
        }
        assertEquals(ExplanationFailureCategory.MalformedResponse, malformed.category)
    }

    @Test fun repeatedRootExamplesAreRejected() {
        // Each array holds one complete example, so only the repeated root key makes this invalid.
        // The parser compares against its root key set rather than the values already stored.
        val duplicate = answer.replace(
            ""","examples":[{"text":"She turned down the offer.","translation":""},{"text":"He turned it down.","translation":""}]}""",
            ""","examples":[{"text":"She turned down the offer.","translation":""}],"examples":[{"text":"He turned it down.","translation":""}]}"""
        )
        assertNotEquals(answer, duplicate)
        assertEquals(2, Regex("\"examples\"").findAll(duplicate).count())
        assertThrows(IllegalArgumentException::class.java) {
            ExplanationParser().apply { append(duplicate) }.finish()
        }
    }

    @Test fun aRejectedThirdExampleNeverEntersTheInvalidSnapshot() {
        val third = """{"text":"They turned it off.","translation":""}"""
        val overlong = answer.replace(
            """{"text":"He turned it down.","translation":""}]}""",
            """{"text":"He turned it down.","translation":""},$third]}"""
        )
        val parser = ExplanationParser()
        val publishedThird = mutableListOf<ExplanationExample>()
        var rejected = false
        overlong.forEach { character ->
            try {
                publishedThird += parser.append(character.toString()).examples.filter { it.text == "They turned it off." }
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
        }
        assertTrue("A third example must terminate the answer", rejected)
        assertTrue("No chunk may publish the third example", publishedThird.isEmpty())
        // The client publishes this snapshot when append throws, so it must only contain validated
        // examples and must never grow past the schema's maximum.
        val snapshot = parser.snapshot()
        assertEquals(2, snapshot.examples.size)
        assertEquals(
            listOf("She turned down the offer.", "He turned it down."),
            snapshot.examples.map(ExplanationExample::text)
        )
    }

    @Test fun incompleteExampleIsNeverPublished() {
        val parser = ExplanationParser()
        val partial = parser.append(answer.substringBefore("He turned") + "He")
        assertEquals(listOf(ExplanationExample("She turned down the offer.")), partial.examples)
        assertThrows(IllegalArgumentException::class.java) { parser.finish() }
    }

    @Test fun chineseAnswerPublishesEachEnglishExampleOnlyTogetherWithItsChineseTranslation() {
        val expected = listOf(
            ExplanationExample("She turned down the offer.", "她拒绝了那个提议。"),
            ExplanationExample("He turned it down.", "他拒绝了它。")
        )
        val parser = ExplanationParser(ExplanationLanguage.Chinese)
        chineseAnswer.forEach { character ->
            val partial = parser.append(character.toString())
            // A streamed example is never visible as English text without its translation, and
            // every published translation belongs to a complete English example.
            partial.examples.forEach { example ->
                assertTrue(example.text in expected.map(ExplanationExample::text))
                assertTrue(example.translation in expected.map(ExplanationExample::translation))
                assertTrue(example.text.isNotBlank())
                assertTrue(example.translation.isNotBlank())
            }
        }
        val finished = parser.finish()
        assertEquals(expected, finished.examples)
        assertEquals("拒绝", finished.meaning)
        assertEquals("这里表示拒绝某个提议。", finished.context)
        // The source term and both IPA fields are never rewritten by the language policy.
        assertEquals("turn down", finished.term)
        assertEquals("/tɜːn daʊn/", finished.ukIpa)
        assertEquals("/tɝːn daʊn/", finished.usIpa)
    }

    @Test fun chineseAnswerRejectsExamplesWithoutAChineseTranslation() {
        val invalid = listOf(
            chineseAnswer.replace("\"translation\":\"她拒绝了那个提议。\"", "\"translation\":\"\""),
            chineseAnswer.replace(",\"translation\":\"她拒绝了那个提议。\"", ""),
            chineseAnswer.replace("\"translation\":\"她拒绝了那个提议。\"", "\"translation\":\"   \"")
        )
        invalid.forEach { input ->
            val parser = ExplanationParser(ExplanationLanguage.Chinese)
            assertThrows(IllegalArgumentException::class.java) { parser.append(input).also { parser.finish() } }
        }
    }

    @Test fun englishAnswerKeepsTheEnglishLearningExperienceWithoutATranslation() {
        val finished = ExplanationParser(ExplanationLanguage.English).apply { append(answer) }.finish()
        assertEquals(
            listOf(ExplanationExample("She turned down the offer."), ExplanationExample("He turned it down.")),
            finished.examples
        )
    }

    @Test fun chineseAnswerStillRejectsMalformedExampleObjects() {
        val valid = "\"text\":\"She turned down the offer.\",\"translation\":\"她拒绝了那个提议。\""
        val invalid = listOf(
            chineseAnswer.replace(valid, "\"text\":\"She turned down the offer.\""),
            chineseAnswer.replace(valid, "\"text\":\"\",\"translation\":\"她拒绝了那个提议。\""),
            chineseAnswer.replace(valid, "\"translation\":\"她拒绝了那个提议。\",\"text\":\"She turned down the offer.\",\"extra\":\"x\"")
        )
        invalid.forEach { input ->
            val parser = ExplanationParser(ExplanationLanguage.Chinese)
            assertThrows(IllegalArgumentException::class.java) { parser.append(input).also { parser.finish() } }
        }
    }
}
