package app.orchard.tv.system

import android.content.Context
import android.media.AudioManager
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

    private fun prop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()
}
