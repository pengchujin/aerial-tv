package app.orchard.tv.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * VIDAA's own picture menu uses ConfigOptionManager, not Android's screen_brightness. Its volume
 * is separate from AudioService too (V3K_PRO / MT9653, Android 11, firmware 02.02.70.01).
 * Resolve named options from the firmware instead of hard-coding binder transactions. Both
 * routes require an explicit device profile; an absent/refused native API never writes AOSP's
 * unrelated setting. The backlight range must be checked on the TV before enabling it.
 */
internal object HisenseControls {
    private const val TAG = "AerialHisenseControls"
    private val intType = Int::class.javaPrimitiveType!!
    private val boolType = Boolean::class.javaPrimitiveType!!

    private class Api {
        private val config = Class.forName("com.jamdeo.tv.common.ConfigOptionManager")
        private val audio = Class.forName("com.jamdeo.tv.common.EnumConstants\$AudioOptions")
        private val picture = Class.forName("com.jamdeo.tv.common.EnumConstants\$PictureOptions")
        val volume = audio.getField("TIL_VOLUME").getInt(null)
        val mute = audio.getField("TIL_VOLUME_MUTE").getInt(null)
        val backlight = picture.getField("TIL_BACK_LIGHT_CUSTOM").getInt(null)
        val backlightMode = picture.getField("TIL_BACK_LIGHT_MODE").getInt(null)
        private val sensor = Class.forName("com.jamdeo.tv.common.EnumConstants\$SystemOptions")
            .getField("TIL_SENSOR_ENABLE").getInt(null)
        private val getInt = config.getMethod("getConfigurationIntValue", intType)
        private val setInt = config.getMethod("setConfigurationIntValue", intType, intType)
        private val getBool = config.getMethod("getConfigurationBooleanValue", intType)
        fun read(option: Int) = (getInt.invoke(null, option) as Number).toInt()
        fun write(option: Int, value: Int) = setInt.invoke(null, option, value) == true
        fun muted() = getBool.invoke(null, mute) == true
        fun manualBacklight() {
            val props = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
            // Match the TV's manual slider: keep its light sensor when that mode is enabled.
            val manual = props.invoke(null, "ro.his.light.freq_support", "0") == "1" ||
                props.invoke(null, "ro.tv.sensor_backlight_type", "0") == "0" ||
                getBool.invoke(null, sensor) != true
            if (manual && read(backlightMode) != 0 && !write(backlightMode, 0)) {
                Log.w(TAG, "Native manual backlight mode refused")
            }
        }
    }

    private val api by lazy {
        runCatching { Api() }.onFailure { Log.d(TAG, "Native configuration API unavailable", it) }.getOrNull()
    }

    @Volatile private var audioManager: Any? = null
    private var audioRequested = false

    private fun audio(context: Context): Any? {
        synchronized(this) {
            if (!audioRequested) {
                audioRequested = true
                val connect = Runnable {
                    runCatching {
                        val tv = Class.forName("com.jamdeo.tv.TvManager")
                        audioManager = tv.getMethod("getAudioManager", Context::class.java)
                            .invoke(tv.getMethod("getInstance").invoke(null), context.applicationContext)
                    }.onFailure { Log.w(TAG, "Native audio service unavailable", it) }
                }
                // Native managers bind asynchronously; never block the UI for a connection.
                if (Looper.myLooper() == Looper.getMainLooper()) connect.run()
                else Handler(Looper.getMainLooper()).post(connect)
            }
        }
        return audioManager?.takeIf {
            runCatching { it.javaClass.getMethod("isConnected").invoke(it) == true }.getOrDefault(false)
        }
    }

    fun volume(context: Context): VolumeStatus? {
        if (!DeviceProfile.current(context).hisenseNativeVolume) return null
        audio(context)
        return native {
            val level = read(volume)
            if (level !in 0..100) null else VolumeStatus(level, 100, muted())
        }
    }

    fun adjustVolume(context: Context, direction: Int) {
        val before = volume(context) ?: return
        val next = (before.level + direction.compareTo(0)).coerceIn(0, before.max)
        native {
            val manager = audio(context) ?: return@native Log.w(TAG, "Native audio service not ready")
            if (manager.javaClass.getMethod("setVolume", intType).invoke(manager, next) != true) {
                Log.w(TAG, "Native volume write refused")
            }
            Log.i(TAG, "volume ${before.level} -> ${read(volume)} (requested $next)")
        }
    }

    fun toggleMute(context: Context) {
        val before = volume(context) ?: return
        native {
            val manager = audio(context) ?: return@native Log.w(TAG, "Native audio service not ready")
            if (manager.javaClass.getMethod("setMuteFlag", boolType).invoke(manager, !before.muted) != true) {
                Log.w(TAG, "Native mute write refused")
            }
        }
    }

    fun backlight(context: Context): Float? {
        val max = DeviceProfile.current(context).hisenseBacklightMax
        if (max <= 0) return null
        return native { read(backlight).takeIf { it in 0..max }?.div(max.toFloat()) }
    }

    fun backlightMode(): Int? = native { read(backlightMode) }

    fun adjustBacklight(context: Context, direction: Int): Float? {
        val max = DeviceProfile.current(context).hisenseBacklightMax
        val before = backlight(context) ?: return null
        // Never turn the panel off. Keep the native range; one remote step is one hardware level.
        val next = (Math.round(before * max) + direction.compareTo(0)).coerceIn(1, max)
        native {
            if (!write(backlight, next)) Log.w(TAG, "Native backlight write refused")
            else manualBacklight()
            Log.i(TAG, "backlight ${Math.round(before * max)} -> ${read(backlight)} (requested $next)")
        }
        return backlight(context)
    }

    private inline fun <T> native(call: Api.() -> T): T? =
        api?.let { runCatching { it.call() }.onFailure { Log.w(TAG, "Native control failed", it) }.getOrNull() }
}
