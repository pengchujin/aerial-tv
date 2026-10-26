package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.orchard.tv.BuildConfig
import app.orchard.tv.R
import app.orchard.tv.guard.HomeGuardService

enum class NetKind { Wifi, Ethernet, Offline }

/** The network apps use by default. ConnectivityManager.activeNetwork only exists from Android 6.0. */
fun ConnectivityManager.currentNetwork(): Network? {
    if (Build.VERSION.SDK_INT >= 23) return activeNetwork
    @Suppress("DEPRECATION")
    val type = activeNetworkInfo?.takeIf { it.isConnected }?.type ?: return null
    @Suppress("DEPRECATION")
    return allNetworks.firstOrNull { getNetworkInfo(it)?.type == type }
}

data class NetworkStatus(val kind: NetKind = NetKind.Offline, val name: String? = null, val level: Float = 1f)

data class VolumeStatus(val level: Int = 0, val max: Int = 100, val muted: Boolean = false) {
    val fraction: Float get() = if (max == 0) 0f else level / max.toFloat()
}

enum class SourceKind { Hdmi, DisplayPort, Tuner, Av, Usb, Other }

data class TvSource(
    val id: String,
    val label: String,
    val kind: SourceKind,
    val connected: Boolean,
    /** What HDMI-CEC says is plugged in, e.g. "Apple TV". */
    val deviceName: String?,
    val intent: Intent,
)

enum class HomeStatus { Default, Guarded, NotDefault }

object SystemProbe {

    fun network(context: Context): NetworkStatus {
        val cm = context.getSystemService<ConnectivityManager>() ?: return NetworkStatus()
        var caps = cm.getNetworkCapabilities(cm.currentNetwork() ?: return NetworkStatus()) ?: return NetworkStatus()
        // Under a VPN the default network is the VPN's, which carries no Wi-Fi or Ethernet
        // transport on Android 8, and was shown as Ethernet over Wi-Fi (Hisense H55E9A, issue #435).
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            caps = runCatching { cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) } }.getOrDefault(emptyList())
                .firstOrNull {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        (it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                } ?: caps
        }
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus(NetKind.Ethernet)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wm = context.applicationContext.getSystemService<WifiManager>()
                @Suppress("DEPRECATION")
                val info = wm?.connectionInfo
                val ssid = info?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID.trim('<', '>') || it == "<unknown ssid>" }
                val level = if (info == null) 1f else if (Build.VERSION.SDK_INT >= 30) {
                    wm.calculateSignalLevel(info.rssi) / wm.maxSignalLevel.coerceAtLeast(1).toFloat()
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.calculateSignalLevel(info.rssi, 4) / 3f
                }
                NetworkStatus(NetKind.Wifi, ssid, level)
            }
            else -> NetworkStatus(NetKind.Ethernet)
        }
    }

    fun volume(context: Context): VolumeStatus {
        val am = context.getSystemService<AudioManager>() ?: return VolumeStatus()
        return VolumeStatus(
            level = am.getStreamVolume(AudioManager.STREAM_MUSIC),
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            muted = if (Build.VERSION.SDK_INT >= 23) am.isStreamMute(AudioManager.STREAM_MUSIC) else legacyMuted,
        )
    }

    /** Android 5.x has no way to read the mute state, so remember what Aerial last set. */
    private var legacyMuted = false

    /**
     * Control Center's slider asks for no volume UI: it is its own, and with FLAG_SHOW_UI TCL's
     * volume bar came up beside it (issue #288). TCL's SystemUI writes the amplifier on every
     * volume change, shown or not: tsdkNative.setVolumeForDevice ran for a change without the flag
     * on MT5879 (Android 11, 1.41), as on the reporter's m7221 (Android 8). Up to 1.40 the flag was
     * there for MT5879, where it had seemed that only a shown bar reached the amplifier.
     * [fromKey]: the guard repeating a held volume key (HomeGuardService.takeVolumeKey), which
     * shows TCL's bar as the key itself does.
     */
    private fun volumeFlags(context: Context, fromKey: Boolean) =
        if (fromKey && isInstalled(context, "com.tcl.settings")) AudioManager.FLAG_SHOW_UI else 0

    /**
     * TCL tcl_m7642 (雷鸟 65S515D, Android 9) has adjustStreamVolume note the change in battery stats,
     * which needs a system permission: it threw and Control Center crashed at the first step (issue
     * #15). Setting the level is the way left there.
     */
    fun adjustVolume(context: Context, direction: Int, fromKey: Boolean = false) {
        val am = context.getSystemService<AudioManager>() ?: return
        val flags = volumeFlags(context, fromKey)
        try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                flags,
            )
        } catch (e: SecurityException) {
            runCatching {
                val level = am.getStreamVolume(AudioManager.STREAM_MUSIC) + if (direction > 0) 1 else -1
                am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), flags)
            }
        }
    }

    fun toggleMute(context: Context) {
        val am = context.getSystemService<AudioManager>() ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            // See adjustVolume: no other way to mute there.
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, volumeFlags(context, fromKey = false)) }
        } else {
            legacyMuted = !legacyMuted
            @Suppress("DEPRECATION")
            am.setStreamMute(AudioManager.STREAM_MUSIC, legacyMuted)
        }
    }

    fun homeStatus(context: Context): HomeStatus {
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        return when {
            resolved == context.packageName -> HomeStatus.Default
            HomeGuardService.isRunning -> HomeStatus.Guarded
            else -> HomeStatus.NotDefault
        }
    }

    // ---- Settings entries: the TV maker's own settings app where there is one ----

    /** Whether [pkg] is the TV's settings app (behind the parental PIN when one is set). */
    fun isSettingsApp(context: Context, pkg: String): Boolean =
        pkg in SETTINGS_PACKAGES ||
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName == pkg

    private val SETTINGS_PACKAGES = setOf("com.android.settings", "com.android.tv.settings", "com.xiaomi.mitv.settings", "com.tcl.settings", "com.helios.setting", "com.haier.tv.menusetting")

    fun settingsIntent(context: Context): Intent =
        xgimiSettingsIntent(context, "Settings://com.xgimi.misckey.main/mainPage") ?: settingsActivityIntent(context)

    private fun settingsActivityIntent(context: Context): Intent =
        firstResolvable(
            context,
            Intent("com.xiaomi.mitv.settings.SETTINGS").setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity")),
            // Haier 65U2 (YunOS 9, Android 9) answers ACTION_SETTINGS with a Bluetooth page that
            // crashes as it opens; its own settings are here (the reporter opened them, issue #458).
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.haier.tv.menusetting", "com.haier.tv.menusetting.RootActivity")),
            Intent(Settings.ACTION_SETTINGS),
            // TCL MT5879 (China): nothing answers ACTION_SETTINGS; MAIN opens TCL's full settings panel.
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
            // PPTV K55U27 (MStar, Android 6): its settings app answers no standard action (issue #264).
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.pptv.setting", "com.pptv.setting.root.SettingsActivity")),
            // XGIMI GMUI (Z7X, Android 9) neither; its settings open by link (issue #241).
            Intent(Intent.ACTION_VIEW, Uri.parse("Settings://com.xgimi.misckey.main/mainPage")),
            WHALEY_SETTINGS,
        )

    // TCL MT5879 answers ACTION_WIFI_SETTINGS with its Wi-Fi side panel.
    /**
     * Where a viewer who installed only the APK (from a USB stick, issue #213) can make Aerial Home
     * without a computer, or null where the TV has no such page: Accessibility for Home Guard
     * (Xiaomi MiTV-MFTR0 has it, TCL MT5879 not), the Home picker (only the standard build is a
     * Home; Xiaomi's firmware ignores the choice), and notification access, which brings Aerial
     * back after a standby kill.
     */
    fun guardSettingsIntent(context: Context): Intent? = resolvable(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun homeSettingsIntent(context: Context): Intent? =
        if (BuildConfig.FLAVOR == "standard") resolvable(context, Intent(Settings.ACTION_HOME_SETTINGS)) else null

    fun revivalSettingsIntent(context: Context): Intent? =
        resolvable(context, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))

    private fun resolvable(context: Context, intent: Intent): Intent? =
        intent.takeIf { context.packageManager.resolveActivity(it, 0) != null }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The network page and, after it, the ones to try if it closes at once. Xiaomi MiTV-ANSP0
     * (Android 9) starts MitvWifiActivity and its MitvActivityManagerService destroys it 0.2 s later,
     * while TV Settings' own network page opens fine (issue #331; MiTV-MFTP0 "flashes", #335).
     * XGIMI's settings service first where it has one (see xgimiSettingsIntent). On a wired
     * connection Xiaomi's Ethernet page comes first: the Wi-Fi page with Wi-Fi off is what a
     * MiTV-MFTP0 (Android 9) viewer saw "crash" (issue #369); MiTV-MFTR0 has the page too.
     */
    fun networkSettingsIntents(context: Context, ethernet: Boolean = false): List<Intent> =
        listOfNotNull(xgimiSettingsIntent(context, XGIMI_NETWORK_PAGE)) + networkSettingsActivityIntents(context, ethernet)

    private fun networkSettingsActivityIntents(context: Context, ethernet: Boolean = false): List<Intent> {
        val wired = listOfNotNull(Intent("com.xiaomi.mitv.settings.ETHERNET_SETTINGS").setPackage("com.xiaomi.mitv.settings").takeIf { ethernet })
        return allResolvable(
            context,
            *wired.toTypedArray(),
            Intent("com.xiaomi.mitv.settings.WIFI_SETTINGS").setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.wifi.MitvWifiActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity")),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            // PPTV K55U27 and XGIMI Z7X answer no standard Wi-Fi action (issues #264, #241).
            Intent("com.pptv.intent.action.WIFI_ACTIVITY").setComponent(ComponentName("com.pptv.setting", "com.pptv.setting.wifi.WifiWorkActivity")),
            Intent(Intent.ACTION_VIEW, Uri.parse("Settings://com.xgimi.settings.net/wifi_misckey")),
            Intent(Settings.ACTION_SETTINGS),
            // TCL tcl_m7642 (Android 9) answers neither Wi-Fi nor settings actions (issue #15).
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
            WHALEY_SETTINGS,
        )
    }

    private val XGIMI_SETTINGS = ComponentName("com.android.newsettings", "com.xgimi.newsettings.SettingService")
    private const val XGIMI_NETWORK_PAGE = "Settings://com.xgimi.settings.net/wifi_misckey"

    // XGIMI Z7X (GMUI 6.11) opens these pages through an exported service rather than an activity;
    // the reporter of issue #241 opened settings, network, inputs and picture through it on a Z7X.
    private fun xgimiSettingsIntent(context: Context, page: String): Intent? {
        val intent = Intent("com.xgimi.settings.SETTINGS").setComponent(XGIMI_SETTINGS)
            .putExtra("data", page)
            .putExtra("packageName", "com.xgimi.misckey")
            .putExtra("unlocked", false)
        return intent.takeIf { context.packageManager.resolveService(it, 0)?.serviceInfo?.exported == true }
    }

    fun startSettingsIntent(context: Context, intent: Intent) {
        val activity = if (intent.component == XGIMI_SETTINGS) {
            val shown = runCatching { context.startService(intent) }.getOrNull() != null
            if (shown) return
            if (intent.getStringExtra("data") == XGIMI_NETWORK_PAGE) {
                networkSettingsActivityIntents(context).first()
            } else {
                settingsActivityIntent(context)
            }
        } else intent
        context.startActivity(settingsLaunchIntent(context, activity))
    }

    /**
     * Whaley (WTV55K1J, Android 5.0) answers no settings action; its network page is not exported,
     * so the network button opens its settings too (issue #292).
     */
    private val WHALEY_SETTINGS
        get() = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.helios.setting", "com.helios.activity.StartActivity"))

    /**
     * Coocaa/Skyworth keep the panel level in their picture middleware and ignore the standard
     * screen_brightness setting (75A7D, 8R134_A7D, Android 11, CCOS 9.0). Their picture page is a
     * side panel the settings app opens by starting SkySetting's exported HalfScreenService with
     * Key = "android.settings.PICTURE_SETTINGS"; the system display settings action resolves to
     * nothing there. See swaiotos.sal.impl.ccos.setting.SettingImpl.startPictureSetting.
     */
    private val SKYWORTH_PICTURE_PANEL = ComponentName(
        "com.skyworth.setting",
        "com.skyworth.setting.service.HalfScreenService",
    )

    fun skyworthPicturePanel(context: Context): Boolean =
        runCatching { context.packageManager.getServiceInfo(SKYWORTH_PICTURE_PANEL, 0) }.isSuccess

    /**
     * Opens the TV's own picture settings where brightness lives, for TVs whose backlight Aerial
     * cannot set itself. TCL keeps it in a side panel that its settings app shows as a service;
     * Skyworth/Coocaa too (see [SKYWORTH_PICTURE_PANEL]).
     * Xiaomi's settings app shows its backlight slider on a broadcast: older Xiaomi TVs have no
     * picture_backlight setting and no display settings screen to open (MiTV-ASTP0, Android 9,
     * found in PR #85; MiTV-MFTP0, Mi TV 4A, #23, #130, #185), and MiTV-MFTR0 has the receiver too.
     */
    fun openPictureSettings(context: Context): Boolean {
        val backlight = Intent("com.xiaomi.mitv.settings.BACKLIGHT_POPUP")
            .setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.SettingsBroadcastReceiver"))
        if (context.packageManager.queryBroadcastReceivers(backlight, 0).any { it.activityInfo.exported }) {
            val shown = runCatching { context.sendBroadcast(backlight) }.isSuccess
            if (shown) return true
        }
        if (isInstalled(context, "com.tcl.settings")) {
            val shown = runCatching {
                context.startService(Intent("com.tcl.settings.SHOW_WINDOW").setPackage("com.tcl.settings"))
            }.getOrNull() != null
            if (shown) return true
        }
        if (skyworthPicturePanel(context)) {
            val shown = runCatching {
                context.startService(
                    Intent().setComponent(SKYWORTH_PICTURE_PANEL)
                        .putExtra("Key", "android.settings.PICTURE_SETTINGS"),
                )
            }.getOrNull() != null
            if (shown) return true
        }
        return runCatching {
            context.startActivity(
                firstResolvable(
                    context,
                    Intent(Settings.ACTION_DISPLAY_SETTINGS),
                    // PPTV K55U27: picture and sound, where its brightness is (issue #264).
                    Intent("com.pptv.intent.action.PICTUREAUDIO_ACTIVITY")
                        .setComponent(ComponentName("com.pptv.setting", "com.pptv.setting.media.PictureAudioSettingActivity")),
                    settingsActivityIntent(context),
                ),
            )
        }.isSuccess
    }

    /**
     * Opens the TV's own input picker, for TVs whose inputs Android does not list. TCL MT5879 keeps
     * its inputs in its own middleware (TvInputManager has none) and its remote's input key opens a
     * side panel: SystemUI's status bar service where the firmware has the custom source panel,
     * TCL settings' otherwise (TclShortCutKeyManager.startTVInputService).
     */
    fun openInputPicker(context: Context): Boolean {
        val pm = context.packageManager
        // Hisense VIDAA_TV (MT9653, Android 11) lists no inputs either; its input panel is a
        // foreground service (issue #261, tried on the reporter's TV).
        val hisense = Intent("com.hisense.tv.start_inputs")
            .setComponent(ComponentName("com.hisense.tv.inputs", "com.hisense.tv.InputsUIService"))
        if (pm.resolveService(hisense, 0) != null) {
            val shown = runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(hisense) else context.startService(hisense)
            }.getOrNull() != null
            if (shown) return true
        }
        tclInputPanel(context)?.let { intent ->
            if (runCatching { context.startService(intent) }.getOrNull() != null) return true
        }
        // Older Coocaa firmware (Skyworth 5H51S_N6 and 8A20 V8E, Android 5.1) keeps its inputs in
        // the TV middleware with no provider, but any app may open its source dialog, which the
        // remote's input key also shows (issues #519, #582, tried by both reporters with adb).
        val coocaa = Intent("coocaa.intent.action.SOURCE_DIALOG").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.resolveActivity(coocaa, 0) }.getOrNull() == null) return false
        return runCatching { context.startActivity(coocaa) }.isSuccess
    }

    /**
     * TCL's own input panel, where the firmware has one. The remote's input key opens it by itself,
     * before Home sees the key (TCL MT5879, PR #334; issue #108).
     */
    fun tclInputPanel(context: Context): Intent? {
        val pm = context.packageManager
        val intent = if (pm.hasSystemFeature("com.tcl.software.systemui_custom_tv_source")) {
            Intent("com.tcl.tvstatusbar.action.SHOW_WINDOW").setPackage("com.android.systemui")
                .putExtra("TvStatusBar_Type", "Source")
                .putExtra("TvStatusBar_StartFrom", "input")
        } else {
            Intent("com.tcl.insrc.SHOW_WINDOW").setPackage("com.tcl.settings")
        }
        return intent.takeIf { runCatching { pm.resolveService(it, 0) }.getOrNull() != null }
    }

    /**
     * Coocaa's Quick Panel (Skyworth 75A7D, 8R134_A7D, Android 11): the firmware opens its own
     * signal-source panel on the remote's input key, from a window of com.coocaa.os.ccosservice,
     * and lets the key through as well, so Aerial's Inputs page was drawn under it.
     */
    fun coocaaInputPanel(context: Context): Boolean = runCatching {
        context.packageManager.getServiceInfo(
            ComponentName(
                "com.coocaa.os.ccosservice",
                "com.coocaa.os.controlcenter.quicksettings.service.FeatureService",
            ),
            0,
        )
    }.isSuccess

    private fun firstResolvable(context: Context, vararg intents: Intent): Intent {
        val pm = context.packageManager
        // Not Intent.resolveActivity: it returns an explicit component unchecked, so Xiaomi's
        // component was picked on TCL too, where it does not exist.
        return (intents.firstOrNull { pm.resolveActivity(it, 0) != null } ?: intents.last())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .let { settingsLaunchIntent(context, it) }
    }

    /** Every one of [intents] that resolves, each to a different activity; the last one if none does. */
    private fun allResolvable(context: Context, vararg intents: Intent): List<Intent> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        return intents
            .filter { intent ->
                val info = pm.resolveActivity(intent, 0)?.activityInfo ?: return@filter false
                seen.add("${info.packageName}/${info.name}")
            }
            .ifEmpty { listOf(intents.last()) }
            .map { settingsLaunchIntent(context, Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ---- Inputs ----

    private const val XIAOMI_PLAYER = "com.xiaomi.mitv.tvplayer"
    private const val DROIDLOGIC_PLAYER = "com.droidlogic.tvsource"

    /** CEC logical-device placeholders some input services report instead of the device's name. */
    private val GENERIC_CEC_NAME = Regex("^(Playback|Recorder|Tuner|Audio ?System|Audio|TV|Reserved)[ _]?\\d*$", RegexOption.IGNORE_CASE)

    /**
     * Physical inputs from the TV input framework, routed through the maker's own player where the
     * generic passthrough intent has no handler (Xiaomi's China firmware).
     */
    fun sources(context: Context): List<TvSource> = frameworkSources(context)
        .ifEmpty { KonkaSources.load(context) }
        .ifEmpty { tclSources(context) }
        .ifEmpty { whaleySources(context) }
        .ifEmpty { skyworthSources(context) } + listOfNotNull(usbSource(context))

    /**
     * The TV's own player for USB drives, offered as one more input: owners switch the TV on to
     * watch what is on a drive, and look for it with the HDMI ports. Xiaomi's 高清播放器 (verified on
     * MiTV-MFTR0) and TCL's 媒体中心 (MT5879) both list the drives plugged in on their first page.
     */
    private fun usbSource(context: Context): TvSource? {
        val player = USB_PLAYERS.firstOrNull { runCatching { context.packageManager.getActivityInfo(it, 0) }.isSuccess } ?: return null
        return TvSource(
            id = USB_SOURCE_ID,
            label = "USB",
            kind = SourceKind.Usb,
            // Whether a drive is in is not followed: the list is read once with the apps.
            connected = false,
            deviceName = null,
            intent = Intent(Intent.ACTION_MAIN).setComponent(player)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }

    const val USB_SOURCE_ID = "usb"

    private val USB_PLAYERS = listOf(
        ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"),
        ComponentName("com.tcl.ui_mediaCenter", "com.tcl.ui_mediaCenter.main.MainActivity"),
    )

    private fun frameworkSources(context: Context): List<TvSource> {
        val tim = context.getSystemService<TvInputManager>() ?: return emptyList()
        val infos = runCatching { tim.tvInputList }.getOrNull() ?: return emptyList()
        val xiaomi = isInstalled(context, XIAOMI_PLAYER)
        val tclPlayer = tclPlayer(context)
        val profile = DeviceProfile.current()

        return infos
            .filter { it.parentId == null && (it.isPassthroughInput || it.type == TvInputInfo.TYPE_TUNER) }
            .filter { Build.VERSION.SDK_INT < 24 || runCatching { !it.isHidden(context) }.getOrDefault(true) }
            .filter { hw(it.id) !in profile.hiddenHardware }
            .mapNotNull { info ->
                val kind = when (info.type) {
                    TvInputInfo.TYPE_HDMI -> SourceKind.Hdmi
                    TvInputInfo.TYPE_DISPLAY_PORT -> SourceKind.DisplayPort
                    TvInputInfo.TYPE_TUNER -> SourceKind.Tuner
                    TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_COMPONENT -> SourceKind.Av
                    else -> SourceKind.Other
                }
                val label = runCatching { info.loadLabel(context).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val state = runCatching { tim.getInputState(info.id) }.getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED)
                // CEC devices register as child inputs of the port they are plugged into. Opening
                // the device's input rather than the port's has the TV select the device over CEC,
                // which wakes it from standby as picking it in the TV's own list does; the port's
                // only switches the picture (Sony BRAVIA XR90 with an Apple TV and a PS5, issue #259).
                val cec = infos.firstOrNull { it.parentId == info.id }
                val generic = genericRoute(cec ?: info, kind)
                val intent = (if (xiaomi) xiaomiRoute(info, kind, label, profile) else null)
                    ?: generic.takeIf { resolves(context, it) }
                    ?: droidLogicRoute(context, info)
                    ?: tclPlayer?.let { tclRoute(it, kind, label) }
                    ?: generic
                val device = cec
                    ?.let { runCatching { it.loadLabel(context).toString() }.getOrNull() }
                    ?.takeIf { it.isNotBlank() && !GENERIC_CEC_NAME.matches(it) }
                TvSource(
                    id = info.id,
                    label = profile.labels[hw(info.id)] ?: label,
                    kind = kind,
                    connected = device != null || state == TvInputManager.INPUT_STATE_CONNECTED && kind != SourceKind.Tuner,
                    deviceName = device,
                    intent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            .let { tunersApart(context, it) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
    }

    /**
     * Two tuners under one name, told apart by their services: Sony BRAVIA 4K AE2 (Android 14) has
     * a DVB tuner and MediaTek's analog one, both labelled 电视, and listed two the same (issue #433).
     */
    private fun tunersApart(context: Context, sources: List<TvSource>): List<TvSource> {
        val shared = sources.filter { it.kind == SourceKind.Tuner }.groupBy { it.label }.filterValues { it.size > 1 }.keys
        if (shared.isEmpty()) return sources
        return sources.map { source ->
            if (source.kind != SourceKind.Tuner || source.label !in shared) return@map source
            val service = source.id.substringBefore("/HW").lowercase()
            when {
                "analog" in service || "atv" in service -> source.copy(label = context.getString(R.string.input_antenna))
                "dvb" in service || "dtv" in service || "dtmb" in service || "digital" in service ->
                    source.copy(label = context.getString(R.string.input_digital))
                else -> source
            }
        }
    }

    /**
     * Amlogic's DroidLogic player, for firmware where nothing handles the framework's passthrough
     * VIEW intent: it takes the input id as an extra. OBE_NEW X7 projector (Android 9): HDMI2 opened
     * with its HW id. Only the port's id is verified there, not a CEC child's.
     */
    private fun droidLogicRoute(context: Context, info: TvInputInfo): Intent? {
        if (info.type != TvInputInfo.TYPE_HDMI) return null
        val intent = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName(DROIDLOGIC_PLAYER, "$DROIDLOGIC_PLAYER.DroidLogicTv"),
        ).putExtra("id", info.id)
        val activity = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return null
        return intent.takeIf {
            activity.exported && activity.enabled && activity.applicationInfo.enabled &&
                (activity.permission == null || ContextCompat.checkSelfPermission(context, activity.permission) == PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun resolves(context: Context, intent: Intent) =
        runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)

    private const val TCL_PLAYER = "com.tcl.tv"

    private fun tclPlayer(context: Context): ComponentName? =
        ComponentName(TCL_PLAYER, "$TCL_PLAYER.TVActivity")
            .takeIf { runCatching { context.packageManager.getActivityInfo(it, 0) }.isSuccess }

    private fun tclIntent(player: ComponentName, ordinal: Int): Intent = Intent().setComponent(player)
        .putExtra("targetSourceId", ordinal)
        .putExtra("referer", "com.tcl.settings.module_source")

    /**
     * FFALCON/TCL T966D5 (Android 14) lists its inputs in the framework, but nothing there opens
     * the passthrough intent: every input said this TV could not switch to it (issue #395). TCL's
     * player is tried there with the ordinals MT5879 uses (see tclSources), chosen by the input's
     * name; not verified on that TV.
     */
    private fun tclRoute(player: ComponentName, kind: SourceKind, label: String): Intent? {
        val ordinal = when (kind) {
            SourceKind.Hdmi -> Regex("(\\d)").find(label)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..4 }?.let { 7 + it }
            SourceKind.Tuner -> 2
            SourceKind.Av -> 3
            else -> null
        } ?: return null
        return tclIntent(player, ordinal)
    }

    /**
     * TCL keeps its inputs in its own middleware, so the framework lists none (MT5879, China). Its
     * player switches to any of them when started with the input's EnTCLInputSource ordinal, which
     * is what TCL's own input panel does (SystemUI's ShareUtils.startLiveTV). The ports are the ones
     * MT5879 has; the firmware does not tell an app which are connected or what is plugged in.
     */
    private fun tclSources(context: Context): List<TvSource> {
        val player = tclPlayer(context) ?: return emptyList()
        fun source(name: String, ordinal: Int, kind: SourceKind, label: String) = TvSource(
            id = "tcl:$name",
            label = label,
            kind = kind,
            connected = false,
            deviceName = null,
            intent = tclIntent(player, ordinal).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return listOf(
            source("HDMI1", 8, SourceKind.Hdmi, "HDMI 1"),
            source("HDMI2", 9, SourceKind.Hdmi, "HDMI 2"),
            source("HDMI3", 10, SourceKind.Hdmi, "HDMI 3"),
            source("HDMI4", 11, SourceKind.Hdmi, "HDMI 4"),
            source("DTV", 2, SourceKind.Tuner, context.getString(R.string.input_live_tv)),
            source("AV1", 3, SourceKind.Av, context.getString(R.string.input_av)),
        )
    }

    private const val WHALEY_PLAYER = "com.whaley.tv.tvplayer.ui"

    /**
     * Whaley (微鲸 WTV55K1J, MStar, Android 5.0) registers no inputs either. Its player switches when
     * started with the input's ordinal, which the reporter of issue #292 found port by port: HDMI 1–3
     * are 4–6 and AV is 7 (0–3 are the tuners, which that TV has no hardware for). Not tried here.
     */
    private fun whaleySources(context: Context): List<TvSource> {
        val player = ComponentName(WHALEY_PLAYER, "com.whaley.tv.tvplayer.ui.RootActivity")
        if (runCatching { context.packageManager.getActivityInfo(player, 0) }.isFailure) return emptyList()
        fun source(ordinal: Int, kind: SourceKind, label: String) = TvSource(
            id = "whaley:$ordinal",
            label = label,
            kind = kind,
            connected = false,
            deviceName = null,
            intent = Intent("com.whaley.tv.tvplayer.ui.START_TV_PLAYER").setComponent(player)
                .putExtra("inputSrc", ordinal)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return listOf(
            source(4, SourceKind.Hdmi, "HDMI 1"),
            source(5, SourceKind.Hdmi, "HDMI 2"),
            source(6, SourceKind.Hdmi, "HDMI 3"),
            source(7, SourceKind.Av, context.getString(R.string.input_av)),
        )
    }

    private const val SKYWORTH_PLAYER = "com.skyworth.tv"
    private val SKYWORTH_SOURCES = Uri.parse("content://com.skyworth.tv.sdk.provider/system/source_list")

    /**
     * Skyworth's Coocaa firmware (75A7D, RTD2885N, Android 11) lists no framework inputs either.
     * Its TV SDK provider keeps the ports (a projection is required or it returns no cursor), and
     * its TV activity switches when started with the source's name and index in a "source" bundle
     * (SkyTVMainActivity.p hands it to SkyTVManager.J2).
     */
    private fun skyworthSources(context: Context): List<TvSource> {
        val player = ComponentName(SKYWORTH_PLAYER, "$SKYWORTH_PLAYER.activity.SkyTVMainActivity")
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull()
            ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        return runCatching {
            val sources = mutableListOf<TvSource>()
            context.contentResolver.query(
                SKYWORTH_SOURCES,
                arrayOf("sourceName", "sourceIndex", "displayName", "signalState"),
                null,
                null,
                null,
            )?.use { cursor ->
                val name = cursor.getColumnIndexOrThrow("sourceName")
                val index = cursor.getColumnIndexOrThrow("sourceIndex")
                val display = cursor.getColumnIndexOrThrow("displayName")
                val signal = cursor.getColumnIndexOrThrow("signalState")
                while (cursor.moveToNext()) {
                    val sourceName = cursor.getString(name)?.takeIf { it.isNotBlank() }
                    if (sourceName != null) {
                        val sourceIndex = cursor.getInt(index)
                        val label = cursor.getString(display)?.takeIf { it.isNotBlank() } ?: sourceName
                        sources += TvSource(
                            id = "skyworth:$sourceName:$sourceIndex",
                            label = label,
                            kind = skyworthKind(sourceName),
                            // signalState is 1 for every listed port, connected or not.
                            connected = false,
                            deviceName = null,
                            intent = Intent().setComponent(player)
                                .putExtra("source", Bundle().apply {
                                    putString("name", sourceName)
                                    putString("displayname", label)
                                    putInt("index", sourceIndex)
                                    putInt("signal", cursor.getInt(signal))
                                })
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            sources.distinctBy { it.id }.sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
        }.getOrDefault(emptyList())
    }

    /** VOD is Skyworth's name for the tuner source its TV activity opens as 电视. */
    private fun skyworthKind(name: String): SourceKind = when {
        name.startsWith("HDMI") -> SourceKind.Hdmi
        name == "DP" -> SourceKind.DisplayPort
        name == "VOD" || name.startsWith("DTV") || name.startsWith("ATV") || name.startsWith("DVB") ||
            name == "DTMB" -> SourceKind.Tuner
        name.startsWith("AV") || name.startsWith("YUV") || name.startsWith("YPBPR") || name.startsWith("VGA") ||
            name.startsWith("SVIDEO") -> SourceKind.Av
        else -> SourceKind.Other
    }

    private fun hw(id: String): Int? = Regex("/HW(\\d+)$").find(id)?.groupValues?.get(1)?.toIntOrNull()

    private fun xiaomiRoute(info: TvInputInfo, kind: SourceKind, label: String, profile: DeviceProfile): Intent? {
        val n = hw(info.id) ?: return null
        return when (kind) {
            // MediaTek hardware ids HW5.. map onto Xiaomi's source ids 23.. for HDMI. Models whose
            // numbers were found on the TV have them in their profile (MiTV-ANTL0, #64); else the
            // firmware's own hdmiN_order, where it has one, beats the general rule (PR #571).
            SourceKind.Hdmi -> Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                .putExtra(
                    "input",
                    profile.xiaomiSourceIds[n] ?: xiaomiConfiguredSource(label)
                        ?: profile.xiaomiFallbackSourceIds[n] ?: (n + 18),
                )
            // DP and USB-C numbers do not follow the HDMI ones (#62), so only models whose numbers
            // are known get a route; the rest fall back to the generic intent.
            SourceKind.DisplayPort -> profile.xiaomiSourceIds[n]?.let {
                Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                    .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                    .putExtra("input", it)
            }
            SourceKind.Av -> Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                .putExtra("input", 2)
            // MediaTek has the digital tuner at HW0 and the analogue one at HW1 (MiTV-MFTP0 labels
            // them "Tuner(DTV)" and "Tuner(ATV)"); Amlogic has "ATV" at HW0 and "DTV" at HW10
            // (MiTV4-ANSM0, issue #291), so the label decides where it names one.
            SourceKind.Tuner -> if (isDigitalTuner(label, n)) {
                Intent("com.xiaomi.mitv.tvplayer.DTMB_PLAY").setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.dtmb.DTMBActivity"))
            } else {
                Intent("com.xiaomi.mitv.tvplayer.ATV_PLAY").setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.AtvActivity"))
            }
            SourceKind.Usb, SourceKind.Other -> null
        }
    }

    /**
     * Some Xiaomi firmwares number the sources by board, even within one model: their own player
     * asks mitv.common.ConfigurationManager for hdmiN_order (MiTV4-ANSM0 8.9.19.854: hdmi3_order
     * is 25, and source 25 opens HDMI 3; PR #571, #492). Read by reflection, so TVs without the
     * vendor framework return null. [label] is the framework's own label, which names the port.
     */
    private fun xiaomiConfiguredSource(label: String): Int? {
        val port = Regex("HDMI\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return runCatching {
            val configuration = Class.forName("mitv.common.ConfigurationManager")
            val manager = configuration.getMethod("getInstance").invoke(null)
            configuration.getMethod("queryTvConfigByKey", String::class.java)
                .invoke(manager, "hdmi${port}_order") as? String
        }.getOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun isDigitalTuner(label: String, hw: Int): Boolean = when {
        label.contains("ATV", ignoreCase = true) -> false
        label.contains("DTV", ignoreCase = true) || label.contains("DTMB", ignoreCase = true) -> true
        else -> hw == 0
    }

    private fun genericRoute(info: TvInputInfo, kind: SourceKind): Intent =
        if (kind == SourceKind.Tuner) {
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.id))
        } else {
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
        }

    private fun isInstalled(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}

/**
 * Per-model corrections the framework cannot tell us. MiTV-MFTR0 registers a fourth HDMI input
 * (HW8) with no connector behind it, and its two tuners are better named by what they receive.
 * MiTV-MFFU1 (Redmi monitor G Pro 27U) registers its DP port (HW9) and its USB-C port (HW10) both
 * as DisplayPort; its tvplayer knows them as source 29 and 30 (#62, found in its MonitorUtils).
 * MiTV-ANTL0 (L42R7-RA, Amlogic, Android 5.1) registers only one HDMI input, HW6, labelled
 * HDMI2, but it is the HDMI 1 port and source 23 (#64, from the reporter's logcat).
 * MiTV4-ANSM0 (Mi TV 4, Amlogic, Android 6.0.1) registers an HDMI input only for a port with a
 * device in it. On older firmwares HW7 is source 23 on some boards (#191, the reporter scanned
 * sources 1–32; Mi TV 4A, L32M5-AZ, #313) and 25 on others (#492), which the firmware's
 * hdmi3_order tells apart; 23 stays the fallback. Firmware 22.x numbers the ports as the general
 * rule does (#375).
 * MiTV-MFZP0 (77" OLED, mt9950, Android 9) registers HW4–HW7 as HDMI 1–4 but has three ports:
 * HW7 has none behind it, and its tvplayer numbers them hw + 19 (#311, the reporter's local build).
 */
private class DeviceProfile(
    val hiddenHardware: Set<Int?> = emptySet(),
    val labels: Map<Int?, String> = emptyMap(),
    /** Xiaomi tvplayer source ids for inputs that have no rule or another one, by hardware id. */
    val xiaomiSourceIds: Map<Int, Int> = emptyMap(),
    /** Like [xiaomiSourceIds], but only where the firmware has no hdmiN_order of its own. */
    val xiaomiFallbackSourceIds: Map<Int, Int> = emptyMap(),
) {
    companion object {
        fun current(): DeviceProfile = when (Build.MODEL) {
            // NSOP0 / Novatek, firmware 22.7.26.1365: real input sessions map 23/24/25 to HW6/5/4.
            "MiTV_NSOP0", "MiTV-NSOP0" -> DeviceProfile(
                labels = mapOf(6 to "HDMI 1", 5 to "HDMI 2", 4 to "HDMI 3", 3 to "TV", 1 to "DTMB", 0 to "AV"),
                xiaomiSourceIds = mapOf(6 to 23, 5 to 24, 4 to 25),
            )
            "MiTV_MFTR0", "MiTV-MFTR0" -> DeviceProfile(
                hiddenHardware = setOf(8),
                labels = mapOf(1 to "ATV", 0 to "DTMB", 2 to "AV", 6 to "HDMI 2 (eARC)"),
            )
            "MiTV_MFFU1", "MiTV-MFFU1" -> DeviceProfile(
                labels = mapOf(9 to "DP", 10 to "USB-C"),
                xiaomiSourceIds = mapOf(9 to 29, 10 to 30),
            )
            "MiTV_ANTL0", "MiTV-ANTL0" -> DeviceProfile(
                labels = mapOf(6 to "HDMI 1"),
                xiaomiSourceIds = mapOf(6 to 23),
            )
            // Firmware 22.x knows each port by its own number, and HW7 is HDMI 3 = 25 by the
            // general rule (MiTV4-ANSM0 22.4.15.3234, issue #375: 23/24/25 opened HDMI 1/2/3).
            "MiTV4-ANSM0", "MiTV4_ANSM0", "MiTV4A" ->
                if (xiaomiFirmwareMajor() >= 22) DeviceProfile()
                else DeviceProfile(xiaomiFallbackSourceIds = mapOf(7 to 23))
            "MiTV_MFZP0", "MiTV-MFZP0" -> DeviceProfile(
                hiddenHardware = setOf(7),
                xiaomiSourceIds = mapOf(4 to 23, 5 to 24, 6 to 25),
            )
            else -> DeviceProfile()
        }

        /** The first number of a Xiaomi build id such as 22.4.15.3234, or 0. */
        private fun xiaomiFirmwareMajor(): Int = Build.DISPLAY.substringBefore('.').toIntOrNull() ?: 0
    }
}
