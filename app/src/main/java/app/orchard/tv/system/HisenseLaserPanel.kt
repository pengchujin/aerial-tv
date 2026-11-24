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
 * Measured on a Vidda M2 Pro 高亮版 (VL5R-PRO; VIDAA_TV, MT9653, Android 11, Aerial 1.76), 2026-11-01; the seven
 * modes and their levels are in `docs/devices/hisense-vidaa.md`.
 */
internal object HisenseLaserPanel {
    /** The TV's own 0..10 brightness, the same scale as its 图像 → 亮度 slider. */
    const val LEVEL_MAX = 10

    /**
     * The seven picture modes of 设置 → 图像 → 亮度模式, in the TV's own order, as the values
     * `getBacklightMode` reports and `setBacklightMode` takes: 明亮, 标准, 柔和, 动态, 超新, 超频,
     * 自定义. Measured one by one on a Vidda M2 Pro 高亮版; switching needs this one call and the firmware
     * writes the mode's own level and laser overlap itself.
     */
    private val MODE_BACKLIGHT = intArrayOf(16384, 8192, 2, 4096, 131072, 65536, 0)

    /** 自定义, the only mode whose level the owner controls; also the raw value of "no preset". */
    const val CUSTOM = 6

    /** 动态 (3): the one mode whose level is neither the owner's nor a fixed number of ours. */
    const val DYNAMIC = 3

    /** 超新 (4) and 超频 (5), the two laser-overlap modes, whose level the TV pins and locks. */
    const val SUPERNOVA = 4
    const val OVERCLOCK = 5

    /**
     * Whether the TV locks the level in mode [index]. In 超新 and 超频 its own 亮度 row is disabled
     * (`enabled=false`, `focusable=false`) with the level at 10, so the slider is offered but cannot
     * move — a write here would take the picture out of the mode the owner chose.
     */
    fun levelLocked(index: Int?): Boolean = index == SUPERNOVA || index == OVERCLOCK

    /** How many modes the row offers, so the labels can be checked against it. */
    val MODE_COUNT: Int get() = MODE_BACKLIGHT.size

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
        // 超新 and 超频 hold their level and the TV disables its own slider there; taking the write
        // would leave the mode the owner picked, which is not what a brightness step should do.
        if (levelLocked(mode(context))) return
        val pm = pictureManager(context) ?: return
        val target = Math.round(level.coerceIn(0f, 1f) * LEVEL_MAX)
        val int = Int::class.javaPrimitiveType ?: return
        runCatching {
            method(pm, "setBacklightMode", int)?.invoke(pm, MODE_BACKLIGHT[CUSTOM])
            method(pm, "setCustomBacklight", int)?.invoke(pm, target)
        }
    }

    /**
     * The mode this set is in, as an index into the row of seven, or null where this set is not a
     * laser one (or the firmware reports a value the row does not show). Read back rather than
     * remembered: the TV's own picture menu changes it, and a preset also moves the level with it.
     */
    fun mode(context: Context): Int? {
        val pm = pictureManager(context) ?: return null
        if (method(pm, "getLaserOverLapOrCL0Mode") == null) return null
        val raw = runCatching { method(pm, "getBacklightMode")?.invoke(pm) as? Int }.getOrNull() ?: return null
        return modeIndexOf(raw)
    }

    /** Switches the picture to mode [index]; false where the set has no such mode to switch to. */
    fun setMode(context: Context, index: Int): Boolean {
        val value = MODE_BACKLIGHT.getOrNull(index) ?: return false
        val pm = pictureManager(context) ?: return false
        val int = Int::class.javaPrimitiveType ?: return false
        return runCatching {
            val call = method(pm, "setBacklightMode", int) ?: return false
            call.invoke(pm, value)
            true
        }.getOrDefault(false)
    }

    /** A raw `getBacklightMode` value as the row's index, or null when the row does not show it. */
    fun modeIndexOf(raw: Int): Int? = MODE_BACKLIGHT.indexOf(raw).takeIf { it >= 0 }
}
