package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChanghongInputPickerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val packages = shadowOf(context.packageManager)
    private val component = ComponentName("com.changhong.SourceMenu", "com.changhong.source.SourceMenuService")

    @Before
    fun prepare() {
        ShadowBuild.setModel("长虹智能电视")
        ShadowBuild.setProduct("mt5520_ll")
        DeviceProfile.setOwn(context, null)
    }

    @Test
    fun matchingProfileOpensTheMenuWithoutServiceDetectionOrStartingThePreviousInput() {
        val caller = RecordingContext(context)

        assertTrue(SystemProbe.openInputPicker(caller))
        val started = caller.services.single()
        assertEquals(component, started.component)
        assertEquals("CH_MENU_SOURCE", started.action)
        assertEquals("menuSource", started.getStringExtra("MenuSource"))
        assertEquals(2001, started.getIntExtra("keyCode", -1))
        assertTrue(caller.activities.isEmpty())
    }

    @Test
    fun missingServiceDoesNotClaimToOpenThePicker() {
        val caller = RecordingContext(context, starts = false)
        assertFalse(SystemProbe.openInputPicker(caller))
        assertEquals(1, caller.services.size)
    }

    @Test
    fun otherModelIgnoresTheSameExportedService() {
        ShadowBuild.setModel("Other TV")
        DeviceProfile.setOwn(context, null)
        packages.addServiceIfNotPresent(component).exported = true
        val caller = RecordingContext(context)
        assertFalse(SystemProbe.openInputPicker(caller))
        assertTrue(caller.services.isEmpty())
    }

    @Test
    fun sameModelWithAnotherProductIgnoresTheSameExportedService() {
        ShadowBuild.setProduct("other_product")
        DeviceProfile.setOwn(context, null)
        packages.addServiceIfNotPresent(component).exported = true
        val caller = RecordingContext(context)
        assertFalse(SystemProbe.openInputPicker(caller))
        assertTrue(caller.services.isEmpty())
    }

    @Test
    fun localProfileCanDisableTheVendorRouting() {
        DeviceProfile.setOwn(context, "{}")
        packages.addServiceIfNotPresent(component).exported = true
        val caller = RecordingContext(context)
        assertFalse(SystemProbe.openInputPicker(caller))
        assertTrue(caller.services.isEmpty())
    }

    @Test
    fun ownProfileOnOtherTvKeepsTheExistingPicker() {
        val fallback = ComponentName("com.mstar.tvsetting", "com.foxconn.etvg.inputsource.FxcInputSourceActitvity")
        packages.addActivityIfNotPresent(fallback).exported = true
        packages.addServiceIfNotPresent(component).exported = true
        for ((model, product) in listOf("Other TV" to "mt5520_ll", "长虹智能电视" to "other_product")) {
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            DeviceProfile.setOwn(context, """{"changhongTvos":true}""")
            val caller = RecordingContext(context)
            assertTrue(SystemProbe.openInputPicker(caller))
            assertTrue(caller.services.isEmpty())
            assertEquals(fallback, caller.activities.single().component)
        }
    }

    @Test
    fun nullOrRejectedStartDoesNotClaimSuccess() {
        for (failure in listOf(null, SecurityException("Denied"), IllegalStateException("Unavailable"))) {
            val caller = RecordingContext(context, starts = false, failure = failure)
            assertFalse(SystemProbe.openInputPicker(caller))
            assertEquals(1, caller.services.size)
        }
    }

    @Test
    fun failedServiceStillTriesTheExistingPickerFallbacks() {
        val fallback = ComponentName("com.mstar.tvsetting", "com.foxconn.etvg.inputsource.FxcInputSourceActitvity")
        packages.addActivityIfNotPresent(fallback).exported = true
        val caller = RecordingContext(context, failure = SecurityException("Denied"))

        assertTrue(SystemProbe.openInputPicker(caller))
        assertEquals(1, caller.services.size)
        assertEquals(fallback, caller.activities.single().component)
    }

    private class RecordingContext(
        base: Context,
        private val starts: Boolean = true,
        private val failure: RuntimeException? = null,
    ) : ContextWrapper(base) {
        val services = mutableListOf<Intent>()
        val activities = mutableListOf<Intent>()

        override fun startService(service: Intent): ComponentName? {
            services += Intent(service)
            failure?.let { throw it }
            return service.component.takeIf { starts }
        }

        override fun startActivity(intent: Intent) {
            activities += Intent(intent)
        }
    }
}
