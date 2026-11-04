package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val fallback = ComponentName("com.mstar.tvsetting", "com.foxconn.etvg.inputsource.FxcInputSourceActitvity")

    @Before
    fun prepare() {
        ShadowBuild.setModel("长虹智能电视")
        ShadowBuild.setProduct("mt5520_ll")
        DeviceProfile.setOwn(context, null)
    }

    @Test
    fun exportedServiceOpensTheMenuWithoutStartingThePreviousInput() {
        installService()
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
    fun packageAndEasySettingWithoutSourceServiceDoNotEnableThePicker() {
        packages.installPackage(PackageInfo().apply { packageName = component.packageName })
        packages.addServiceIfNotPresent(ChanghongSettings.component).exported = true
        val caller = RecordingContext(context)
        assertFalse(SystemProbe.openInputPicker(caller))
        assertTrue(caller.services.isEmpty())
    }

    @Test
    fun sourceServiceIsIndependentOfEasySettingModelProductAndOwnProfile() {
        installService()
        assertNull(ChanghongSettings.intent(context))
        for ((model, product) in listOf("Other TV" to "mt5520_ll", "长虹智能电视" to "other_product", "Other TV" to "other_product")) {
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            for (profile in listOf(null, "{}", """{"hidden":[8]}""")) {
                DeviceProfile.setOwn(context, profile)
                val caller = RecordingContext(context)
                assertTrue(SystemProbe.openInputPicker(caller))
                assertEquals(component, caller.services.single().component)
                assertTrue(caller.activities.isEmpty())
            }
        }
    }

    @Test
    fun unexportedDisabledOrPermissionProtectedServicesAreSkipped() {
        for (unavailable in listOf("unexported", "disabled", "disabledApp", "permission")) {
            val service = installService()
            when (unavailable) {
                "unexported" -> service.exported = false
                "disabled" -> service.enabled = false
                "disabledApp" -> service.applicationInfo.enabled = false
                "permission" -> service.permission = "com.changhong.permission.SOURCE_MENU"
            }
            val caller = RecordingContext(context)
            assertFalse(unavailable, SystemProbe.openInputPicker(caller))
            assertTrue(unavailable, caller.services.isEmpty())
        }
    }

    @Test
    fun componentDisabledByTheOwnerIsSkipped() {
        installService()
        context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
        val caller = RecordingContext(context)
        assertFalse(SystemProbe.openInputPicker(caller))
        assertTrue(caller.services.isEmpty())
    }

    @Test
    fun grantedServicePermissionAllowsThePicker() {
        installService().permission = "com.changhong.permission.SOURCE_MENU"
        val caller = object : RecordingContext(context) {
            override fun checkCallingOrSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        }
        assertTrue(SystemProbe.openInputPicker(caller))
        assertEquals(component, caller.services.single().component)
    }

    @Test
    fun missingServiceUsesTheExistingPickerEvenOnThePreviouslyMatchedModel() {
        packages.addActivityIfNotPresent(fallback).exported = true
        val caller = RecordingContext(context)
        assertTrue(SystemProbe.openInputPicker(caller))
        assertTrue(caller.services.isEmpty())
        assertEquals(fallback, caller.activities.single().component)
    }

    @Test
    fun nullOrRejectedStartDoesNotClaimSuccess() {
        installService()
        for (failure in listOf(null, SecurityException("Denied"), IllegalStateException("Unavailable"))) {
            val caller = RecordingContext(context, starts = false, failure = failure)
            assertFalse(SystemProbe.openInputPicker(caller))
            assertEquals(1, caller.services.size)
        }
    }

    @Test
    fun failedServiceStillTriesTheExistingPickerFallbacks() {
        installService()
        packages.addActivityIfNotPresent(fallback).exported = true
        for (failure in listOf(null, SecurityException("Denied"), IllegalStateException("Unavailable"))) {
            val caller = RecordingContext(context, starts = false, failure = failure)
            assertTrue(SystemProbe.openInputPicker(caller))
            assertEquals(1, caller.services.size)
            assertEquals(fallback, caller.activities.single().component)
        }
    }

    private fun installService() = packages.addServiceIfNotPresent(component).apply {
        exported = true
        enabled = true
        applicationInfo.enabled = true
        permission = null
    }

    private open class RecordingContext(
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
