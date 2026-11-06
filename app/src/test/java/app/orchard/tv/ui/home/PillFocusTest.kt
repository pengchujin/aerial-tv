package app.orchard.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PillFocusTest {
    @Test fun directionsReachExistingTilesOnTheFirstRow() {
        assertEquals(0, pillTile(8, 5, true))
        assertEquals(4, pillTile(8, 5, false))
        assertEquals(1, pillTile(2, 5, false))
        assertEquals(0, pillTile(1, 5, false))
        assertNull(pillTile(0, 5, true))
        assertNull(pillTile(3, 0, false))
    }
}
