package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

/**
 * Whaley (微鲸 WTV55K1J, MStar, Android 5.0.2): the power light breathes from power-on until the
 * stock launcher turns it off with LEDUtility.enableLED(false), which is
 * TvManager.setLedMode(1, 0) (issue #902 on WTV43K1J found the symptom). Once Aerial is Home, or
 * comes up before the stock launcher has drawn, nothing makes that call and the light stays on.
 * Aerial makes the same call itself, once per boot; checked on a WTV55K1J with the stock
 * launcher hidden (the light went off, by eye; PR #1624).
 *
 * Known by Whaley's player being there (the same check as the inputs); the vendor class is on the
 * boot class path of that firmware and reached by reflection, so other TVs never load it.
 */
object WhaleyBoot {
    private const val PREFS = "system"
    private const val LIGHT_BOOT_ID = "whaley_light_boot_id"
    private const val LED_OFF = 1

    private val PLAYER = ComponentName("com.whaley.tv.tvplayer.ui", "com.whaley.tv.tvplayer.ui.RootActivity")

    fun isWhaley(context: Context): Boolean =
        runCatching { context.packageManager.getActivityInfo(PLAYER, 0) }.isSuccess

    fun newBoot(bootId: String?, handled: String?): Boolean = !bootId.isNullOrBlank() && bootId != handled

    /** Turns the power-on light off once per boot; a no-op anywhere but Whaley. */
    fun turnOffPowerLight(context: Context) {
        if (!isWhaley(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bootId = BootOpen.BOOT_ID
        if (!newBoot(bootId, prefs.getString(LIGHT_BOOT_ID, null))) return
        prefs.edit().putString(LIGHT_BOOT_ID, bootId).commit()
        thread(name = "WhaleyLight") {
            runCatching {
                val tv = Class.forName("com.mstar.android.tvapi.common.TvManager")
                val manager = tv.getMethod("getInstance").invoke(null)
                tv.getMethod("setLedMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(manager, LED_OFF, 0)
            }.onSuccess { DiagLog.i("whaley: power light turned off") }
                .onFailure { DiagLog.i("whaley: power light not turned off: $it") }
        }
    }
}
