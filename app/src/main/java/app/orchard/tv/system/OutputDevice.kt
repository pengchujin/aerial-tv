package app.orchard.tv.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build

/**
 * What kind of thing Aerial is running on, for Control Center's volume and brightness rows. Shared
 * with Lite (copied into its package at build time), so plain Android only.
 *
 * A set-top box or a streaming stick plugged into a TV has no panel of its own, so a brightness
 * slider moves nothing and the TV's own picture settings are not on this device either; and where
 * Android's volume is fixed, the TV's volume goes over HDMI-CEC or IR and a slider here moves
 * nothing either (Fire TV Stick 4K Plus on an LG B5, issue #1307; X96 M200 box, #1104).
 */
object OutputDevice {
    /**
     * An HDMI playback device rather than a display: `ro.hdmi.device_type` lists 4 (playback) and
     * not 0 (TV), as on Android TV boxes and sticks; or `ro.build.characteristics` says stb / mbx,
     * as Amlogic and Rockchip box firmwares do. A TV lists 0, or nothing.
     */
    fun isPlaybackDevice(): Boolean {
        val types = prop("ro.hdmi.device_type").orEmpty().split(',').map { it.trim() }
        if ("4" in types && "0" !in types) return true
        return prop("ro.build.characteristics").orEmpty().let { "stb" in it || "mbx" in it }
    }

    /** Android's volume is fixed (Android 5.0+): the sound's level is set past this device. */
    fun volumeFixed(context: Context): Boolean = Build.VERSION.SDK_INT >= 21 && runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isVolumeFixed
    }.getOrDefault(false)

    /** Control Center shows a volume row: not where the volume is fixed. */
    fun showsVolume(context: Context): Boolean = !volumeFixed(context)

    /** Control Center shows a brightness row (a slider or the way to the picture settings): not on a box. */
    fun showsBrightness(): Boolean = !isPlaybackDevice()

    /**
     * One line for diagnostics: each sign [isPlaybackDevice] reads, and others that could tell a box
     * from a TV, so a change to these checks can be weighed on reports. Reports up to 2.11 gave only
     * the verdict, and it was "true" on TVs (TCL 55L2 #1612, Baofeng AML_T962 #1460) without saying
     * which sign said so. The battery is there for battery-powered players (GOOVIS D4, #1426).
     */
    fun describe(context: Context): String {
        val pm = context.packageManager
        val features = listOf(
            "live_tv" to "android.software.live_tv",
            "leanback" to "android.software.leanback",
            "hdmi_cec" to "android.hardware.hdmi.cec",
            "television" to "android.hardware.type.television",
        ).joinToString(" ") { (name, feature) -> "$name=${runCatching { pm.hasSystemFeature(feature) }.getOrDefault(false)}" }
        fun shown(key: String) = prop(key).orEmpty().ifEmpty { "-" }
        return "playback_device=${isPlaybackDevice()} (hdmi device_type=${shown("ro.hdmi.device_type")}, " +
            "characteristics=${shown("ro.build.characteristics")}) volume_fixed=${volumeFixed(context)}\n" +
            "            features $features; battery ${battery(context)}"
    }

    /** The last battery broadcast, without registering for more. */
    private fun battery(context: Context): String {
        val b = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return "-"
        return "present=${b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)}" +
            " level=${b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}/${b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)}" +
            " plugged=${b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)} status=${b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)}"
    }

    private fun prop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()
}
