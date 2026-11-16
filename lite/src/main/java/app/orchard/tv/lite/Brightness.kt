package app.orchard.tv.lite

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

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
        // Hisense laser: the light source's own level, which screen_brightness does not touch.
        HisenseLaserPanel.level(context)?.let { return it }
        // TCL MS901 / MStar ONLY: read the TV's own brightness store, not the picture SDK's
        // getBacklight() (that one returns a scaled MStar register, not a percentage). Every
        // other TV falls through to the original branches below, byte for byte unchanged.
        if (TclMs901Picture.available(context)) return TclMs901Picture.level(context) / 100f
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
            installed(context, "com.tcl.settings") || installed(context, "com.sony.dtv.configsettings") ||
                // Sony's name on Dangbei's China firmware (BRAVIA BF1, the full app's issue #1355).
                Build.MANUFACTURER.equals("Sony", ignoreCase = true) -> null
            DeviceProfile.current(context).ignoresScreenBrightness -> null
            Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context) -> null
            else -> runCatching { Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f }.getOrNull()
        }
    }

    /** One notch (5%) up or down, never to 0 (Xiaomi turns the backlight off there, issue #174). */
    fun adjust(context: Context, direction: Int): Float? {
        // Hisense laser: 11 notches of the TV's own level, where 5% would land on the same notch
        // twice and the row would seem to stick.
        HisenseLaserPanel.level(context)?.let { current ->
            val next = HisenseLaserPanel.step(current, direction)
            HisenseLaserPanel.setLevel(context, next)
            return next
        }
        val current = level(context) ?: return null
        val percent = Math.round(current * 100)
        val next = (if (direction > 0) (percent / 5 + 1) * 5 else ((percent + 4) / 5 - 1) * 5).coerceIn(1, 100)
        // TCL MS901 / MStar ONLY: mirror the TV's own brightness page - persist to its settings
        // provider, then apply to the panel and notify TVOS (see TclMs901Picture below). As in
        // level(), the generic TCL / Xiaomi / MitvPanel / AOSP paths below are left untouched.
        if (TclMs901Picture.available(context)) {
            runCatching { TclMs901Picture.setBacklight(context, next) }
                .onFailure { Log.w("AerialBrightness", "setBacklight($next) failed", it) }
            return next / 100f
        }
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

/**
 * TCL MS901 / MStar brightness, reverse engineered from the TV's own brightness page
 * (`com.tcl.tv/com.tcl.system.SysPicActivityActivity` -> `com.tcl.tv.manager.SYSBackLightOperation`).
 *
 * Every notch of that page does three things; the old Lite code did only the middle one, and used
 * its own SharedPreferences instead of the TV's store:
 *
 *  1. `ContentResolver.update(content://com.tcl.tv.sys.SYSPicSettings/pic_settings, {"backlight": 0..100})`.
 *     `SYSSettingsProvider.update()` only writes `pic_settings` in SQLite and calls `notifyChange`.
 *     This DB value is what the TV restores on boot / input switch and what its settings page shows.
 *  2. `PictureDesk.SetBacklightDispaly(short)` -> `PictureManager.setBacklight(int)` for the immediate
 *     hardware change. `TTvPictureManager.setBacklight()` is the addon wrapper of exactly that call.
 *  3. `TvManager.setTvosInterfaceCommand("Menu_BackLight=<value>")` to notify TVOS.
 *
 * Reading is `ContentResolver.query(..., {"backlight"})`. The provider is exported with no
 * permission, so a launcher can use the same path. `getBacklight()` returns the MStar register
 * (6912..33280), NOT a percentage, so it is only useful as a log-side confirmation of step 2.
 *
 * This is a SEPARATE object from [TclPicture]: existing TCL sets that ship com.tcl.settings keep
 * using their graphic-backlight branch above, exactly as before this adaptation.
 */
private object TclMs901Picture {
    private const val TAG = "AerialBrightness"
    private const val MANAGER = "com.tcl.tvmanager.TTvPictureManager"
    private const val TV_MANAGER = "com.mstar.android.tvapi.common.TvManager"
    private const val URI = "content://com.tcl.tv.sys.SYSPicSettings/pic_settings"
    private const val COLUMN = "backlight"
    private const val PREFS = "settings"
    private const val KEY = "tcl_ms901_backlight" // fallback only, when the provider is unavailable

    /** True only when the firmware's own brightness store and picture SDK are both usable. */
    fun available(context: Context): Boolean = runCatching {
        val c = Class.forName(MANAGER)
        c.getMethod("getInstance", Context::class.java)
        c.getMethod("setBacklight", Int::class.javaPrimitiveType)
        // Only claim this TV when its store answers with a 0..100 percentage; other TCL boards
        // have no such provider and must keep using the generic brightness path.
        read(context)?.let { it in 0..100 } ?: false
    }.getOrDefault(false)

    private fun read(context: Context): Int? {
        val cursor = runCatching {
            context.contentResolver.query(Uri.parse(URI), arrayOf(COLUMN), null, null, null)
        }.getOrNull() ?: return null
        return runCatching {
            try {
                if (!cursor.moveToFirst()) null
                else cursor.getColumnIndex(COLUMN).takeIf { it >= 0 }?.let { cursor.getInt(it) }
            } finally {
                cursor.close()
            }
        }.getOrNull()
    }

    private fun manager(context: Context): Any? = runCatching {
        val get = Class.forName(MANAGER).getMethod("getInstance", Context::class.java)
        // Some builds keep the Context, others (verified here) accept null. Try both.
        runCatching { get.invoke(null, context.applicationContext) }.getOrNull()
            ?: get.invoke(null, *arrayOfNulls<Any>(1))
    }.getOrNull()

    /** Register value, for the log only - never a percentage. */
    private fun rawBacklight(context: Context): Int? = runCatching {
        val pm = manager(context) ?: return null
        pm.javaClass.getMethod("getBacklight").invoke(pm) as? Int
    }.getOrNull()

    /** The TV's own brightness setting, 0..100; sharedPreferences only if the provider is down. */
    fun level(context: Context): Int =
        (read(context) ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 50))
            .coerceIn(0, 100)

    fun setBacklight(context: Context, percent: Int) {
        val value = percent.coerceIn(1, 100)

        // 1. Persist exactly where SYSBackLightOperation.updateDB() writes.
        val rows = runCatching {
            context.contentResolver.update(
                Uri.parse(URI), ContentValues().apply { put(COLUMN, value) }, null, null
            )
        }.onFailure { Log.w(TAG, "update pic_settings failed", it) }.getOrDefault(0)

        // 2. Apply to the panel now (SetBacklightDispaly -> PictureManager.setBacklight).
        val pm = manager(context)
        if (pm != null) runCatching {
            pm.javaClass.getMethod("setBacklight", Int::class.javaPrimitiveType).invoke(pm, value)
        }.onFailure { Log.w(TAG, "setBacklight failed", it) }

        // 3. Same TVOS notification the settings page sends from SetBacklight2DBC().
        runCatching {
            val tv = Class.forName(TV_MANAGER).getMethod("getInstance").invoke(null)
            tv.javaClass.getMethod("setTvosInterfaceCommand", String::class.java)
                .invoke(tv, "Menu_BackLight=$value")
        }.onFailure { Log.w(TAG, "Menu_BackLight notify failed", it) }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, value).apply()
        Log.i(TAG, "TCL backlight=$value (db rows=$rows, panel=${rawBacklight(context)})")
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
