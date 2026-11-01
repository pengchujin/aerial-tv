package app.orchard.tv.lite

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * Panel brightness, as the full app's system/Brightness.kt finds it: Xiaomi's picture_backlight
 * (Settings.Global, Android 4.2 on, with WRITE_SECURE_SETTINGS), the MiTV middleware's backlight
 * (MitvPanel, shared with the full app), TCL's TTvPictureManager, else the standard screen
 * brightness (WRITE_SETTINGS is granted at install before Android 6.0). Null where Lite cannot
 * set it; Control Center then opens the TV's own picture settings.
 */
object Brightness {
    private const val XIAOMI_BACKLIGHT = "picture_backlight"
    private var mitvLevel: Int? = null

    private fun installed(context: Context, pkg: String) = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun usesXiaomiBacklight(context: Context) = Build.VERSION.SDK_INT >= 17 &&
        // MiTV-MFTP0 has picture_backlight but ignores it (the full app's issues #284, #335).
        !DeviceProfile.current(context).ignoresPictureBacklight &&
        runCatching { Settings.Global.getString(context.contentResolver, XIAOMI_BACKLIGHT) }.getOrNull() != null

    private fun storedLevel(context: Context): Int? =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("mitv_backlight", -1).takeIf { it in 1..100 }

    /** Current level as 0..1, or null when Lite cannot change it on this TV. */
    fun level(context: Context): Float? {
        val r = context.contentResolver
        if (installed(context, "com.tcl.settings")) TclPicture.backlight(context)?.let { return it / 100f }
        return when {
            usesXiaomiBacklight(context) -> {
                if (context.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return null
                Settings.Global.getInt(r, XIAOMI_BACKLIGHT, 50) / 100f
            }
            MitvPanel.usable(context) -> (mitvLevel ?: MitvPanel.level(context) ?: storedLevel(context) ?: 50) / 100f
            // A Xiaomi TV where neither works: the TV's own slider rather than one that changes nothing.
            installed(context, "com.xiaomi.mitv.settings") -> null
            installed(context, "com.tcl.settings") || installed(context, "com.sony.dtv.configsettings") -> null
            DeviceProfile.current(context).ignoresScreenBrightness -> null
            Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context) -> null
            else -> runCatching { Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f }.getOrNull()
        }
    }

    /** One notch (5%) up or down, never to 0 (Xiaomi turns the backlight off there, issue #174). */
    fun adjust(context: Context, direction: Int): Float? {
        val current = level(context) ?: return null
        val percent = Math.round(current * 100)
        val next = (if (direction > 0) (percent / 5 + 1) * 5 else ((percent + 4) / 5 - 1) * 5).coerceIn(1, 100)
        val r = context.contentResolver
        if (!installed(context, "com.tcl.settings") && !usesXiaomiBacklight(context) && MitvPanel.usable(context)) {
            runCatching {
                val from = mitvLevel ?: MitvPanel.level(context) ?: storedLevel(context) ?: percent
                MitvPanel.setBacklight(context, from, next)
                mitvLevel = next
                context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("mitv_backlight", next).apply()
            }
            return next / 100f
        }
        runCatching {
            when {
                installed(context, "com.tcl.settings") && TclPicture.backlight(context) != null -> TclPicture.setBacklight(context, next)
                usesXiaomiBacklight(context) -> Settings.Global.putInt(r, XIAOMI_BACKLIGHT, next)
                else -> {
                    Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                    Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, (next * 255 / 100).coerceAtLeast(1))
                }
            }
        }
        return level(context)
    }

    /**
     * The TV's own picture settings, where brightness is when Lite cannot set it: Xiaomi's backlight
     * popup, TCL's panel, else the display settings (the full app's SystemProbe.openPictureSettings).
     */
    fun openPictureSettings(context: Context): Boolean {
        val backlight = Intent("com.xiaomi.mitv.settings.BACKLIGHT_POPUP")
            .setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.SettingsBroadcastReceiver"))
        if (context.packageManager.queryBroadcastReceivers(backlight, 0).any { it.activityInfo.exported }) {
            if (runCatching { context.sendBroadcast(backlight) }.isSuccess) return true
        }
        if (installed(context, "com.tcl.settings")) {
            val shown = runCatching { context.startService(Intent("com.tcl.settings.SHOW_WINDOW").setPackage("com.tcl.settings")) }.getOrNull() != null
            if (shown) return true
        }
        val pm = context.packageManager
        val picture = listOf(
            // Older Coocaa (Tianci): picture and sound (5S09_A3, 6S58_K6S, issue #895), before the
            // display action, which those TVs answer with the settings' main page.
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tianci.setting", "com.tianci.setting.activity.PictureSoundSetting")),
            Intent(Settings.ACTION_DISPLAY_SETTINGS),
        ).firstOrNull { runCatching { pm.resolveActivity(it, 0) }.getOrNull() != null } ?: Intent(Settings.ACTION_DISPLAY_SETTINGS)
        return runCatching { context.startActivity(picture.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}

/** TCL's picture SDK, as in the full app: the graphic backlight, 0-100, open to any app. */
private object TclPicture {
    private fun manager(context: Context): Any? = runCatching {
        Class.forName("com.tcl.tvmanager.TTvPictureManager").getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
    }.getOrNull()

    fun backlight(context: Context): Int? {
        val pm = manager(context) ?: return null
        return runCatching { pm.javaClass.getMethod("getGraphicBacklight").invoke(pm) as Int }.getOrNull()?.takeIf { it in 1..100 }
    }

    fun setBacklight(context: Context, percent: Int) {
        val pm = manager(context) ?: return
        pm.javaClass.getMethod("setGraphicBacklight", Int::class.javaPrimitiveType).invoke(pm, percent.coerceIn(1, 100))
    }
}
