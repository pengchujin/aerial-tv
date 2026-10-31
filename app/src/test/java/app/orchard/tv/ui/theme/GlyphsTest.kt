package app.orchard.tv.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlyphsTest {
    @Test fun `a full Wi-Fi signal has nothing dimmed behind it`() {
        assertNull(wifiBackdrop(Glyph.Wifi, 1f))
        assertNull(wifiBackdrop(Glyph.Wifi, 0.67f))
    }

    @Test fun `a weaker Wi-Fi signal keeps the full set of bars behind it`() {
        for (level in listOf(0f, 0.33f, 0.5f, 0.66f)) assertEquals("level $level", GlyphPaths.WIFI, wifiBackdrop(Glyph.Wifi, level))
    }

    @Test fun `only Wi-Fi has a backdrop`() {
        assertNull(wifiBackdrop(Glyph.Ethernet, 0f))
        assertNull(wifiBackdrop(Glyph.Offline, 0f))
        assertNull(wifiBackdrop(Glyph.Speaker, 0f))
    }
}
