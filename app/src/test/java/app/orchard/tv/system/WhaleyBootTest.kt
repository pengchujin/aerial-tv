package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleyBootTest {
    private fun ports(vararg state: Pair<Boolean, Int>) =
        state.mapIndexed { i, (connected, cec) -> WhaleyBoot.HdmiPort(i + 1, connected, cec) }

    @Test fun sourceIdsMapToPortsAndBack() {
        assertEquals(1, WhaleyBoot.hdmiPort("whaley:4"))
        assertEquals(2, WhaleyBoot.hdmiPort("whaley:5"))
        assertEquals(3, WhaleyBoot.hdmiPort("whaley:6"))
        assertEquals("whaley:5", WhaleyBoot.sourceId(2))
    }

    @Test fun otherSourcesAreNotScanned() {
        assertNull(WhaleyBoot.hdmiPort("whaley:7")) // AV
        assertNull(WhaleyBoot.hdmiPort("tv:HW5"))
        assertNull(WhaleyBoot.hdmiPort(null))
        assertNull(WhaleyBoot.hdmiPort("whaley:x"))
    }

    @Test fun staysOnHomeWhenNoPortHasADevice() {
        assertNull(WhaleyBoot.choose(2, ports(false to 0, false to 0, false to 0)))
    }

    @Test fun chosenPortWinsWhenOccupied() {
        assertEquals(2, WhaleyBoot.choose(2, ports(true to 0, false to 1, false to 0)))
    }

    @Test fun fallsBackToTheLowestOccupiedPort() {
        assertEquals(3, WhaleyBoot.choose(2, ports(false to 0, false to 0, true to 0)))
        assertEquals(1, WhaleyBoot.choose(2, ports(false to 2, false to 0, true to 0)))
    }

    @Test fun aConsoleAnsweringCecInStandbyCounts() {
        assertEquals(2, WhaleyBoot.choose(2, ports(false to 0, false to 1, false to 0)))
    }

    @Test fun scanStopsEarlyOnlyForTheChosenPort() {
        assertTrue(WhaleyBoot.scanDone(2, ports(false to 0, true to 0, false to 0), elapsedMs = 0))
        assertFalse(WhaleyBoot.scanDone(2, ports(true to 0, false to 0, false to 0), elapsedMs = 1_000))
        assertTrue(WhaleyBoot.scanDone(2, ports(false to 0, false to 0, false to 0), WhaleyBoot.SCAN_MS))
    }

    @Test fun powerLightIsHandledOncePerBoot() {
        assertTrue(WhaleyBoot.newBoot("b1", null))
        assertTrue(WhaleyBoot.newBoot("b2", "b1"))
        assertFalse(WhaleyBoot.newBoot("b1", "b1"))
        assertFalse(WhaleyBoot.newBoot(null, null))
    }
}
