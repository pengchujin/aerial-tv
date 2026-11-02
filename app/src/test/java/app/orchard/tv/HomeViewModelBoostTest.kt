package app.orchard.tv

import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModelStore
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.XgimiBoost
import com.xgimi.gmpf.api.DisplayManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeViewModelBoostTest {
    private val store = ViewModelStore()
    private lateinit var vm: HomeViewModel

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        AerialSettings.init(app)
        XgimiBoost::class.java.getDeclaredField("supported").also { it.isAccessible = true }.set(null, true)
        DisplayManager.getInstance().apply {
            lens = 0; level = 7; automatic = true; rejectModeChange = false; calls.clear()
        }
        vm = HomeViewModel(app)
        store.put("brightness", vm)
        // The current values normally arrive from the asynchronous hardware poll.
        ReflectionHelpers.getField<MutableState<Float?>>(vm, "brightness\$delegate").value = 0.7f
        ReflectionHelpers.getField<MutableState<Boolean?>>(vm, "brightnessBoost\$delegate").value = false
    }

    @After fun cleanup() {
        store.clear()
        XgimiBoost::class.java.getDeclaredField("supported").also { it.isAccessible = true }.set(null, false)
    }

    @Test fun refusedEnableAndRepeatedClickKeepTheDisplayedOrdinaryLevel() {
        vm.toggleBrightnessBoost()
        assertEquals(0.7f, vm.brightness!!, 0f)
        assertFalse(vm.brightnessBoost!!)
        vm.toggleBrightnessBoost()
        assertEquals(0.7f, vm.brightness!!, 0f)
        assertFalse(vm.brightnessBoost!!)
    }

    @Test fun leftAfterAnEnableRequestStepsDownFromTheRealLevel() {
        vm.toggleBrightnessBoost()
        vm.adjustBrightness(-1)
        assertEquals(0.6f, vm.brightness!!, 0f)
        assertFalse(vm.brightnessBoost!!)
    }

    @Test fun disablingBoostKeepsTheOrdinaryLevelUntilConfirmed() {
        DisplayManager.getInstance().lens = 1
        ReflectionHelpers.getField<MutableState<Boolean?>>(vm, "brightnessBoost\$delegate").value = true
        vm.toggleBrightnessBoost()
        assertEquals(0.7f, vm.brightness!!, 0f)
        assertTrue(vm.brightnessBoost!!)
    }

    @Test fun leftFromConfirmedBoostSelectsOrdinaryMaximum() {
        DisplayManager.getInstance().lens = 1
        ReflectionHelpers.getField<MutableState<Boolean?>>(vm, "brightnessBoost\$delegate").value = true
        vm.adjustBrightness(-1)
        assertEquals(1f, vm.brightness!!, 0f)
        assertFalse(vm.brightnessBoost!!)
    }
}
