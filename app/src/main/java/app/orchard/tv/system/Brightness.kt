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
 * - TCL: TTvPictureManager in TCL's framework (see TclPicture), or on newer MT9655 firmware
 *   TvBacklightManager (see TclPq); where neither answers, Control Center opens TCL's picture panel.
 * - Sony BRAVIA: the screen brightness setting changes but the backlight does not (no backlight in
 *   the display config); Control Center opens Sony's picture settings, which DISPLAY_SETTINGS
 *   resolves to. Reported on a BRAVIA VH1, Android 12 (GitHub issue #2).
 * - Skyworth/Coocaa: the panel is driven by the TV's picture middleware, and Settings.System
 *   screen_brightness does not move it (75A7D, 8R134_A7D, Android 11, CCOS 9.0: the setting sat at
 *   2/255 with the screen at full brightness). Control Center opens the firmware's picture panel
 *   instead (SystemProbe.skyworthPicturePanel).
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

    // MiTV-MFTP0 keeps picture_backlight without following it: DeviceProfile.ignoresPictureBacklight.

    private fun usesXiaomiBacklight(context: Context) =
        !DeviceProfile.current(context).ignoresPictureBacklight &&
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
            // TCL, Sony and Skyworth/Coocaa drive the backlight through their own TV services and
            // ignore the system setting; Control Center opens their picture panels.
            // Before Android 6.0 WRITE_SETTINGS is granted at install.
            isTcl(context) || isSony(context) || SystemProbe.skyworthPicturePanel(context) ||
                DeviceProfile.current(context).ignoresScreenBrightness ||
                Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context) -> null
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

    private fun graphicBacklight(context: Context): Int? {
        val pm = manager(context) ?: return null
        return runCatching { pm.javaClass.getMethod("getGraphicBacklight").invoke(pm) as Int }
            // MT5879 (Android 11) answers 0 with the screen lit: there the graphic backlight is
            // not what drives the panel, and a slider would start from nothing and set it to 5.
            // A real 0 is a black screen, where nobody sees Control Center to use the slider.
            .getOrNull()?.takeIf { it in 1..100 }
    }

    fun backlight(context: Context): Int? = graphicBacklight(context) ?: TclPq.backlight(context)

    fun setBacklight(context: Context, percent: Int) {
        if (graphicBacklight(context) == null && TclPq.backlight(context) != null) {
            TclPq.setBacklight(context, percent.coerceIn(1, 100))
            return
        }
        val pm = manager(context) ?: return
        pm.javaClass.getMethod("setGraphicBacklight", Int::class.javaPrimitiveType).invoke(pm, percent.coerceIn(1, 100))
    }
}

/**
 * The newer TCL picture interface, com.tcl.tv.pq.TvBacklightManager in the shared library
 * com.tcl.tv.display (/system_ext/framework), which the manifest asks for as optional. Found and
 * checked by a user on a TCL 85T7MPRO (MT9655, Android 14), whose TTvPictureManager does not
 * drive the panel: from a local build of Aerial, setBacklight(EN_APPLY_CURRENT, level,
 * EN_TCL_ACT_EXEC_SAVE) moved the panel 38 → 39 → 38 and read back the same, no permission
 * needed (issue #656). Scale 1–100, as TCL's slider. A write that fails or does not read back
 * gives it up for the process, and Control Center opens TCL's picture panel as before.
 * Not on TCL MT5879, which has no such library.
 */
private object TclPq {
    private class Api(val manager: Any, val get: java.lang.reflect.Method, val set: java.lang.reflect.Method, val current: Any, val save: Any)

    private var failed = false

    private fun api(context: Context): Api? {
        if (failed) return null
        return runCatching {
            val loader = context.applicationContext.classLoader
            val managerClass = Class.forName("com.tcl.tv.pq.TvBacklightManager", true, loader)
            val applyClass = Class.forName("com.tcl.tv.pq.EnApplyType", true, loader)
            val actClass = Class.forName("com.tcl.tv.pq.EnTclActType", true, loader)
            Api(
                manager = managerClass.getMethod("getInstance").invoke(null) ?: return null,
                get = managerClass.getMethod("getBacklight"),
                set = managerClass.getMethod("setBacklight", applyClass, Int::class.javaPrimitiveType, actClass),
                current = applyClass.getField("EN_APPLY_CURRENT").get(null)!!,
                save = actClass.getField("EN_TCL_ACT_EXEC_SAVE").get(null)!!,
            )
        }.getOrNull()
    }

    fun backlight(context: Context): Int? {
        val api = api(context) ?: return null
        return runCatching { api.get.invoke(api.manager) as Int }.getOrNull()?.takeIf { it in 1..100 }
    }

    fun setBacklight(context: Context, percent: Int) {
        val api = api(context) ?: return
        val ok = runCatching {
            api.set.invoke(api.manager, api.current, percent, api.save) == true && api.get.invoke(api.manager) as Int == percent
        }.getOrDefault(false)
        if (!ok) failed = true
    }
}
