package app.orchard.tv.system

import com.xgimi.gmpf.api.DisplayManager
import com.xgimi.gmpf.api.GmTvManager
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
        dm.automatic = false; dm.format = DisplayManager.EN_XGIMI_3D_NONE
        dm.calls.clear(); GmTvManager.getInstance().imax = false
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

    @Test fun togglingBoostPreservesTheStoredOrdinaryLevel() {
        dm.lens = 0; dm.level = 7
        assertTrue(XgimiBoost.enable())
        assertEquals(7, dm.level)
        assertTrue(XgimiBoost.disable())
        assertEquals(7, dm.level)
        assertEquals(listOf("mode:1", "mode:0"), dm.calls)
        assertFalse(XgimiBoost.enabled()!!)
    }

    @Test fun unsupportedAndUnknownModesAreNotReinterpretedAsBoost() {
        supported(false)
        assertNull(XgimiBoost.enabled())
        assertFalse(XgimiBoost.disable())
        assertFalse(XgimiBoost.enable())
        supported(true); dm.lens = 7
        assertNull(XgimiBoost.enabled())
        assertFalse(XgimiBoost.disable())
        assertFalse(XgimiBoost.enable())
        assertTrue(dm.calls.isEmpty())
    }

    @Test fun unsupportedFirmwareKeepsOrdinaryBrightnessWrites() {
        supported(false); dm.lens = 0
        assertTrue(SystemProbe.setXgimiBrightnessLevel(7))
        assertEquals(listOf("level:7"), dm.calls)
        assertEquals(7, dm.level)
    }

    @Test fun explicitBoostHonorsNativePlaybackAndAmbientRestrictions() {
        dm.lens = 0; dm.level = 7; dm.automatic = true
        assertFalse(XgimiBoost.enable())
        dm.automatic = false; dm.format = 2
        assertFalse(XgimiBoost.enable())
        dm.format = DisplayManager.EN_XGIMI_3D_NONE; GmTvManager.getInstance().imax = true
        assertFalse(XgimiBoost.enable())
        assertTrue(dm.calls.isEmpty())
        GmTvManager.getInstance().imax = false
        assertTrue(XgimiBoost.enable())
        assertEquals(listOf("mode:1"), dm.calls)
        assertEquals(7, dm.level) // Enabling Boost need not change the stored ordinary level.
    }
}
