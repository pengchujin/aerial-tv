package app.orchard.tv.system

import android.content.Context

/**
 * Hisense VIDAA laser sets: the light source itself, through `com.jamdeo.tv.PictureManager`.
 *
 * The class sits on the boot class path and answers any app — no permission, root or system
 * signature needed. `setCustomBacklight` takes the same 0..10 level as the TV's own
 * 图像 → 亮度 slider and `getCustomBacklight` reads it back, while `Settings.System.
 * screen_brightness` changes on these sets without moving the light source at all: 216 → 30 left
 * the picture exactly as it was (measured with a local probe app). Issue #1025 reports the same
 * symptom on a VIDAA 75E8H, where Control Center opens the picture settings instead of a slider
 * that would change nothing.
 *
 * Only laser sets are taken over. Every VIDAA firmware carries the class, but
 * `getLaserOverLapOrCL0Mode` belongs to the laser light path (CL0 is the laser source), so a set
 * without it keeps its existing behaviour: the system `screen_brightness` where that works, and
 * the picture settings where it does not. Nothing is keyed on the model name — `VIDAA_TV` is what
 * every Hisense reports.
 *
 * The scale is the TV's own 11 notches, not percent: a step moves one notch, where the other TVs
 * step 5%. A picture mode other than 自定义 pins a level of its own and drops writes to the custom
 * one, so a write chooses 自定义 first — which is what the TV itself does when the owner drags its
 * brightness slider, and why the row switches to 自定义 as the picture changes.
 *
 * Measured on a Vidda M2 Pro 高亮版 (VL5R-PRO; VIDAA_TV, MT9653, Android 11, Aerial 1.76), 2026-11-01;
 * see `docs/devices/hisense-vidaa.md`.
 */
internal object HisenseLaserPanel {
    /** The TV's own 0..10 brightness, the same scale as its 图像 → 亮度 slider. */
    const val LEVEL_MAX = 10

    /** 自定义: the only picture backlight mode whose level the owner controls. */
    private const val BACKLIGHT_MODE_CUSTOM = 0

    private val tvManager by lazy { runCatching { Class.forName("com.jamdeo.tv.TvManager") }.getOrNull() }

    /**
     * The picture manager, kept once it answers. It is the binder proxy to
     * `com.jamdeo.tv.service/.PictureService`, and a write made through a proxy built on the spot
     * applied late enough that a held key's repeats overtook each other on this firmware.
     */
    @Volatile
    private var cached: Any? = null

    private fun pictureManager(context: Context): Any? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val cls = tvManager ?: return null
            val pm = runCatching {
                val tv = cls.getMethod("getInstance").invoke(null) ?: return null
                tv.javaClass.getMethod("getPictureManager", Context::class.java)
                    .invoke(tv, context.applicationContext)
            }.getOrNull() ?: return null
            cached = pm
            return pm
        }
    }

    private fun method(on: Any, name: String, vararg args: Class<*>): java.lang.reflect.Method? =
        runCatching { on.javaClass.getMethod(name, *args) }.getOrNull()

    /**
     * The raw 0..10 level, or null where this set does not answer as a laser one. Looked up on
     * every call rather than once: just after boot the picture service may not be bound yet, and a
     * set taken for one without a laser level would keep no slider all day.
     */
    private fun rawLevel(context: Context): Int? {
        val pm = pictureManager(context) ?: return null
        // The laser light path's own call: present on laser sets, absent on the others.
        if (method(pm, "getLaserOverLapOrCL0Mode") == null) return null
        val level = runCatching { method(pm, "getCustomBacklight")?.invoke(pm) as? Int }.getOrNull()
        return level?.takeIf { it in 0..LEVEL_MAX }
    }

    /** Whether this set's brightness is the laser level. */
    fun usable(context: Context): Boolean = rawLevel(context) != null

    /** Current level as 0..1, or null where this set is not a laser one. */
    fun level(context: Context): Float? = rawLevel(context)?.let { it / LEVEL_MAX.toFloat() }

    /** One notch up or down, stopping at either end, as 0..1. */
    fun step(current: Float, direction: Int): Float {
        val now = Math.round(current * LEVEL_MAX)
        val next = (now + if (direction > 0) 1 else -1).coerceIn(0, LEVEL_MAX)
        return next / LEVEL_MAX.toFloat()
    }

    /** Writes [level], choosing the custom picture mode first so the preset does not drop it. */
    fun setLevel(context: Context, level: Float) {
        val pm = pictureManager(context) ?: return
        val target = Math.round(level.coerceIn(0f, 1f) * LEVEL_MAX)
        val int = Int::class.javaPrimitiveType ?: return
        runCatching {
            method(pm, "setBacklightMode", int)?.invoke(pm, BACKLIGHT_MODE_CUSTOM)
            method(pm, "setCustomBacklight", int)?.invoke(pm, target)
        }
    }

}
