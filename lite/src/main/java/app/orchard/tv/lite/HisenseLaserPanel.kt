package app.orchard.tv.lite

import android.content.Context

/**
 * Hisense VIDAA laser sets: the light source itself, through `com.jamdeo.tv.PictureManager`.
 *
 * The Lite build's copy of the full app's system/HisenseLaserPanel.kt, which carries the
 * measurements and the reasoning — keep the two in step. No laser set runs Lite (they are
 * Android 8 and later), but the row should behave the same wherever it is shown.
 *
 * The class sits on the boot class path and answers any app: no permission, root or system
 * signature. setCustomBacklight takes the same 0..10 level as the TV's own 图像 → 亮度 slider and
 * getCustomBacklight reads it back, while screen_brightness changes on these sets without moving
 * the light source at all (VIDAA 75E8H, issue #1025; measured on a Vidda M2 Pro 高亮版, model VL5R-PRO, 2026-11-01).
 *
 * Only laser sets are taken over: `getLaserOverLapOrCL0Mode` belongs to the laser light path
 * (CL0 is the laser source), so a set without it keeps its existing behaviour.
 *
 * Lite has no row of picture modes, so it cannot say why a slider stopped; a step in 超新 or 超频
 * still drops the set into 自定义 at the level asked for.
 */
object HisenseLaserPanel {
    /** The TV's own 0..10 brightness, the same scale as its 图像 → 亮度 slider. */
    const val LEVEL_MAX = 10

    /** 自定义: the only picture backlight mode whose level the owner controls. */
    private const val BACKLIGHT_MODE_CUSTOM = 0

    private val tvManager by lazy { runCatching { Class.forName("com.jamdeo.tv.TvManager") }.getOrNull() }

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

    /** The raw 0..10 level, or null where this set does not answer as a laser one. */
    private fun rawLevel(context: Context): Int? {
        val pm = pictureManager(context) ?: return null
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
