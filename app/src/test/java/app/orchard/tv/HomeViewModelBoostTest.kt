package app.orchard.tv

import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModelStore
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.XgimiBoost
import com.xgimi.gmpf.api.DisplayManager
import com.xgimi.gmpf.api.GmTvManager
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeViewModelBoostTest {
    private val store = ViewModelStore()
    private lateinit var vm: HomeViewModel
    private val display get() = DisplayManager.getInstance()

    @Before fun setup() {
        ShadowToast.reset()
        val app = RuntimeEnvironment.getApplication()
        ReflectionHelpers.setStaticField(AerialSettings::class.java, "started", false)
        AerialSettings.init(app)
        AerialSettings.chooseBrightnessBoostAllowed(false)
        XgimiBoost::class.java.getDeclaredField("supported").also { it.isAccessible = true }.set(null, true)
        display.apply {
            lens = 0; level = 7; automatic = false; rejectModeChange = false
            format = DisplayManager.EN_XGIMI_3D_NONE; beforeModeChange = null; deferLevelWrites = false; calls.clear()
            rejectLevelWrites = false
        }
        GmTvManager.getInstance().imax = false
        vm = HomeViewModel(app)
        store.put("brightness", vm)
        shown(0.7f, false)
    }

    private fun shown(level: Float, boost: Boolean?) {
        ReflectionHelpers.getField<MutableState<Float?>>(vm, "brightness\$delegate").value = level
        ReflectionHelpers.getField<MutableState<Boolean?>>(vm, "brightnessBoost\$delegate").value = boost
    }

    /** Exercise the real queue and worker, not just the optimistic UI fields. */
    private fun awaitBrightness() {
        await { ReflectionHelpers.getField<Any?>(vm, "pendingBrightness") == null }
    }

    private fun await(done: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            ShadowLooper.idleMainLooper()
            if (done()) return
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Brightness worker did not finish")
    }

    private fun atMaximum() {
        display.level = 10
        shown(1f, false)
    }

    @After fun cleanup() {
        display.beforeModeChange = null
        display.deferLevelWrites = false
        display.rejectLevelWrites = false
        store.clear()
        XgimiBoost::class.java.getDeclaredField("supported").also { it.isAccessible = true }.set(null, false)
    }

    @Test fun allowanceToggleAtSeventyNeverWritesHardwareAndPersists() {
        vm.toggleBrightnessBoostAllowed()
        assertTrue(AerialSettings.brightnessBoostAllowed)
        assertEquals(0.7f, vm.brightness!!, 0f)
        assertTrue(display.calls.isEmpty())
        ReflectionHelpers.setStaticField(AerialSettings::class.java, "started", false)
        AerialSettings.init(RuntimeEnvironment.getApplication())
        assertTrue(AerialSettings.brightnessBoostAllowed)
        vm.toggleBrightnessBoostAllowed()
        assertFalse(AerialSettings.brightnessBoostAllowed)
        assertEquals(0.7f, vm.brightness!!, 0f)
        assertEquals(7, display.level)
        assertTrue(display.calls.isEmpty())
    }

    @Test fun enabledRangeStepsBothDirectionsAndKeepsAllowance() {
        vm.toggleBrightnessBoostAllowed()
        for (level in 8..10) {
            vm.adjustBrightness(1); awaitBrightness()
            assertEquals(level, display.level)
            assertEquals(0, display.lens)
        }
        vm.adjustBrightness(1); awaitBrightness()
        assertTrue(vm.brightnessBoost!!)
        assertEquals(1, display.lens)
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(1, display.lens)
        vm.adjustBrightness(-1); awaitBrightness()
        assertEquals(10, display.level)
        assertEquals(0, display.lens)
        assertTrue(AerialSettings.brightnessBoostAllowed)
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(1, display.lens)
        vm.adjustBrightness(-1); awaitBrightness()
        vm.adjustBrightness(-1); awaitBrightness()
        assertEquals(9, display.level)
        assertTrue(AerialSettings.brightnessBoostAllowed)
    }

    @Test fun disabledRangeStopsAtOrdinaryMaximum() {
        atMaximum()
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(10, display.level)
        assertEquals(0, display.lens)
        assertFalse(vm.brightnessBoost!!)
    }

    @Test fun delayedOrdinaryWritesDoNotResetConsecutiveSliderSteps() {
        display.deferLevelWrites = true
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(0.8f, vm.brightness!!, 0f)
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(0.9f, vm.brightness!!, 0f)
        assertEquals(listOf("level:8", "level:9"), display.calls)
        assertEquals(7, display.level)
    }

    @Test fun disablingAtBoostClampsToHundredEvenWhenFirmwareRetainsSeventy() {
        display.lens = 1
        shown(0.7f, true)
        AerialSettings.chooseBrightnessBoostAllowed(true)
        vm.toggleBrightnessBoostAllowed(); awaitBrightness()
        assertEquals(10, display.level)
        assertEquals(0, display.lens)
        assertEquals(1f, vm.brightness!!, 0f)
        assertFalse(AerialSettings.brightnessBoostAllowed)
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(0, display.lens)
    }

    @Test fun rejectedEntryKeepsHundredAndAllowance() {
        atMaximum()
        display.automatic = true
        vm.toggleBrightnessBoostAllowed()
        repeat(2) { vm.adjustBrightness(1); awaitBrightness() }
        assertEquals(0, display.lens)
        assertFalse(vm.brightnessBoost!!)
        assertEquals(1f, vm.brightness!!, 0f)
        assertTrue(AerialSettings.brightnessBoostAllowed)
        assertFalse(display.calls.contains("mode:1"))
    }

    @Test fun rapidStepsToRejectedBoostStillReachOrdinaryHundred() {
        display.automatic = true
        vm.toggleBrightnessBoostAllowed()
        repeat(4) { vm.adjustBrightness(1) }
        awaitBrightness()
        assertEquals(10, display.level)
        assertEquals(0, display.lens)
        assertEquals(1f, vm.brightness!!, 0f)
        assertFalse(vm.brightnessBoost!!)
    }

    @Test fun rejectedBoostDoesNotReadBackAnUnsettledOrdinaryMaximum() {
        display.automatic = true
        display.deferLevelWrites = true
        display.level = 9
        shown(1f, false)
        AerialSettings.chooseBrightnessBoostAllowed(true)
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(1f, vm.brightness!!, 0f)
        assertFalse(vm.brightnessBoost!!)
        // Simulate the accepted ordinary write settling before another right key.
        display.level = 10
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(1f, vm.brightness!!, 0f)
        assertFalse(vm.brightnessBoost!!)
        assertEquals(listOf("level:10", "level:10"), display.calls)
    }

    @Test fun rejectedOrdinaryMaximumStillReadsBackTheActualLevel() {
        display.rejectLevelWrites = true
        display.level = 9
        shown(1f, false)
        AerialSettings.chooseBrightnessBoostAllowed(true)
        vm.adjustBrightness(1); awaitBrightness()
        assertEquals(0.9f, vm.brightness!!, 0f)
        assertFalse(vm.brightnessBoost!!)
        assertFalse(display.calls.contains("mode:1"))
    }

    @Test fun nativeBoostIsAdoptedWithoutHardwareWrites() {
        display.lens = 1
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        vm.refreshBrightness()
        await { vm.brightnessBoost == true }
        assertTrue(AerialSettings.brightnessBoostAllowed)
        display.lens = 0
        vm.refreshBrightness()
        await { vm.brightnessBoost == false }
        assertTrue(AerialSettings.brightnessBoostAllowed)
        assertTrue(display.calls.isEmpty())
    }

    @Test fun rejectedExitShowsActualBoostAndKeepsAllowanceOn() {
        display.lens = 1
        display.rejectModeChange = true
        shown(0.7f, true)
        AerialSettings.chooseBrightnessBoostAllowed(true)
        vm.toggleBrightnessBoostAllowed(); awaitBrightness()
        assertEquals(1, display.lens)
        assertEquals(7, display.level)
        assertTrue(vm.brightnessBoost!!)
        assertTrue(AerialSettings.brightnessBoostAllowed)
    }

    @Test fun switchingOffWhileEntryIsInFlightWinsOverThatEntry() {
        atMaximum()
        vm.toggleBrightnessBoostAllowed()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        display.beforeModeChange = { mode ->
            if (mode == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        vm.adjustBrightness(1)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            vm.toggleBrightnessBoostAllowed()
        } finally { release.countDown() }
        awaitBrightness()
        assertEquals(0, display.lens)
        assertEquals(10, display.level)
        assertFalse(vm.brightnessBoost!!)
        assertFalse(AerialSettings.brightnessBoostAllowed)
    }

    @Test fun rapidReverseStepsFromTheRequestedBoostAndCanEnterAgain() {
        atMaximum()
        vm.toggleBrightnessBoostAllowed()
        vm.adjustBrightness(1)
        vm.adjustBrightness(-1)
        vm.adjustBrightness(1)
        awaitBrightness()
        assertEquals(1, display.lens)
        assertTrue(vm.brightnessBoost!!)
        vm.adjustBrightness(-1)
        vm.adjustBrightness(-1)
        awaitBrightness()
        assertEquals(9, display.level)
        assertEquals(0, display.lens)
    }

    @Test fun unsupportedBoostNeverEnablesExtraStep() {
        XgimiBoost::class.java.getDeclaredField("supported").also { it.isAccessible = true }.set(null, false)
        shown(1f, null)
        display.level = 10
        vm.toggleBrightnessBoostAllowed()
        assertFalse(AerialSettings.brightnessBoostAllowed)
        // A persisted preference alone cannot grant the firmware capability.
        AerialSettings.chooseBrightnessBoostAllowed(true)
        repeat(2) { vm.adjustBrightness(1); awaitBrightness() }
        assertEquals(0, display.lens)
        assertFalse(display.calls.contains("mode:1"))
        display.level = 9
        display.rejectLevelWrites = true
        vm.adjustBrightness(1); awaitBrightness()
        // Preserve the existing optimistic ordinary slider and no new Toast on other engines.
        assertEquals(1f, vm.brightness!!, 0f)
        assertEquals(0, ShadowToast.shownToastCount())
    }
}
