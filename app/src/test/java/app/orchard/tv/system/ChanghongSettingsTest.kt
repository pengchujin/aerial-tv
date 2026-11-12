package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import app.orchard.tv.MainActivity
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.apps.TileArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ChanghongSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val packages = shadowOf(context.packageManager)
    private val settingsPackages = listOf("com.android.settings", "com.android.tv.settings", ChanghongSettings.PACKAGE)

    @Before
    fun prepare() {
        ShadowBuild.setModel("长虹智能电视")
        ShadowBuild.setProduct("mt5520_ll")
        DeviceProfile.setOwn(context, null)
    }

    @Test
    fun controlCenterAndStockSettingsTileUseTheSameService() {
        installService()
        val selected = SystemProbe.settingsIntent(context)
        assertEquals(ChanghongSettings.component, selected.component)
        assertEquals("Changhong.EasySetting", selected.action)
        assertNull(selected.extras)
        for (pkg in settingsPackages) {
            assertTrue(selected.filterEquals(SystemProbe.settingsTileIntent(context, pkg)))
            assertTrue(SystemProbe.isSettingsApp(context, pkg))
        }
        assertNull(SystemProbe.settingsTileIntent(context, "unrelated.player"))
    }

    @Test
    fun networkPrefersTheVendorPanelOnWifiAndEthernet() {
        installService()
        for (ethernet in listOf(false, true)) {
            val selected = SystemProbe.networkSettingsIntents(context, ethernet).first()
            assertEquals(ChanghongSettings.component, selected.component)
            assertTrue(selected.getBooleanExtra(ChanghongSettings.NETWORK, false))
        }
    }

    @Test
    fun easySettingIsIndependentOfModelProductAndOwnProfile() {
        installService()
        for ((model, product) in listOf("Other TV" to "mt5520_ll", "长虹智能电视" to "other_product", "Other TV" to "other_product")) {
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            for (profile in listOf(null, "{}", """{"hidden":[8]}""")) {
                DeviceProfile.setOwn(context, profile)
                assertEquals(ChanghongSettings.component, SystemProbe.settingsIntent(context).component)
                assertEquals(ChanghongSettings.component, SystemProbe.networkSettingsIntents(context).first().component)
                assertEquals(ChanghongSettings.component, ChanghongSettings.intent(context, ChanghongSettings.PICTURE)?.component)
                for (pkg in settingsPackages) assertNotNull(SystemProbe.settingsTileIntent(context, pkg))
            }
        }
    }

    @Test
    fun packageAndSourceServiceWithoutEasySettingKeepTheExistingEntries() {
        packages.installPackage(PackageInfo().apply { packageName = ChanghongSettings.PACKAGE })
        packages.addServiceIfNotPresent(ComponentName("com.changhong.SourceMenu", "com.changhong.source.SourceMenuService")).exported = true
        assertNoEasySettingRoute()
        assertTrue(SystemProbe.isSettingsApp(context, ChanghongSettings.PACKAGE))
        assertTrue(SystemProbe.openPictureSettings(context))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun unexportedDisabledOrPermissionProtectedServicesAreSkipped() {
        for (unavailable in listOf("unexported", "disabled", "disabledApp", "permission")) {
            val service = installService()
            when (unavailable) {
                "unexported" -> service.exported = false
                "disabled" -> service.enabled = false
                "disabledApp" -> service.applicationInfo.enabled = false
                "permission" -> service.permission = "com.changhong.permission.EASY_SETTING"
            }
            assertNoEasySettingRoute()
        }
    }

    @Test
    fun componentDisabledByTheOwnerIsSkipped() {
        installService()
        context.packageManager.setComponentEnabledSetting(ChanghongSettings.component, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0)
        assertNoEasySettingRoute()
    }

    @Test
    fun grantedServicePermissionAllowsTheSettingsEntry() {
        installService().permission = "com.changhong.permission.EASY_SETTING"
        val caller = object : ContextWrapper(context) {
            override fun checkCallingOrSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        }
        assertEquals(ChanghongSettings.component, SystemProbe.settingsIntent(caller).component)
    }

    @Test
    fun serviceFailureUsesTheMatchingActivityFallback() {
        val legacySettings = SystemProbe.settingsIntent(context)
        val legacyNetwork = SystemProbe.networkSettingsIntents(context).first()
        installService()
        for (failure in listOf(null, SecurityException("Denied"), IllegalStateException("Unavailable"))) {
            val caller = RecordingContext(context, failure)
            SystemProbe.startSettingsIntent(caller, SystemProbe.settingsIntent(caller))
            SystemProbe.startSettingsIntent(caller, SystemProbe.networkSettingsIntents(caller).first())
            assertEquals(2, caller.services.size)
            assertEquals(2, caller.activities.size)
            assertTrue(legacySettings.filterEquals(caller.activities[0]))
            assertTrue(legacyNetwork.filterEquals(caller.activities[1]))
        }
    }

    @Test
    fun serviceDisabledAfterSelectionFallsBackWithoutTryingToStartIt() {
        val legacySettings = SystemProbe.settingsIntent(context)
        val legacyNetwork = SystemProbe.networkSettingsIntents(context).first()
        val service = installService()
        val settings = SystemProbe.settingsIntent(context)
        val network = SystemProbe.networkSettingsIntents(context).first()
        service.enabled = false
        val caller = RecordingContext(context)
        SystemProbe.startSettingsIntent(caller, settings)
        SystemProbe.startSettingsIntent(caller, network)
        assertTrue(caller.services.isEmpty())
        assertTrue(legacySettings.filterEquals(caller.activities[0]))
        assertTrue(legacyNetwork.filterEquals(caller.activities[1]))
    }

    @Test
    fun mainActivityDispatchesSettingsAndNetworkToServiceNotActivity() {
        installService()
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        for (intent in listOf(SystemProbe.settingsIntent(activity), SystemProbe.networkSettingsIntents(activity).first())) {
            MainActivity::class.java.getDeclaredMethod("open", Intent::class.java).apply {
                isAccessible = true
            }.invoke(activity, intent)
            val started = shadowOf(activity).nextStartedService
            assertNotNull(started)
            assertTrue(intent.filterEquals(started))
            assertEquals(intent.getBooleanExtra(ChanghongSettings.NETWORK, false), started.getBooleanExtra(ChanghongSettings.NETWORK, false))
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(0, ShadowToast.shownToastCount())
        }
    }

    @Test
    fun mainActivityRedirectsOnlySettingsTilesAndOnlyWhenTheServiceExists() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
        val art = TileArt(bitmap, null, Rect.Zero, Color.Black, bitmap)
        val launch = MainActivity::class.java.getDeclaredMethod("launch", AppEntry::class.java, Rect::class.java)
            .apply { isAccessible = true }
        for (available in listOf(false, true)) {
            if (available) installService()
            for (pkg in settingsPackages + "unrelated.player") {
                val app = AppEntry(pkg, "Settings", ComponentName(pkg, "$pkg.SettingsActivity"), true, false, art)
                launch.invoke(activity, app, null)
                if (available && pkg in settingsPackages) {
                    assertEquals(ChanghongSettings.component, shadowOf(activity).nextStartedService.component)
                    assertNull(shadowOf(activity).nextStartedActivity)
                } else {
                    assertTrue(app.launchIntent().filterEquals(shadowOf(activity).nextStartedActivity))
                    assertNull(shadowOf(activity).nextStartedService)
                }
            }
        }
    }

    @Test
    fun unavailableBacklightOpensVendorPicturePanel() {
        installService()
        assertTrue(SystemProbe.openPictureSettings(context))
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(ChanghongSettings.component, started.component)
        assertTrue(started.getBooleanExtra(ChanghongSettings.PICTURE, false))
    }

    @Test
    fun failedPictureServiceUsesTheExistingPictureFallback() {
        val legacy = RecordingContext(context)
        assertTrue(SystemProbe.openPictureSettings(legacy))
        installService()
        for (failure in listOf(null, SecurityException("Denied"), IllegalStateException("Unavailable"))) {
            val caller = RecordingContext(context, failure)
            assertTrue(SystemProbe.openPictureSettings(caller))
            assertTrue(caller.services.single().getBooleanExtra(ChanghongSettings.PICTURE, false))
            assertTrue(legacy.activities.single().filterEquals(caller.activities.single()))
        }
    }

    private fun installService() = packages.addServiceIfNotPresent(ChanghongSettings.component).apply {
        exported = true
        enabled = true
        applicationInfo.enabled = true
        permission = null
    }

    private fun assertNoEasySettingRoute() {
        assertNull(ChanghongSettings.intent(context))
        assertNull(ChanghongSettings.intent(context, ChanghongSettings.PICTURE))
        assertFalse(ChanghongSettings.component == SystemProbe.settingsIntent(context).component)
        assertFalse(ChanghongSettings.component == SystemProbe.networkSettingsIntents(context).first().component)
        for (pkg in settingsPackages) assertNull(SystemProbe.settingsTileIntent(context, pkg))
        val caller = RecordingContext(context)
        assertFalse(ChanghongSettings.open(caller, Intent().setComponent(ChanghongSettings.component)))
        assertTrue(caller.services.isEmpty())
    }

    private class RecordingContext(base: Context, private val failure: RuntimeException? = null) : ContextWrapper(base) {
        val services = mutableListOf<Intent>()
        val activities = mutableListOf<Intent>()
        override fun startService(service: Intent): ComponentName? {
            services += Intent(service)
            failure?.let { throw it }
            return null
        }
        override fun startActivity(intent: Intent) { activities += Intent(intent) }
    }
}
