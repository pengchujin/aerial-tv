package app.orchard.tv.ui.wallpaper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlFitsTest {
    @Test fun `a control that leaves the gap to the name fits`() {
        assertTrue(controlFits(labelWidth = 300, controlWidth = 500, rowWidth = 900, gap = 24))
    }

    @Test fun `exactly as wide as the row leaves room for is still a fit`() {
        assertTrue(controlFits(labelWidth = 300, controlWidth = 576, rowWidth = 900, gap = 24))
    }

    @Test fun `one pixel more and it collapses`() {
        assertFalse(controlFits(labelWidth = 300, controlWidth = 577, rowWidth = 900, gap = 24))
    }

    @Test fun `a long name leaves less for the control`() {
        assertFalse(controlFits(labelWidth = 600, controlWidth = 500, rowWidth = 900, gap = 24))
    }
}
