package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.orchard.tv.settings.QuickTarget.Vpn
import app.orchard.tv.settings.QuickTarget.Vpn.Client
import app.orchard.tv.settings.QuickTarget.Vpn.Command
import app.orchard.tv.system.QuickActionRunner.Result

/** Calls installed VPN clients through their public control interfaces. */
internal object VpnQuickControls {
    fun available(context: Context, target: Vpn): Boolean =
        if (target == Vpn.Clash) ClashShortcut.available(context) else broadcast(context, target) != null

    fun send(context: Context, target: Vpn): Result {
        if (target == Vpn.Clash) return when (ClashShortcut.toggle(context)) {
            ClashShortcut.Result.Sent -> Result.Sent
            ClashShortcut.Result.Unavailable -> Result.Unavailable
            ClashShortcut.Result.Failed -> Result.Failed
        }
        val intent = broadcast(context, target) ?: return Result.Unavailable
        return QuickIntentDispatch.send(context, intent, receiver = true)
    }

    private fun broadcast(context: Context, target: Vpn): Intent? {
        val connect = target.command == Command.Connect
        val intent = when (target.client) {
            // tailscale/tailscale-android: IPNReceiver publishes both commands.
            Client.Tailscale -> Intent(if (connect) "com.tailscale.ipn.CONNECT_VPN" else "com.tailscale.ipn.DISCONNECT_VPN")
                .setClassName("com.tailscale.ipn", "com.tailscale.ipn.IPNReceiver")
            // 2dust/v2rayNG: TaskerReceiver uses the currently selected server for the default GUID.
            Client.V2ray -> tasker("com.v2ray.ang", "com.v2ray.ang.receiver.TaskerReceiver", Bundle().apply {
                putBoolean("tasker_extra_bundle_switch", connect)
                putString("tasker_extra_bundle_guid", "Default")
            })
            // shadowsocks/shadowsocks-android: ActionListener keeps the selected profile when no ID is passed.
            Client.Shadowsocks -> tasker("com.github.shadowsocks", "com.github.shadowsocks.tasker.ActionListener",
                Bundle().apply { putBoolean("switch_on", connect) })
            Client.Clash -> return null
        }
        val component = requireNotNull(intent.component)
        if (!QuickIntentDispatch.isPublic(context, component, receiver = true)) return null
        // Check the action too, so a changed manifest does not advertise a stale command.
        val query = Intent(intent.action).setPackage(component.packageName)
        return try {
            intent.takeIf {
                context.packageManager.queryBroadcastReceivers(query, 0).any {
                    it.activityInfo.packageName == component.packageName && it.activityInfo.name == component.className
                }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun tasker(pkg: String, receiver: String, extras: Bundle): Intent =
        Intent("com.twofortyfouram.locale.intent.action.FIRE_SETTING")
            .setComponent(ComponentName(pkg, receiver))
            .putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", extras)
}
