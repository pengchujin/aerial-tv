package app.orchard.tv.system

import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.orchard.tv.R
import app.orchard.tv.settings.QuickAction
import app.orchard.tv.settings.QuickTarget
import app.orchard.tv.settings.QuickTarget.BuiltIn

/** Built-in controls offered to both editors, based on the device's available entry points. */
internal object QuickCatalog {
    fun available(context: Context): List<QuickAction> = buildList {
        QuickTarget.Vpn.entries.filter { VpnQuickControls.available(context, it) }
            .forEach { add(QuickAction(it.client.title, it)) }
        fun system(target: BuiltIn, label: Int) {
            add(QuickAction(context.getString(label), target))
        }
        system(BuiltIn.Network, R.string.qc_network_settings)
        if (context.packageManager.resolveActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), 0) != null) {
            system(BuiltIn.Bluetooth, R.string.qc_bluetooth_settings)
        }
        system(BuiltIn.Mute, R.string.qc_mute)
        system(BuiltIn.Inputs, R.string.cc_inputs)
        system(BuiltIn.Settings, R.string.cc_settings)
    }
}
