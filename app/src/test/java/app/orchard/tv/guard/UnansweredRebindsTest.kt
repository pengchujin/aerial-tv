package app.orchard.tv.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnansweredRebindsTest {
    @Test fun wedgesOnlyAtTheLimit() {
        val rebinds = UnansweredRebinds(limit = 2)
        assertFalse(rebinds.unanswered())
        assertFalse(rebinds.wedged)
        assertTrue(rebinds.unanswered())
        assertTrue(rebinds.wedged)
    }

    @Test fun reportsTheWedgeOnce() {
        val rebinds = UnansweredRebinds(limit = 1)
        assertTrue(rebinds.unanswered())
        assertFalse(rebinds.unanswered())
        assertFalse(rebinds.unanswered())
        assertTrue(rebinds.wedged)
        assertEquals(1, rebinds.count)
    }

    @Test fun aBindingResetsTheCount() {
        val rebinds = UnansweredRebinds(limit = 2)
        rebinds.unanswered()
        rebinds.bound()
        assertEquals(0, rebinds.count)
        assertFalse(rebinds.unanswered())
        assertFalse(rebinds.wedged)
    }

    @Test fun aBindingClearsTheWedge() {
        val rebinds = UnansweredRebinds(limit = 2)
        rebinds.unanswered()
        rebinds.unanswered()
        rebinds.bound()
        assertFalse(rebinds.wedged)
        assertFalse(rebinds.unanswered())
        assertTrue(rebinds.unanswered())
    }

    @Test fun onlyAndroid10AndEarlierCanWedge() {
        assertTrue(UnansweredRebinds.canWedge(28))
        assertTrue(UnansweredRebinds.canWedge(29))
        assertFalse(UnansweredRebinds.canWedge(30))
        assertFalse(UnansweredRebinds.canWedge(34))
    }
}
