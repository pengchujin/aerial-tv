package app.orchard.tv.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Panel brightness. TVs keep it in different places:
 *
 * - Xiaomi (MediaTek, China firmware): Settings.Global "picture_backlight", 0–100, applied by the
 *   firmware's TV menu service as soon as it changes. Writing it needs WRITE_SECURE_SETTINGS.
 * - MiTV China firmware without "picture_backlight" (MiTV-ASTP0, Mi TV 4A, recent Redmi): the
 *   panel is driven by the mitv middleware, which keeps the level in Settings.System
 *   "backLightForNormal" (0–100). The setting alone does not move the backlight: the value has to
 *   be pushed to TvService as well, and only once [MitvPanel.usable] has found that the
 *   transactions match this firmware (issues #23, #346).
 * - TCL: TTvPictureManager in TCL's framework (see TclPicture); where it is missing, Control
 *   Center opens TCL's picture panel.
 * - Sony BRAVIA: the screen brightness setting changes but the backlight does not (no backlight in
 *   the display config); Control Center opens Sony's picture settings, which DISPLAY_SETTINGS
 *   resolves to. Reported on a BRAVIA VH1, Android 12 (GitHub issue #2).
 * - XGIMI (GMUI): the real brightness is the DLP light engine's level, in a private HAL class
 *   (com.xgimi.gmpf.api.DisplayManager, see SystemProbe.xgimiBrightnessLevel); the system setting
 *   does not follow it. Issue #241 took the level for read-only on a Z7X, but a `pig_chivalrous` on
 *   GMUI 9 takes a write from a plain app, so Control Center steps the light engine's own levels.
 * - Others: the standard Settings.System screen brightness, 0–255. Writing it needs the "modify
 *   system settings" app-op. Both are granted by tools/install.sh; without them Control Center
 *   opens the TV's own picture settings instead.
 */
object Brightness {
    private const val XIAOMI_BACKLIGHT = "picture_backlight"

    /**
     * MiTV-MFTP0 (Android 9) has picture_backlight, yet changing it leaves the panel as it was (two
     * owners, issues #284 and #335); it gets Xiaomi's own backlight slider like the TVs without it.
     * Nothing on the TV tells the two apart, so by model.
     */
    private val IGNORES_BACKLIGHT_SETTING = setOf("MiTV-MFTP0", "MiTV_MFTP0")

    private fun usesXiaomiBacklight(context: Context) =
        Build.MODEL !in IGNORES_BACKLIGHT_SETTING &&
            Settings.Global.getString(context.contentResolver, XIAOMI_BACKLIGHT) != null

    private fun isTcl(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.tcl.settings", 0) }.isSuccess

    private fun isXiaomi(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.xiaomi.mitv.settings", 0) }.isSuccess

    private fun isSony(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.sony.dtv.configsettings", 0) }.isSuccess

    /**
     * Level Aerial last pushed to the MiTV panel. The middleware's getter reports animated values
     * and the firmware refuses third-party writes to its own setting, so Aerial tracks the level it
     * set, seeded once per process from the panel itself when it has none.
     */
    private var mitvLevel: Int? = null

    private const val PREFS = "aerial"
    private const val KEY_MITV_LEVEL = "mitv_backlight"

    private fun storedLevel(context: Context): Int? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MITV_LEVEL, -1)
        return if (value in 1..100) value else null
    }

    /** Current level as 0..1, or null when Aerial cannot change it on this TV. */
    fun level(context: Context): Float? {
        // XGIMI: the real brightness is the DLP light-engine level; the system screen_brightness
        // setting does not follow it (issue #241).
        SystemProbe.xgimiBrightnessLevel()?.let { return it }
        val r = context.contentResolver
        if (isTcl(context)) TclPicture.backlight(context)?.let { return it / 100f }
        return when {
            usesXiaomiBacklight(context) -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return null
                Settings.Global.getInt(r, XIAOMI_BACKLIGHT, 50) / 100f
            }
            // The MiTV middleware is driven over its own binder and Aerial keeps the level in its own
            // preferences, so this path does not need WRITE_SETTINGS.
            MitvPanel.usable(context) -> (mitvLevel ?: MitvPanel.level(context) ?: storedLevel(context) ?: 50) / 100f
            // A Xiaomi TV where neither place works: open the TV's own backlight slider rather than
            // show one that changes nothing (issue #346).
            isXiaomi(context) -> null
            // TCL and Sony drive the backlight through their own TV services and ignore the system
            // setting; Control Center opens their picture panels.
            // Before Android 6.0 WRITE_SETTINGS is granted at install.
            isTcl(context) || isSony(context) || Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context) -> null
            else -> Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        }
    }

    /**
     * XGIMI's light-engine level one notch from [current], or null on a TV without the engine.
     * [current] comes from the caller rather than from the HAL: the engine applies a write a moment
     * later, so a level read back between the repeats of a held key names the previous notch again
     * and the row would stick while the picture moves on.
     */
    fun xgimiStep(current: Float, direction: Int): Float? {
        if (!SystemProbe.hasXgimiBrightness) return null
        val max = SystemProbe.xgimiBrightnessMax
        val now = Math.round(current * max)
        val next = (now + if (direction > 0) 1 else -1).coerceIn(1, max)
        return next / max.toFloat()
    }

    /**
     * Steps brightness one notch up or down and returns the new level. Never to 0: Xiaomi turns the
     * backlight off at picture_backlight 0 and the screen goes black, with Control Center still open
     * on it (HyperOS, issue #174); its own slider stops at 1. So the lowest notch is 1, and the steps
     * stay on multiples of 5 above it (1, 5, 10 ...).
     *
     * On XGIMI the level is the DLP light engine's, stepped by its own single levels (1..10) through
     * the HAL class (see [SystemProbe.setXgimiBrightnessLevel]).
     */
    fun adjust(context: Context, direction: Int): Float? {
        SystemProbe.xgimiBrightnessLevel()?.let { current ->
            val next = xgimiStep(current, direction) ?: return current
            val level = Math.round(next * SystemProbe.xgimiBrightnessMax)
            // The light engine applies it off this thread, so the new level is reported as asked and
            // Control Center's poll shows what it settled at — including where a TV drops the write.
            return if (SystemProbe.setXgimiBrightnessLevel(level)) next else current
        }
        val current = level(context) ?: return null
        val percent = Math.round(current * 100)
        val next = (if (direction > 0) (percent / 5 + 1) * 5 else ((percent + 4) / 5 - 1) * 5).coerceIn(1, 100)
        val r = context.contentResolver
        // The same order as level(): TCL's SDK and picture_backlight come first, so a Xiaomi that
        // has both never has its panel probed or moved this way.
        if (!isTcl(context) && !usesXiaomiBacklight(context) && MitvPanel.usable(context)) {
            runCatching {
                // Start the relative step from the level Aerial set last: the middleware's getter is
                // animated, so a panel reading taken mid-step is only the seed of the first step
                // after a restart.
                val from = mitvLevel ?: MitvPanel.level(context) ?: storedLevel(context) ?: percent
                MitvPanel.setBacklight(context, from, next)
                mitvLevel = next
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_MITV_LEVEL, next).apply()
            }
            // Return what was asked for rather than reading it back: the MiTV middleware animates
            // the panel and its getter reports the transient level, which made the slider jump.
            return next / 100f
        }
        runCatching {
            if (isTcl(context)) {
                TclPicture.setBacklight(context, next)
            } else if (usesXiaomiBacklight(context)) {
                Settings.Global.putInt(r, XIAOMI_BACKLIGHT, next)
            } else {
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, (next * 255 / 100).coerceAtLeast(1))
            }
        }
        return level(context)
    }
}

/**
 * TCL's picture SDK, com.tcl.tvmanager.TTvPictureManager, on the boot class path and open to any
 * app: no permission, root or system signature (issue #363, measured on a TCL 75V2, m7221,
 * Android 8). MT5879 (Android 11) has the class too but answers 0; see [backlight]. The graphic
 * backlight is the one for the TV's own screen, where Control Center is; the video one
 * (getBacklight) is for HDMI and tuner pictures, which the owner may have set apart in TCL's
 * panel, so it is left alone. Scale 0–100, as TCL's slider. Looked up on every call, not once:
 * just after boot the SDK may not answer yet, and a TV taken for one without it would keep no
 * slider all day.
 */
private object TclPicture {
    private fun manager(context: Context): Any? = runCatching {
        Class.forName("com.tcl.tvmanager.TTvPictureManager")
            .getMethod("getInstance", Context::class.java)
            .invoke(null, context.applicationContext)
    }.getOrNull()

    fun backlight(context: Context): Int? {
        val pm = manager(context) ?: return null
        return runCatching { pm.javaClass.getMethod("getGraphicBacklight").invoke(pm) as Int }
            // MT5879 (Android 11) answers 0 with the screen lit: there the graphic backlight is
            // not what drives the panel, and a slider would start from nothing and set it to 5.
            // A real 0 is a black screen, where nobody sees Control Center to use the slider.
            .getOrNull()?.takeIf { it in 1..100 }
    }

    fun setBacklight(context: Context, percent: Int) {
        val pm = manager(context) ?: return
        pm.javaClass.getMethod("setGraphicBacklight", Int::class.javaPrimitiveType).invoke(pm, percent.coerceIn(1, 100))
    }
}
