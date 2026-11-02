package app.orchard.tv.system

import com.xgimi.gmpf.api.DisplayManager
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XgimiBoostTest {
    private val dm = DisplayManager.getInstance()
    private fun supported(value: Boolean) {
        XgimiBoost::class.java.getDeclaredField("supported").also { it.isAccessible = true }.set(null, value)
    }
    @Before fun reset() {
        supported(true)
        dm.lens = 1; dm.level = 10; dm.rejectModeChange = false
        dm.calls.clear()
    }
    @After fun release() { supported(false) }

    @Test fun loweringBrightnessExitsBoostBeforeWritingTheDlpLevel() {
        assertTrue(SystemProbe.setXgimiBrightnessLevel(7))
        assertEquals(listOf("mode:0", "level:7"), dm.calls)
        assertEquals(7, dm.level)
        assertEquals(false, XgimiBoost.enabled())
    }

    @Test fun rejectedExitDoesNotPretendToSetAnIgnoredLevel() {
        dm.rejectModeChange = true
        assertFalse(SystemProbe.setXgimiBrightnessLevel(7))
        assertEquals(listOf("mode:0"), dm.calls)
        assertEquals(10, dm.level)
    }

    @Test fun firstStepToOrdinaryTenExitsBoostDespiteTheUnchangedDlpLevel() {
        assertTrue(SystemProbe.setXgimiBrightnessLevel(10))
        assertEquals(listOf("mode:0", "level:10"), dm.calls)
        assertEquals(10, dm.level)
        assertFalse(XgimiBoost.enabled()!!)
    }

    @Test fun ordinaryTenDoesNotTurnBoostBackOn() {
        dm.lens = 0
        assertTrue(SystemProbe.setXgimiBrightnessLevel(10))
        assertEquals(listOf("level:10"), dm.calls)
        assertFalse(XgimiBoost.enabled()!!)
    }

    @Test fun unsupportedAndUnknownModesAreNotReinterpretedAsBoost() {
        supported(false)
        assertNull(XgimiBoost.enabled())
        assertFalse(XgimiBoost.disable())
        supported(true); dm.lens = 7
        assertNull(XgimiBoost.enabled())
        assertFalse(XgimiBoost.disable())
        assertTrue(dm.calls.isEmpty())
    }

    @Test fun unsupportedFirmwareKeepsOrdinaryBrightnessWrites() {
        supported(false); dm.lens = 0
        assertTrue(SystemProbe.setXgimiBrightnessLevel(7))
        assertEquals(listOf("level:7"), dm.calls)
        assertEquals(7, dm.level)
    }
}
