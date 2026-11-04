package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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

    @Before
    fun prepare() {
        ShadowBuild.setModel("长虹智能电视")
        ShadowBuild.setProduct("mt5520_ll")
        DeviceProfile.setOwn(context, null)
    }

    @Test
    fun controlCenterAndStockSettingsTileUseTheSameService() {
        val selected = SystemProbe.settingsIntent(context)
        assertEquals(ChanghongSettings.component, selected.component)
        assertEquals("Changhong.EasySetting", selected.action)
        assertNull(selected.extras)
        for (pkg in listOf("com.android.settings", "com.android.tv.settings", ChanghongSettings.PACKAGE)) {
            assertTrue(selected.filterEquals(SystemProbe.settingsTileIntent(context, pkg)))
            assertTrue(SystemProbe.isSettingsApp(context, pkg))
        }
        assertNull(SystemProbe.settingsTileIntent(context, "unrelated.player"))
    }

    @Test
    fun networkPrefersTheVendorPanelOnWifiAndEthernet() {
        for (ethernet in listOf(false, true)) {
            val selected = SystemProbe.networkSettingsIntents(context, ethernet).first()
            assertEquals(ChanghongSettings.component, selected.component)
            assertTrue(selected.getBooleanExtra(ChanghongSettings.NETWORK, false))
        }
    }

    @Test
    fun otherModelsOrProductsIgnoreTheSameVendorService() {
        packages.addServiceIfNotPresent(ChanghongSettings.component).exported = true
        for ((model, product) in listOf("Other TV" to "mt5520_ll", "长虹智能电视" to "other_product")) {
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            DeviceProfile.setOwn(context, null)
            assertFalse(ChanghongSettings.component == SystemProbe.settingsIntent(context).component)
            assertFalse(ChanghongSettings.component == SystemProbe.networkSettingsIntents(context).first().component)
            for (pkg in listOf("com.android.settings", "com.android.tv.settings", ChanghongSettings.PACKAGE)) {
                assertNull(SystemProbe.settingsTileIntent(context, pkg))
            }
            assertNull(ChanghongSettings.intent(context, ChanghongSettings.PICTURE))
            assertFalse(SystemProbe.isSettingsApp(context, ChanghongSettings.PACKAGE))
            assertFalse(ChanghongSettings.open(context, Intent().setComponent(ChanghongSettings.component)))
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        }
    }

    @Test
    fun serviceFailureUsesTheMatchingActivityFallback() {
        ShadowBuild.setProduct("other_product")
        DeviceProfile.setOwn(context, null)
        val legacySettings = SystemProbe.settingsIntent(context)
        val legacyNetwork = SystemProbe.networkSettingsIntents(context).first()
        prepare()
        for (throws in listOf(false, true)) {
            val caller = object : ContextWrapper(context) {
                val activities = mutableListOf<Intent>()
                override fun startService(service: Intent): ComponentName? {
                    if (throws) throw SecurityException("Unavailable")
                    return null
                }
                override fun startActivity(intent: Intent) { activities += intent }
            }
            SystemProbe.startSettingsIntent(caller, SystemProbe.settingsIntent(caller))
            SystemProbe.startSettingsIntent(caller, SystemProbe.networkSettingsIntents(caller).first())
            assertEquals(2, caller.activities.size)
            assertTrue(legacySettings.filterEquals(caller.activities[0]))
            assertTrue(legacyNetwork.filterEquals(caller.activities[1]))
        }
    }

    @Test
    fun otherTvKeepsActivityDispatchEvenForAnExplicitChanghongIntent() {
        ShadowBuild.setModel("Other TV")
        DeviceProfile.setOwn(context, """{"changhongTvos":true}""")
        val caller = object : ContextWrapper(context) {
            val activities = mutableListOf<Intent>()
            val services = mutableListOf<Intent>()
            override fun startService(service: Intent): ComponentName? {
                services += service
                return service.component
            }
            override fun startActivity(intent: Intent) { activities += intent }
        }
        for (page in listOf(null, ChanghongSettings.NETWORK, ChanghongSettings.PICTURE)) {
            val intent = Intent("Changhong.EasySetting").setComponent(ChanghongSettings.component)
            if (page != null) intent.putExtra(page, true)
            SystemProbe.startSettingsIntent(caller, intent)
            assertTrue(intent.filterEquals(caller.activities.last()))
            if (page != null) assertTrue(caller.activities.last().getBooleanExtra(page, false))
        }
        assertTrue(caller.services.isEmpty())
    }

    @Test
    fun ownProfileCannotRedirectSettingsNetworkTilesOrPictureOnOtherTv() {
        ShadowBuild.setProduct("other_product")
        DeviceProfile.setOwn(context, null)
        val legacySettings = SystemProbe.settingsIntent(context)
        val legacyNetwork = SystemProbe.networkSettingsIntents(context)
        packages.addServiceIfNotPresent(ChanghongSettings.component).exported = true
        DeviceProfile.setOwn(context, """{"changhongTvos":true}""")
        assertTrue(legacySettings.filterEquals(SystemProbe.settingsIntent(context)))
        val network = SystemProbe.networkSettingsIntents(context)
        assertEquals(legacyNetwork.size, network.size)
        assertTrue(legacyNetwork.zip(network).all { (expected, actual) -> expected.filterEquals(actual) })
        for (pkg in listOf("com.android.settings", "com.android.tv.settings", ChanghongSettings.PACKAGE)) {
            assertNull(SystemProbe.settingsTileIntent(context, pkg))
        }
        assertFalse(SystemProbe.isSettingsApp(context, ChanghongSettings.PACKAGE))
        assertTrue(SystemProbe.openPictureSettings(context))
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun mainActivityDispatchesSettingsAndNetworkToServiceNotActivity() {
        packages.addServiceIfNotPresent(ChanghongSettings.component).exported = true
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
    fun otherTvLaunchesSettingsTilesWithTheirOriginalIntents() {
        ShadowBuild.setModel("Other TV")
        DeviceProfile.setOwn(context, """{"changhongTvos":true}""")
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap()
        val art = TileArt(bitmap, null, Rect.Zero, Color.Black, bitmap)
        val launch = MainActivity::class.java.getDeclaredMethod("launch", AppEntry::class.java, Rect::class.java)
            .apply { isAccessible = true }
        for (pkg in listOf("com.android.settings", "com.android.tv.settings", ChanghongSettings.PACKAGE, "unrelated.player")) {
            val app = AppEntry(pkg, "Settings", ComponentName(pkg, "$pkg.SettingsActivity"), true, false, art)
            launch.invoke(activity, app, null)
            assertTrue(app.launchIntent().filterEquals(shadowOf(activity).nextStartedActivity))
            assertNull(shadowOf(activity).nextStartedService)
        }
    }

    @Test
    fun unavailableBacklightOpensVendorPicturePanel() {
        packages.addServiceIfNotPresent(ChanghongSettings.component).exported = true
        assertTrue(SystemProbe.openPictureSettings(context))
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(ChanghongSettings.component, started.component)
        assertTrue(started.getBooleanExtra(ChanghongSettings.PICTURE, false))
    }
}
