package app.orchard.tv.system

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.orchard.tv.R
import app.orchard.tv.settings.QuickControls
import app.orchard.tv.settings.ManualQuickTarget
import app.orchard.tv.settings.QuickTarget.BuiltIn
import app.orchard.tv.settings.QuickTarget.Vpn
import app.orchard.tv.settings.Restricted

/** The TV and phone dispatch saved capabilities on the main thread under the same policy. */
internal object QuickActionRunner {
    enum class Result(val message: Int) {
        Sent(R.string.qc_sent), Unavailable(R.string.qc_unavailable), Failed(R.string.qc_failed),
        Background(R.string.qc_background), Restricted(R.string.qc_restricted),
    }

    /** The resumed Control Center's existing settings and input navigation. */
    class Foreground(
        val open: (Intent) -> Unit,
        val showInputs: () -> Unit,
        val closePanel: () -> Unit,
    )

    var foreground: Foreground? = null

    fun run(context: Context, id: String): Result {
        if (Restricted.active) return Result.Restricted
        val target = QuickControls.store.enabledAction(id)?.target ?: return Result.Unavailable
        if (target == BuiltIn.Mute) return runCatching {
            SystemProbe.toggleMute(context)
            Result.Sent
        }.getOrDefault(Result.Failed)
        val home = foreground ?: return Result.Background
        return runCatching {
            when (target) {
                is ManualQuickTarget -> return ManualQuickDispatch.send(context, target).also {
                    if (it == Result.Sent && target.type == ManualQuickTarget.Type.Activity) home.closePanel()
                }
                is Vpn -> return VpnQuickControls.send(context, target)
                BuiltIn.Network -> {
                    val pages = SystemProbe.networkSettingsIntents(context, SystemProbe.network(context).kind == NetKind.Ethernet)
                    val first = pages.firstOrNull() ?: return Result.Unavailable
                    LaunchFallback.arm(pages.drop(1))
                    home.open(first)
                }
                BuiltIn.Bluetooth -> home.open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                BuiltIn.Inputs -> home.showInputs()
                BuiltIn.Settings -> {
                    LaunchFallback.arm(SystemProbe.settingsFallbacks(context))
                    home.open(SystemProbe.settingsIntent(context))
                }
                BuiltIn.Mute -> Unit // Handled above so it can run without a foreground window.
            }
            Result.Sent
        }.getOrDefault(Result.Failed)
    }
}
