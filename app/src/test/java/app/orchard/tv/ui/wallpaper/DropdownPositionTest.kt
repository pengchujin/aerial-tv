package app.orchard.tv.ui.wallpaper

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class DropdownPositionTest {
    private val window = IntSize(1920, 1080)

    @Test fun `the list opens under the value, its right edge on the value's`() {
        val at = dropdownPosition(IntRect(1500, 400, 1700, 440), window, IntSize(300, 250))
        assertEquals(IntOffset(1400, 446), at)
    }

    @Test fun `with no room below, it opens above the value`() {
        val at = dropdownPosition(IntRect(1500, 900, 1700, 940), window, IntSize(300, 250))
        assertEquals(IntOffset(1400, 644), at)
    }

    @Test fun `it does not leave the window sideways`() {
        assertEquals(0, dropdownPosition(IntRect(20, 400, 120, 440), window, IntSize(300, 250)).x)
        assertEquals(1620, dropdownPosition(IntRect(1900, 400, 2100, 440), window, IntSize(300, 250)).x)
    }

    @Test fun `it does not leave the window upward`() {
        assertEquals(0, dropdownPosition(IntRect(1500, 100, 1700, 140), window, IntSize(300, 1200)).y)
    }
}
