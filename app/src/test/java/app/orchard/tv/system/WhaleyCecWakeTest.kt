package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhaleyCecWakeTest {
    private val off = listOf(false, false, false)
    private val h1 = listOf(true, false, false)

    @Test fun portComingUpThenCecEventSwitches() {
        // As on WTV55K1J: HDMI 1 connected at 17.3 s, CEC status changed at 18.6 s.
        val w = WhaleyCecWake()
        assertNull(w.onPorts(0, off))
        assertNull(w.onPorts(17_300, h1))
        assertEquals(1, w.onCec(18_600))
        assertNull(w.onCec(18_700)) // once only
    }

    @Test fun cecEventThenPortComingUpSwitches() {
        val w = WhaleyCecWake()
        w.onPorts(0, off)
        assertNull(w.onCec(1_000))
        assertEquals(1, w.onPorts(2_000, h1))
    }

    @Test fun aPortAlreadyUpAtTheFirstLookIsNotARise() {
        val w = WhaleyCecWake()
        assertNull(w.onPorts(0, h1))
        assertNull(w.onCec(500))
    }

    @Test fun noCecEventNoSwitch() {
        // A console's maintenance wake, or a box on a power strip: the cable comes up, nobody asked for the picture.
        val w = WhaleyCecWake()
        w.onPorts(0, off)
        assertNull(w.onPorts(1_000, h1))
        assertNull(w.onPorts(30_000, h1))
    }

    @Test fun eventsTooFarApartDoNotSwitch() {
        val w = WhaleyCecWake()
        w.onPorts(0, off)
        w.onPorts(1_000, h1)
        assertNull(w.onCec(1_000 + WhaleyCecWake.WINDOW_MS + 1))
    }

    @Test fun goingToStandbyNeverSwitches() {
        val w = WhaleyCecWake()
        w.onPorts(0, h1)
        assertNull(w.onPorts(10_000, off))
        assertNull(w.onCec(10_500))
    }

    @Test fun theSecondOfTwoDevicesIsTheOneThatCameUp() {
        val w = WhaleyCecWake()
        w.onPorts(0, h1)
        w.onPorts(1_000, listOf(true, false, true))
        assertEquals(3, w.onCec(1_500))
    }
}
