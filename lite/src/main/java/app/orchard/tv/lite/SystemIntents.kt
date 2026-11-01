package app.orchard.tv.lite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.konka.tvsettings", "com.konka.settingcenter.settings.view.MainSettingsActivity")),
        // Hisense VIDAA_TV: its own page before AOSP's, as in the full app (#1085).
        Intent(Settings.ACTION_SETTINGS).setComponent(ComponentName("com.android.tv.settings", "com.hisense.systemsettings.MainActivity")),
        Intent(Settings.ACTION_SETTINGS),
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.konka.systemsetting", "com.konka.systemsetting.MainActivity")),
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

    // Same OEM network category as SystemProbe (#760), not the direct Wi-Fi list.
    // Sidebar focus verified on H55E9A / Android 8; Android 4.x Lite still needs device verification.
    private fun hisenseNetworkSettingsIntent(context: Context): Intent? {
        val intent = Intent("android.settings.NETWORK_SETTINGS").setComponent(
            ComponentName("com.android.tv.settings", "com.hisense.systemsettings.MainActivity"),
        )
        val info = runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo }.getOrNull()
        return intent.takeIf { info != null && info.exported && info.enabled && info.applicationInfo.enabled }
    }

    /**
     * HDMI 1-3 through Xiaomi's player, which takes the port as its `input` number: 23-25, the
     * full app's xiaomiSources. Mi TV 2 (49", Android 4.3) lists no inputs, and its owner opened
     * them this way from another launcher (issue #1094); Mi TV 2 and 3 on Android 5.1 did the same
     * in the full app (#599 #309). Not on boxes, which have the player but no HDMI inputs, and
     * only before Android 5, where no TV lists its inputs: later Mi TV 4 boards number them
     * otherwise, which the full app knows.
     * CLEAR_TOP: a player task in the background is otherwise only brought back, its input unchanged.
     */
    fun xiaomiInputs(context: Context): List<Pair<String, Intent>> {
        if (Build.VERSION.SDK_INT >= 21) return emptyList()
        val player = ComponentName("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.tvplayer.ExternalSourceActivity")
        if (runCatching { context.packageManager.getActivityInfo(player, 0) }.isFailure) return emptyList()
        val traits = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "ro.build.characteristics") as String
        }.getOrNull().orEmpty()
        if ("stb" in traits || "mbx" in traits) return emptyList()
        return (1..3).map { port ->
            "HDMI $port" to Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY").setComponent(player)
                .putExtra("input", 22 + port)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
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
        // LeTV eUI's signal source app, back to the input last used (X3-55, Android 5.0.1, the full
        // app's issues #818 #998); not verified on LeTV's Android 4.x.
        val letv = ComponentName("com.stv.signalsourcemanager", "com.stv.signalsourcemanager.MainActivity")
        if (runCatching { pm.getActivityInfo(letv, 0) }.getOrNull()?.exported == true &&
            runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(letv).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        ) return true
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

    /**
     * Xiaomi's settings menu as the popup its settings app shows on a broadcast, for where its
     * activity closes at once: on Android 6 (Mi TV 4, Mi Box 3S, the full app's issue #834) it only
     * hosts that popup over PatchWall and finishes over any other Home. Older Xiaomi firmwares are
     * not verified. Armed when settings open, sent if Home is back within a moment (onResume).
     */
    private var settingsOpenedAt = 0L

    fun noteSettingsOpened(intent: Intent) {
        settingsOpenedAt = if (intent.component?.packageName == XIAOMI_SETTINGS) SystemClock.uptimeMillis() else 0L
    }

    /** Whether the Xiaomi settings just opened closed at once; asked once, on resume. */
    fun settingsBounced(context: Context): Boolean {
        val at = settingsOpenedAt
        settingsOpenedAt = 0L
        if (at == 0L || SystemClock.uptimeMillis() - at > 800L) return false
        val popup = Intent("com.xiaomi.intent.action.show.globalsetting").setPackage(XIAOMI_SETTINGS)
        return runCatching { context.packageManager.queryBroadcastReceivers(popup, 0) }.getOrNull().orEmpty().any { it.activityInfo.exported }
    }

    fun showXiaomiSettingsPopup(context: Context) {
        runCatching { context.sendBroadcast(Intent("com.xiaomi.intent.action.show.globalsetting").setPackage(XIAOMI_SETTINGS)) }
    }

    private const val XIAOMI_SETTINGS = "com.xiaomi.mitv.settings"

    /** Not Intent.resolveActivity: it returns an explicit component unchecked. */
    private fun first(context: Context, vararg intents: Intent): Intent {
        val pm = context.packageManager
        return (intents.firstOrNull { runCatching { pm.resolveActivity(it, 0) }.getOrNull() != null } ?: intents.last())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
