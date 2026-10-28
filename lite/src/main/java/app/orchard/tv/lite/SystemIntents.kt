package app.orchard.tv.lite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The TV's own pages, found as the full app's SystemProbe finds them (each line verified on the
 * TV it names there): the first that answers wins, the standard action last.
 */
object SystemIntents {
    fun settings(context: Context): Intent = first(
        context,
        Intent("com.xiaomi.mitv.settings.SETTINGS").setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity")),
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.haier.tv.menusetting", "com.haier.tv.menusetting.RootActivity")),
        Intent(Settings.ACTION_SETTINGS),
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.pptv.setting", "com.pptv.setting.root.SettingsActivity")),
        Intent(Intent.ACTION_VIEW, Uri.parse("Settings://com.xgimi.misckey.main/mainPage")),
    )

    fun network(context: Context, ethernet: Boolean): Intent = first(
        context,
        *listOfNotNull(hisenseNetworkSettingsIntent(context)).toTypedArray(),
        *listOfNotNull(Intent("com.xiaomi.mitv.settings.ETHERNET_SETTINGS").setPackage("com.xiaomi.mitv.settings").takeIf { ethernet }).toTypedArray(),
        Intent("com.xiaomi.mitv.settings.WIFI_SETTINGS").setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.wifi.MitvWifiActivity")),
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity")),
        Intent(Settings.ACTION_WIFI_SETTINGS),
        Intent("com.pptv.intent.action.WIFI_ACTIVITY").setComponent(ComponentName("com.pptv.setting", "com.pptv.setting.wifi.WifiWorkActivity")),
        Intent(Settings.ACTION_SETTINGS),
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
    )

    // Same OEM entry as SystemProbe, verified on Hisense H55E9A / Android 8 (#760).
    private fun hisenseNetworkSettingsIntent(context: Context): Intent? {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).setComponent(
            ComponentName("com.android.tv.settings", "com.hisense.systemsettings.MainActivity"),
        )
        val info = runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo }.getOrNull()
        return intent.takeIf { info != null && info.exported && info.enabled && info.applicationInfo.enabled }
    }

    /**
     * The TV's own input panel where Android lists no inputs (every 4.x TV): Hisense's service,
     * TCL's panel, Coocaa's source dialog. False when the TV has none of them.
     */
    fun openInputPicker(context: Context): Boolean {
        val pm = context.packageManager
        val hisense = Intent("com.hisense.tv.start_inputs").setComponent(ComponentName("com.hisense.tv.inputs", "com.hisense.tv.InputsUIService"))
        if (pm.resolveService(hisense, 0) != null) {
            val shown = runCatching { if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(hisense) else context.startService(hisense) }.getOrNull() != null
            if (shown) return true
        }
        val tcl = if (pm.hasSystemFeature("com.tcl.software.systemui_custom_tv_source")) {
            Intent("com.tcl.tvstatusbar.action.SHOW_WINDOW").setPackage("com.android.systemui")
                .putExtra("TvStatusBar_Type", "Source").putExtra("TvStatusBar_StartFrom", "input")
        } else {
            Intent("com.tcl.insrc.SHOW_WINDOW").setPackage("com.tcl.settings")
        }
        if (runCatching { pm.resolveService(tcl, 0) }.getOrNull() != null && runCatching { context.startService(tcl) }.getOrNull() != null) return true
        val coocaa = Intent("coocaa.intent.action.SOURCE_DIALOG").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.resolveActivity(coocaa, 0) }.getOrNull() != null && runCatching { context.startActivity(coocaa) }.isSuccess) return true
        // Coocaa C10's AI launcher picker and the older SkyMovie broadcast (issue #779).
        val aigc = Intent("coocaa.intent.aigc.home.tvsource").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.resolveActivity(aigc, 0) }.getOrNull() != null && runCatching { context.startActivity(aigc) }.isSuccess) return true
        val skyMovie = Intent("com.coocaa.action.SOURCE")
            .setComponent(ComponentName("com.tianci.movieplatform", "com.ccos.cooui.homepage.receiver.LaunchSourceReceiver"))
        if (runCatching { pm.queryBroadcastReceivers(skyMovie, 0) }.getOrNull().orEmpty().any { it.activityInfo.exported }) {
            return runCatching { context.sendBroadcast(skyMovie) }.isSuccess
        }
        return false
    }

    /**
     * Konka's full settings menu, a panel its settings app shows as a service (Konka V563, issue
     * #771); false where the TV has no such service.
     */
    fun openSettingsPanel(context: Context): Boolean {
        val konka = Intent("com.konka.settings.action.SETTING_MAIN_VIEW")
            .setComponent(ComponentName("com.konka.tvsettings", "com.konka.service.SettingService"))
        if (runCatching { context.packageManager.resolveService(konka, 0) }.getOrNull()?.serviceInfo?.exported != true) return false
        return runCatching { context.startService(konka) }.getOrNull() != null
    }

    /** Not Intent.resolveActivity: it returns an explicit component unchecked. */
    private fun first(context: Context, vararg intents: Intent): Intent {
        val pm = context.packageManager
        return (intents.firstOrNull { runCatching { pm.resolveActivity(it, 0) }.getOrNull() != null } ?: intents.last())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
