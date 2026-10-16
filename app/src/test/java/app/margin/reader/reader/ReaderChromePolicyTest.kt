package app.margin.reader.reader

import app.margin.reader.model.ReaderPreferences
import org.junit.Assert.*
import org.junit.Test

class ReaderChromePolicyTest {
    @Test fun `opening is immersive and only center taps toggle controls`() {
        val state = ReaderChromeState()
        assertFalse(state.visible)
        assertEquals(state, state.tap(20f, 1000f))
        assertEquals(state, state.tap(300f, 1000f))
        assertEquals(state, state.tap(700f, 1000f))
        assertEquals(state, state.tap(79f, 200f))
        assertEquals(state, state.tap(980f, 1000f))
        val shown = state.tap(500f, 1000f)
        assertTrue(shown.visible)
        assertFalse(shown.tap(500f, 1000f).visible)
        assertEquals(state, state.tap(0f, 0f))
        assertEquals(state, state.tap(Float.NaN, 1000f))
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
