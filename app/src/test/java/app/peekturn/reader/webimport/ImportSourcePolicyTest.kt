package app.peekturn.reader.webimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSourcePolicyTest {
    @Test fun `only http links reach the browser`() {
        assertEquals("https://openai.com/index/", normalizeHttpUrl("  https://openai.com/index/ "))
        assertNull(normalizeHttpUrl("http://example.com/a"))
        assertNull(normalizeHttpUrl("javascript:alert(1)"))
        assertNull(normalizeHttpUrl("file:///etc/passwd"))
        assertNull(normalizeHttpUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(normalizeHttpUrl("openai.com"))
        assertNull(normalizeHttpUrl(null))
    }

    @Test fun `shared text yields its first link`() {
        assertEquals(
            "https://x.com/paulg/status/1?s=20",
            firstHttpUrl("Look at this thread https://x.com/paulg/status/1?s=20 — worth reading")
        )
        assertEquals("https://example.com/a", firstHttpUrl("看这个 https://example.com/a，然后告诉我"))
        assertNull(firstHttpUrl("no link here"))
        assertNull(firstHttpUrl(null))
    }

    @Test fun `a direct fetch is accepted only for a real article`() {
        assertTrue(acceptsDirectHtml(200, "text/html; charset=utf-8", 20_000, "<html><body>Article"))
        assertFalse(acceptsDirectHtml(403, "text/html", 20_000, "<html><body>Blocked"))
        assertFalse(acceptsDirectHtml(200, "text/plain", 20_000, "not html"))
        assertFalse(acceptsDirectHtml(200, "text/html", 900, "<html><body>tiny"))
        assertFalse(acceptsDirectHtml(200, "text/html", 20_000, "<html><title>Just a moment...</title>"))
        assertFalse(acceptsDirectHtml(200, "text/html", 20_000, "<html>Enable JavaScript and cookies to continue"))
    }
}
