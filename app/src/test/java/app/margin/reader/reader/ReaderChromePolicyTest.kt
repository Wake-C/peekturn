package app.margin.reader.reader

import app.margin.reader.model.ReaderPreferences
import org.junit.Assert.*
import org.junit.Test

class ReaderChromePolicyTest {
    @Test fun `opening is immersive and only center taps toggle controls`() {
        val state = ReaderChromeState()
        assertFalse(state.visible)
        assertEquals(ReaderTapAction.PreviousPage, readerTapAction(20f, 1000f))
        assertEquals(ReaderTapAction.PreviousPage, readerTapAction(300f, 1000f))
        assertEquals(ReaderTapAction.ToggleChrome, readerTapAction(500f, 1000f))
        assertEquals(ReaderTapAction.NextPage, readerTapAction(700f, 1000f))
        assertEquals(ReaderTapAction.NextPage, readerTapAction(980f, 1000f))
        assertEquals(ReaderTapAction.PreviousPage, readerTapAction(79f, 200f))
        assertEquals(ReaderTapAction.Ignore, readerTapAction(0f, 0f))
        assertEquals(ReaderTapAction.Ignore, readerTapAction(Float.NaN, 1000f))
    }

    @Test fun `center zone never pages while edges keep the native zones`() {
        val width = 1080f
        for (x in listOf(325f, 540f, 755f)) {
            assertEquals(ReaderTapAction.ToggleChrome, readerTapAction(x, width))
        }
        assertEquals(ReaderTapAction.ToggleChrome, readerTapAction(width / 2f, width))
        assertEquals(ReaderTapAction.PreviousPage, readerTapAction(width * 0.29f, width))
        assertEquals(ReaderTapAction.NextPage, readerTapAction(width * 0.71f, width))
    }

    @Test fun `chrome surfaces are overlays chosen only by chrome and narration state`() {
        val hidden = ReaderChromeState()
        val shown = ReaderChromeState(visible = true)
        assertFalse(readerMenuVisible(hidden))
        assertTrue(readerMenuVisible(shown))
        assertFalse(readerNarrationCardVisible(hidden, narrationActive = true))
        assertTrue(readerNarrationCardVisible(shown, narrationActive = true))
        assertFalse(readerNarrationCardVisible(shown, narrationActive = false))
        assertTrue(readerCompactTransportVisible(hidden, narrationActive = true))
        assertFalse(readerCompactTransportVisible(shown, narrationActive = true))
        assertFalse(readerCompactTransportVisible(hidden, narrationActive = false))
    }

    @Test fun `search back returns to the contents sheet and other panels close`() {
        assertEquals("contents", readerPanelBackTarget("search"))
        assertNull(readerPanelBackTarget("contents"))
        assertNull(readerPanelBackTarget("typography"))
        assertNull(readerPanelBackTarget(null))
    }

    @Test fun `back dismisses lookup and panel before controls then exits`() {
        val state = ReaderChromeState(visible = true)
        assertEquals(ReaderBackAction.DismissLookup, state.back(hasLookup = true, hasPanel = true))
        assertEquals(ReaderBackAction.DismissPanel, state.back(hasLookup = false, hasPanel = true))
        assertEquals(ReaderBackAction.HideChrome, state.back(false, false))
        assertEquals(ReaderBackAction.Exit, ReaderChromeState().back(false, false))
    }

    @Test fun `one immersive preference controls both bars independently of overlays and app theme controls contrast`() {
        val defaults = ReaderPreferences()
        assertTrue(defaults.immersive)
        assertEquals(ReaderSystemBars(false, false, false), readerSystemBars(defaults, "dark"))
        assertEquals(ReaderSystemBars(false, false, true), readerSystemBars(defaults, "warm"))
        assertEquals(ReaderSystemBars(false, false, true), readerSystemBars(defaults, "light"))
        assertEquals(ReaderSystemBars(true, true, false), readerSystemBars(defaults.copy(immersive = false), "dark"))
        assertEquals(ReaderSystemBars(true, true, true), readerSystemBars(defaults.copy(immersive = false), "light"))
    }
}
