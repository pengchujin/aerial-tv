package app.orchard.tv.system

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.orchard.tv.BuildConfig
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.guard.HomeGuardService
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.PhoneServer
import app.orchard.tv.wallpaper.Wallpapers

/**
 * What a maintainer without the reporter's TV needs to adapt Aerial to it, as Markdown to paste
 * into a GitHub issue (.github/ISSUE_TEMPLATE/device.yml). Shown on the phone page and printed by
 *   adb shell dumpsys activity app.orchard.tv/.MainActivity report
 * which tools/report.sh adds to what only a shell can read.
 *
 * Nothing personal: no account, serial number, Wi-Fi name, address, phone page token, weather city
 * or list of installed apps (only whether the packages Aerial treats specially are there). Log lines
 * have addresses masked, since a film URL or a peer can turn up in them.
 */
object Diagnostics {

    /** Packages Aerial looks for somewhere, by what they mean for it. */
    private val VENDOR_PACKAGES = listOf(
        "com.mitv.tvhome", "com.xiaomi.mitv.settings", "com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.hyper.screensaver",
        "com.tcl.settings", "com.tcl.tv", "com.tcl.MultiScreenInteraction_TV", "com.tcl.appmarket2",
        "com.hisense.vidaaassistant", "com.jamdeo.tv.vod",
        "com.tianci.tv", "com.skyworth.tv", "com.coocaa.os.launcher",
        "com.konka.tvsettings", "com.stv.deskplatform",
        "com.sony.dtv.configsettings",
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.android.tv.settings",
        "com.dangbeimarket",
    )

    // AerialDiag is in DiagLog already; AerialWeather names the city.
    private val LOG_TAGS = listOf("AerialGuard", "AerialUpdates", "AerialStream", "AerialWallpaper", "AerialKonkaSources", "AndroidRuntime")

    fun report(context: Context): String = buildString {
        val pm = context.packageManager
        appendLine("### Aerial diagnostics")
        appendLine()
        appendLine("```")
        appendLine("Aerial      ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR}, package ${context.packageName}, installer ${installer(context) ?: "-"}")
        appendLine("Device      ${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL} (device ${Build.DEVICE}, product ${Build.PRODUCT}, hardware ${Build.HARDWARE}, board ${Build.BOARD})")
        appendLine("Android     ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" + if (Build.VERSION.SDK_INT >= 23) ", patch ${Build.VERSION.SECURITY_PATCH}" else "")
        appendLine("Build       ${Build.DISPLAY}")
        appendLine("Fingerprint ${Build.FINGERPRINT}")
        listOf("ro.board.platform", "ro.product.cpu.abi", "ro.config.low_ram", "ro.build.characteristics", "persist.vt.use_home_pkg")
            .forEach { key -> prop(key)?.takeIf { it.isNotEmpty() }?.let { appendLine("  $key = $it") } }
        appendLine("Screen      ${screen(context)}")
        appendLine("Memory      ${memory(context)}")
        appendLine("Network     ${SystemProbe.network(context).kind}")
        appendLine("```")

        appendLine()
        appendLine("**Home**")
        appendLine("```")
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        appendLine("Resolves to ${resolved?.let { "${it.packageName}/${it.name}" } ?: "-"}  (${SystemProbe.homeStatus(context)})")
        runCatching { pm.queryIntentActivities(homeIntent, 0) }.getOrDefault(emptyList()).forEach {
            appendLine("  candidate ${it.activityInfo.packageName}/${it.activityInfo.name} priority=${it.priority}")
        }
        HomeGuardService.stockHomes()?.forEach { (pkg, classes) -> appendLine("  guard keeps away $pkg: ${classes.joinToString()}") }
        appendLine("```")

        appendLine()
        appendLine("**Takeover**")
        appendLine("```")
        val accessibility = secure(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        appendLine("Home Guard  enabled=${accessibility?.contains("${context.packageName}/") == true} running=${HomeGuardService.isRunning}")
        appendLine("  accessibility services: ${accessibility ?: "-"}")
        appendLine("Revival     notification listener allowed=${NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)}, revivals in 10 min=${GuardSetup.revivedOften(context).let { if (it) "3 or more" else "fewer than 3" }}")
        appendLine("Grants      WRITE_SECURE_SETTINGS=${granted(context, Manifest.permission.WRITE_SECURE_SETTINGS)}" +
            " overlay=${Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)}" +
            " WRITE_SETTINGS=${Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(context)}" +
            " install=${Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()}" +
            " location=${granted(context, Manifest.permission.ACCESS_FINE_LOCATION)}")
        appendLine("ADB         adb_enabled=${global(context, Settings.Global.ADB_ENABLED)} keep_adb=${GuardSetup.keepAdb(context)}")
        appendLine("Screensaver enabled=${secure(context, "screensaver_enabled")} components=${secure(context, "screensaver_components")} Aerial's=${Wallpapers.screensaver}")
        appendLine("Sounds      sound_effects_enabled=${system(context, Settings.System.SOUND_EFFECTS_ENABLED)}")
        appendLine("Settings    focus=${AerialSettings.focusStyle.key} one_row=${AerialSettings.oneRowHome} peek=${AerialSettings.peekNextRow} names=${AerialSettings.nameMode.key} boot_source=${AerialSettings.bootSource != null} boot_app=${AerialSettings.bootApp}")
        appendLine("```")

        appendLine()
        appendLine("**Brightness**")
        appendLine("```")
        appendLine("Aerial can set it: ${Brightness.level(context) != null}")
        appendLine("  global picture_backlight=${global(context, "picture_backlight")}  system screen_brightness=${system(context, Settings.System.SCREEN_BRIGHTNESS)} mode=${system(context, Settings.System.SCREEN_BRIGHTNESS_MODE)}")
        appendLine("```")

        appendLine()
        appendLine("**Inputs**")
        appendLine("```")
        inputs(context).forEach { appendLine(it) }
        SystemProbe.sources(context).forEach { s ->
            appendLine("  shown: ${s.label} [${s.kind}] connected=${s.connected} -> ${s.intent.component?.flattenToShortString() ?: s.intent.data}")
        }
        appendLine("```")

        appendLine()
        appendLine("**Packages**")
        appendLine("```")
        VENDOR_PACKAGES.forEach { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@forEach
            appendLine("$pkg ${info.versionName}" + if (info.applicationInfo?.enabled == false) " (disabled)" else "")
        }
        appendLine("```")

        appendLine()
        appendLine("<details><summary>Log</summary>")
        appendLine()
        appendLine("```")
        DiagLog.recent(150).forEach { appendLine(mask(it)) }
        appendLine("--- logcat")
        logcat().forEach { appendLine(mask(it)) }
        appendLine("```")
        appendLine()
        appendLine("</details>")
    }

    private fun inputs(context: Context): List<String> {
        val tim = context.getSystemService<TvInputManager>() ?: return listOf("no TvInputManager")
        val list = runCatching { tim.tvInputList }.getOrNull() ?: return listOf("TvInputManager refused the list")
        if (list.isEmpty()) return listOf("TvInputManager lists no inputs")
        return list.map { i ->
            val state = runCatching { tim.getInputState(i.id) }.getOrDefault(-1)
            val label = runCatching { i.loadLabel(context).toString() }.getOrDefault("?")
            "${i.id} type=${inputType(i.type)} \"$label\" state=$state" +
                (i.parentId?.let { " parent=$it" } ?: "") + if (i.isPassthroughInput) " passthrough" else ""
        }
    }

    private fun inputType(type: Int) = when (type) {
        TvInputInfo.TYPE_TUNER -> "tuner"
        TvInputInfo.TYPE_HDMI -> "hdmi"
        TvInputInfo.TYPE_COMPOSITE -> "composite"
        TvInputInfo.TYPE_COMPONENT -> "component"
        TvInputInfo.TYPE_SVIDEO -> "svideo"
        TvInputInfo.TYPE_VGA -> "vga"
        TvInputInfo.TYPE_DISPLAY_PORT -> "dp"
        TvInputInfo.TYPE_DVI -> "dvi"
        else -> type.toString()
    }

    @Suppress("DEPRECATION")
    private fun screen(context: Context): String {
        val display = context.getSystemService<WindowManager>()?.defaultDisplay ?: return "-"
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        val modes = if (Build.VERSION.SDK_INT >= 23) display.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" } else "-"
        return "${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi ${"%.0f".format(display.refreshRate)}Hz; modes $modes"
    }

    private fun memory(context: Context): String {
        val am = context.getSystemService<ActivityManager>() ?: return "-"
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${info.totalMem / (1 shl 20)} MB, low-RAM device=${am.isLowRamDevice}"
    }

    @Suppress("DEPRECATION")
    private fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else context.packageManager.getInstallerPackageName(context.packageName)
    }.getOrNull()

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // Android 12 and later refuse some keys outside the public API; those show as "unreadable".
    private fun secure(context: Context, key: String) = runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrElse { "unreadable" }
    private fun global(context: Context, key: String) = runCatching { Settings.Global.getString(context.contentResolver, key) }.getOrElse { "unreadable" }
    private fun system(context: Context, key: String) = runCatching { Settings.System.getString(context.contentResolver, key) }.getOrElse { "unreadable" }

    private fun prop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    /** This process's own lines (all an app may read), from Aerial's tags and crashes. */
    private fun logcat(): List<String> = runCatching {
        val args = listOf("logcat", "-d", "-v", "time", "-t", "400") + LOG_TAGS.map { "$it:V" } + "*:S"
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().use { it.readLines() }
        // logcat -d ends by itself once it has printed the buffer.
        p.destroy()
        lines.takeLast(200)
    }.getOrElse { listOf("logcat unreadable: $it") }

    private val IPV4 = Regex("""(?<![\w.])(\d{1,3}\.){3}\d{1,3}(?![\w.])""")
    private val MAC = Regex("""(?i)\b([0-9a-f]{2}:){5}[0-9a-f]{2}\b""")
    private val PHONE_PAGE = Regex(""":${PhoneServer.PORT}/\w+""")

    /** Addresses out of a log line: a film URL's host stays, a LAN or peer address does not. */
    fun mask(line: String): String = line
        .replace(PHONE_PAGE, ":${PhoneServer.PORT}/<token>")
        .replace(IPV4, "<ip>")
        .replace(MAC, "<mac>")
}
