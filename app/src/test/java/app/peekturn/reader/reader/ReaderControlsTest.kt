package app.peekturn.reader.reader

import app.peekturn.reader.model.AppSettings
import app.peekturn.reader.model.ReaderPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two sheets own separate settings. Typography must never carry immersive/scroll/lookup
 * behaviour, and options must never rewrite the reader's type.
 */
class ReaderControlsTest {
    @Test fun `options change immersive scroll and lookup behaviour without touching typography`() {
        val typography = ReaderPreferences(
            fontFamily = "noto-serif",
            fontSize = 142.0,
            letterSpacing = 0.08,
            lineHeight = 1.9,
            paragraphSpacing = 1.2,
            pageMargins = 1.4,
            topPaddingDp = 50.0,
            bottomPaddingDp = 70.0,
            immersive = true,
            scroll = false
        )
        val settings = AppSettings(quickExplain = false, autoPronounce = false, reading = typography)
        assertEquals(
            ReaderOptions(immersive = true, scroll = false, quickExplain = false, autoPronounce = false),
            settings.readerOptions()
        )

        val changed = settings
            .withReaderOption(ReaderOption.IMMERSIVE, false)
            .withReaderOption(ReaderOption.SCROLL, true)
            .withReaderOption(ReaderOption.QUICK_EXPLAIN, true)
            .withReaderOption(ReaderOption.AUTO_PRONOUNCE, true)

        assertEquals(typography.copy(immersive = false, scroll = true), changed.reading)
        assertTrue(changed.quickExplain)
        assertTrue(changed.autoPronounce)
        assertEquals("noto-serif", changed.reading.fontFamily)
        assertEquals(0.08, changed.reading.letterSpacing, 0.0)
        assertEquals(142.0, changed.reading.fontSize, 0.0)
    }

    @Test fun `typography cannot rewrite immersive scroll or lookup behaviour`() {
        val settings = AppSettings(
            quickExplain = true,
            autoPronounce = true,
            reading = ReaderPreferences(immersive = false, scroll = true)
        )
        // The sheet used to share one draft with behaviour switches, so a typography apply could
        // silently re-enable immersive reading or stop continuous scrolling.
        val staleDraft = settings.reading.copy(
            fontFamily = "source-serif-4",
            fontSize = 160.0,
            letterSpacing = 0.1,
            immersive = true,
            scroll = false
        )

        val applied = settings.withTypography(staleDraft)

        assertFalse(applied.reading.immersive)
        assertTrue(applied.reading.scroll)
        assertTrue(applied.quickExplain)
        assertTrue(applied.autoPronounce)
        assertEquals("source-serif-4", applied.reading.fontFamily)
        assertEquals(160.0, applied.reading.fontSize, 0.0)
        assertEquals(0.1, applied.reading.letterSpacing, 0.0)
        assertNotEquals(settings.reading, applied.reading)
    }

    @Test fun `queued options toggles from one stale snapshot preserve both`() {
        val settings = AppSettings(
            quickExplain = false,
            autoPronounce = false,
            reading = ReaderPreferences(fontFamily = "noto-serif", immersive = false, scroll = false)
        )
        // Both taps were rendered from this snapshot. Before the fix each queued the whole
        // options object, so the second tap restored immersive=false from the stale UI state.
        val staleOptions = settings.readerOptions()
        assertFalse(staleOptions.immersive)
        assertFalse(staleOptions.scroll)

        val queued: List<(AppSettings) -> AppSettings> = listOf(
            { latest -> latest.withReaderOption(ReaderOption.IMMERSIVE, true) },
            { latest -> latest.withReaderOption(ReaderOption.SCROLL, true) }
        )
        val persisted = queued.fold(settings) { latest, change -> change(latest) }

        assertTrue(persisted.reading.immersive)
        assertTrue(persisted.reading.scroll)
        assertEquals("noto-serif", persisted.reading.fontFamily)
        assertFalse(persisted.quickExplain)
        assertFalse(persisted.autoPronounce)
    }

    @Test fun `bottom tools are contents typography features and read aloud`() {
        // Search moved into the contents header, so the fourth bottom entry starts narration
        // instead of opening another surface.
        assertEquals(
            listOf(
                ReaderToolbarEntry.CONTENTS,
                ReaderToolbarEntry.TYPOGRAPHY,
                ReaderToolbarEntry.OPTIONS,
                ReaderToolbarEntry.READ_ALOUD
            ),
            readerToolbarEntries
        )
    }

    @Test fun `queued options and typography changes stay isolated`() {
        val settings = AppSettings(
            quickExplain = false,
            autoPronounce = true,
            reading = ReaderPreferences(fontFamily = "noto-serif", immersive = true, scroll = false)
        )
        // The draft still carries the behaviour values from when the sheet opened.
        val staleTypographyDraft = settings.reading.copy(
            fontFamily = "source-serif-4",
            fontSize = 160.0,
            immersive = true,
            scroll = false
        )

        val queued: List<(AppSettings) -> AppSettings> = listOf(
            { latest -> latest.withReaderOption(ReaderOption.IMMERSIVE, false) },
            { latest -> latest.withTypography(staleTypographyDraft) },
            { latest -> latest.withReaderOption(ReaderOption.SCROLL, true) },
            { latest -> latest.withReaderOption(ReaderOption.QUICK_EXPLAIN, true) }
        )
        val persisted = queued.fold(settings) { latest, change -> change(latest) }

        assertFalse(persisted.reading.immersive)
        assertTrue(persisted.reading.scroll)
        assertTrue(persisted.quickExplain)
        assertTrue(persisted.autoPronounce)
        assertEquals("source-serif-4", persisted.reading.fontFamily)
        assertEquals(160.0, persisted.reading.fontSize, 0.0)
    }
}
