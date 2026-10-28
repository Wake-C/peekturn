package app.peekturn.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultThemeTest {
    @Test fun defaultAndUnknownAppearanceUseTheWhiteScheme() {
        val white = peekturnColorScheme("light")
        assertEquals(white, peekturnColorScheme())
        assertEquals(white, peekturnColorScheme("unknown"))
        assertFalse(isDarkTheme("light"))
        assertFalse(isDarkTheme("unknown"))
    }

    @Test fun explicitDarkAppearanceStillUsesItsOwnScheme() {
        assertNotEquals(peekturnColorScheme("light"), peekturnColorScheme("dark"))
        assertTrue(isDarkTheme("dark"))
    }
}
