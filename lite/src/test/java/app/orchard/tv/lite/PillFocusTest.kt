package app.orchard.tv.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PillFocusTest {
    @Test fun directionsReachExistingTilesOnTheFirstRow() {
        assertNull(pillTile(0, 5, true))
        assertEquals(1, pillTile(2, 5, false))
        assertEquals(4, pillTile(8, 5, false))
        assertEquals(0, pillTile(8, 5, true))
    }
}
