package app.peekturn.reader.diagnostics

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canonical metadata redaction and runtime-identifier validation for support diagnostics. */
class DiagnosticTextTest {
    @Test fun metadataRedactsUrlsPathsAndCredentialsAndPreservesOrdinaryFields() {
        val value = "engine=OEM TTS\nvoice=en-us-x-sfg#female_1-local " +
            "url=https://user:secret@tts.example/path?api_key=TOPSECRET " +
            "file=/private/books/secret.epub " +
            "win=C:\\Users\\reader\\secret.epub " +
            "auth=Bearer abc123 " +
            "token=abc123; manufacturer=Acme; version=1.2.3-beta"

        val safe = diagnosticMetadata(value, limit = 1_024)

        assertFalse(safe.contains("TOPSECRET"))
        assertFalse(safe.contains("/private/books"))
        assertFalse(safe.contains("C:\\Users"))
        assertFalse(safe.contains("abc123"))
        assertFalse(safe.contains("secret.epub"))
        assertFalse(safe.contains("\n"))
        assertTrue(safe.contains("engine=OEM TTS"))
        assertTrue(safe.contains("en-us-x-sfg#female_1-local"))
        assertTrue(safe.contains("manufacturer=Acme"))
        assertTrue(safe.contains("version=1.2.3-beta"))
        assertTrue(safe.contains("[redacted-url]"))
        assertTrue(safe.contains("[redacted-path]"))
        assertTrue(safe.contains("token=[redacted]"))
    }

    @Test fun metadataBoundsByUtf8BytesWithAnExplicitMarker() {
        val safe = diagnosticMetadata("x".repeat(1_000), limit = 40)

        assertTrue(safe.endsWith("[truncated]"))
        assertTrue(safe.toByteArray(StandardCharsets.UTF_8).size <= 40)
    }

    @Test fun unicodeFormattingCannotHideUrlsOrForgeMetadataLines() {
        val safe = diagnosticMetadata(
            "voice=ht\u200btps://hidden.invalid/?token=SECRET\u2028next=public",
            limit = 1_024
        )

        assertFalse(safe.contains("hidden.invalid"))
        assertFalse(safe.contains("SECRET"))
        assertFalse(safe.contains('\u200b'))
        assertFalse(safe.contains('\u2028'))
        assertTrue(safe.contains("next=public"))
    }

    @Test fun identifiersAcceptJavaShapesIncludingNestedAndSpecialMethods() {
        assertEquals("com.example.Outer\$Inner", diagnosticIdentifier("com.example.Outer\$Inner"))
        assertEquals("access\$getValue\$p", diagnosticIdentifier("access\$getValue\$p"))
        assertEquals("<init>", diagnosticIdentifier("<init>"))
        assertEquals("<clinit>", diagnosticIdentifier("<clinit>"))
    }

    @Test fun identifiersRejectPayloadShapedFieldsWithAnExplicitMarker() {
        assertEquals(DIAGNOSTIC_INVALID_IDENTIFIER, diagnosticIdentifier("/private/books/secret.epub"))
        assertEquals(DIAGNOSTIC_INVALID_IDENTIFIER, diagnosticIdentifier("method\nname"))
        assertEquals(DIAGNOSTIC_INVALID_IDENTIFIER, diagnosticIdentifier("method name"))
        assertEquals(DIAGNOSTIC_INVALID_IDENTIFIER, diagnosticIdentifier("com.example.Bad/../../secret"))
        assertEquals(DIAGNOSTIC_INVALID_IDENTIFIER, diagnosticIdentifier("x".repeat(300)))
        assertEquals(DIAGNOSTIC_INVALID_IDENTIFIER, diagnosticIdentifier("a..b"))
    }
}
