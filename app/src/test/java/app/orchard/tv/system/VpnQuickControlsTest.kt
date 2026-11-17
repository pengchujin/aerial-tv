package app.orchard.tv.system

import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import app.orchard.tv.settings.QuickAction
import app.orchard.tv.settings.QuickTarget.Vpn
import app.orchard.tv.system.QuickActionRunner.Result
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
@Config(sdk = [30])
class VpnQuickControlsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val packageManager = shadowOf(context.packageManager)
    private val app = shadowOf(context)
    private val tailscale = ComponentName("com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver")
    private val taskerAction = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    private val bundleKey = "com.twofortyfouram.locale.intent.extra.BUNDLE"

    @Test fun catalogShowsOnlyTheActionsPublishedByTheExpectedReceiver() {
        assertFalse(VpnQuickControls.available(context, Vpn.TailscaleConnect))
        publish(tailscale, "com.tailscale.ipn.CONNECT_VPN")
        val entry = QuickCatalog.available(context).single { it.target == Vpn.TailscaleConnect }
        assertEquals("connect", entry.displayJson().getString("vpnCommand"))
        assertEquals(entry, QuickAction.from(entry.json()))
        assertFalse(VpnQuickControls.available(context, Vpn.TailscaleDisconnect))
        assertEquals(Result.Unavailable, VpnQuickControls.send(context, Vpn.TailscaleDisconnect))
    }

    @Test fun tailscaleSendsIndependentCommandsWithoutConsultingSystemVpnState() {
        publish(tailscale, "com.tailscale.ipn.CONNECT_VPN", "com.tailscale.ipn.DISCONNECT_VPN")
        assertEquals(Result.Sent, VpnQuickControls.send(context, Vpn.TailscaleConnect))
        assertEquals(Result.Sent, VpnQuickControls.send(context, Vpn.TailscaleDisconnect))
        val sent = app.broadcastIntents.takeLast(2)
        assertTrue(sent.all { it.component == tailscale })
        assertEquals(listOf("com.tailscale.ipn.CONNECT_VPN", "com.tailscale.ipn.DISCONNECT_VPN"), sent.map { it.action })
    }

    @Test fun taskerPresetsUseTheSelectedClientProfile() {
        val v2ray = ComponentName("com.v2ray.ang", "com.v2ray.ang.receiver.TaskerReceiver")
        val shadowsocks = ComponentName("com.github.shadowsocks", "com.github.shadowsocks.tasker.ActionListener")
        publish(v2ray, taskerAction)
        publish(shadowsocks, taskerAction)
        assertEquals(Result.Sent, VpnQuickControls.send(context, Vpn.V2rayConnect))
        assertEquals(Result.Sent, VpnQuickControls.send(context, Vpn.V2rayDisconnect))
        assertEquals(Result.Sent, VpnQuickControls.send(context, Vpn.ShadowsocksConnect))
        assertEquals(Result.Sent, VpnQuickControls.send(context, Vpn.ShadowsocksDisconnect))
        val sent = app.broadcastIntents.takeLast(4)
        assertEquals(listOf(v2ray, v2ray, shadowsocks, shadowsocks), sent.map { it.component })
        assertTrue(sent.all { it.action == taskerAction })
        val extras = sent.map { requireNotNull(it.getBundleExtra(bundleKey)) }
        assertTrue(extras[0].getBoolean("tasker_extra_bundle_switch"))
        assertFalse(extras[1].getBoolean("tasker_extra_bundle_switch", true))
        assertEquals(listOf("Default", "Default"), extras.take(2).map { it.getString("tasker_extra_bundle_guid") })
        assertTrue(extras[2].getBoolean("switch_on"))
        assertFalse(extras[3].getBoolean("switch_on", true))
        assertTrue(extras.drop(2).none { it.containsKey("profile_id") })
    }

    @Test fun aReceiverThatBecomesPermissionProtectedCannotBeUsed() {
        val receiver = publish(tailscale, "com.tailscale.ipn.CONNECT_VPN")
        assertTrue(VpnQuickControls.available(context, Vpn.TailscaleConnect))
        receiver.permission = "com.tailscale.ipn.CONTROL_VPN"
        assertFalse(VpnQuickControls.available(context, Vpn.TailscaleConnect))
        assertEquals(Result.Unavailable, VpnQuickControls.send(context, Vpn.TailscaleConnect))
        assertTrue(app.broadcastIntents.none { it.component == tailscale })
    }

    private fun publish(component: ComponentName, vararg actions: String): ActivityInfo =
        packageManager.addReceiverIfNotPresent(component).apply {
            exported = true
            enabled = true
            applicationInfo.enabled = true
            packageManager.addIntentFilterForReceiver(component, IntentFilter().apply {
                actions.forEach { addAction(it) }
            })
        }
}
