package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HisenseSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val pm = shadowOf(context.packageManager)
    private val hisense = ComponentName("com.android.tv.settings", "com.hisense.systemsettings.MainActivity")
    private val aosp = ComponentName("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity")

    @Test
    fun exportedOemPagePrecedesAospForBothConnectionTypes() {
        addHisense()
        pm.addActivityIfNotPresent(aosp).exported = true
        for (ethernet in listOf(false, true)) {
            val pages = SystemProbe.networkSettingsIntents(context, ethernet)
            assertEquals(hisense, pages.first().component)
            assertEquals(Settings.ACTION_WIFI_SETTINGS, pages.first().action)
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, pages.first().flags)
            assertEquals(aosp, pages[1].component)
            // Generic Wi-Fi/settings resolve to the same OEM activity, not extra retries.
            assertEquals(2, pages.size)
        }
    }

    @Test
    fun missingOemPagePreservesAospOnOtherAndroidTvs() {
        pm.addActivityIfNotPresent(aosp).exported = true
        assertEquals(aosp, SystemProbe.networkSettingsIntents(context).first().component)
    }

    @Test
    fun unexportedOemPageIsNotSelected() {
        pm.addActivityIfNotPresent(hisense).exported = false
        pm.addActivityIfNotPresent(aosp).exported = true
        assertEquals(aosp, SystemProbe.networkSettingsIntents(context).first().component)
    }

    @Test
    fun disabledOemPageIsNotSelected() {
        addHisense()
        context.packageManager.setComponentEnabledSetting(
            hisense, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        pm.addActivityIfNotPresent(aosp).exported = true
        assertEquals(aosp, SystemProbe.networkSettingsIntents(context).first().component)
    }

    @Test
    fun xiaomiKeepsWiredAndWirelessPriorities() {
        val wifi = ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.wifi.MitvWifiActivity")
        val wired = ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.EthernetActivity")
        pm.addActivityIfNotPresent(wifi).exported = true
        pm.addActivityIfNotPresent(wired).exported = true
        pm.addActivityIfNotPresent(aosp).exported = true
        pm.addIntentFilterForActivity(wired, IntentFilter("com.xiaomi.mitv.settings.ETHERNET_SETTINGS"))
        assertEquals(wifi, SystemProbe.networkSettingsIntents(context).first().component)
        assertEquals("com.xiaomi.mitv.settings.ETHERNET_SETTINGS", SystemProbe.networkSettingsIntents(context, true).first().action)
    }

    @Test
    fun tclKeepsStandardWifiAction() {
        val tcl = ComponentName("com.tcl.settings", "com.tcl.settings.WifiActivity")
        pm.addActivityIfNotPresent(tcl).exported = true
        pm.addIntentFilterForActivity(tcl, IntentFilter(Settings.ACTION_WIFI_SETTINGS))
        val first = SystemProbe.networkSettingsIntents(context).first()
        assertEquals(Settings.ACTION_WIFI_SETTINGS, first.action)
        assertEquals(tcl.className, context.packageManager.resolveActivity(first, 0)!!.activityInfo.name)
        assertFalse(first.component == hisense)
    }

    @Test
    fun startsNativeNetworkActionWithoutChangingGeneralSettings() {
        addHisense()
        val launched = mutableListOf<Intent>()
        val caller = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched += intent }
        }
        SystemProbe.startSettingsIntent(caller, SystemProbe.networkSettingsIntents(caller).first())
        assertEquals(1, launched.size)
        assertEquals(hisense, launched.single().component)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, launched.single().action)
        assertTrue(launched.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(Settings.ACTION_SETTINGS, SystemProbe.settingsIntent(context).action)
    }

    private fun addHisense() {
        pm.addActivityIfNotPresent(hisense).exported = true
        pm.addIntentFilterForActivity(hisense, IntentFilter(Settings.ACTION_WIFI_SETTINGS))
        pm.addIntentFilterForActivity(hisense, IntentFilter(Settings.ACTION_SETTINGS))
    }
}
