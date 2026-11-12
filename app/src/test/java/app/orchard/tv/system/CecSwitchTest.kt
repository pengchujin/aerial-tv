package app.orchard.tv.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CecSwitchTest {
    private val ids = listOf(
        "com.droidlogic.tvinput/.services.Hdmi1InputService/HW5",
        "com.droidlogic.tvinput/.services.Hdmi2InputService/HW6",
    )

    @Test fun anInputIdAmongTheTextsIsTheOne() {
        assertEquals(ids[1], CecSwitch.pick(ids, listOf("HDMI2", ids[1])))
    }

    @Test fun aCecDeviceGoesToThePortItIsUnder() {
        val device = "com.droidlogic.tvinput/.services.Hdmi2InputService/HDMI100004"
        assertEquals(ids[1], CecSwitch.pick(ids, listOf(device)) { if (it == device) ids[1] else null })
    }

    @Test fun nothingKnownIsNothing() {
        assertNull(CecSwitch.pick(ids, listOf("HDMI2", "NintendoSwitch")))
        assertNull(CecSwitch.pick(ids, emptyList()))
    }
}
