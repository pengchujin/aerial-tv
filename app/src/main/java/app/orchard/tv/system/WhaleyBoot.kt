package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Whaley (微鲸 WTV55K1J, MStar, Android 5.0.2): two things the stock launcher does at power-on
 * that are lost once Aerial is Home, or comes up before the stock launcher has drawn.
 *
 * - The power light breathes from power-on until the stock launcher turns it off with
 *   LEDUtility.enableLED(false), which is TvManager.setLedMode(1, 0) (issue #902 on WTV43K1J
 *   found the symptom). Aerial makes the same call itself, so nothing has to wait for the stock
 *   launcher; checked on a WTV55K1J with the stock launcher hidden (the light went off, by eye).
 * - The TV starts on an HDMI input only if something is plugged in there. With a Whaley HDMI
 *   input as what the TV starts on, Aerial first asks the TV service which ports have a device:
 *   hot-plug (a powered device) or a CEC device (a console in standby). The chosen port if it has
 *   one, else the lowest port that has one, else stay on Home. Entering the input lets HDMI-CEC
 *   wake the console. On that WTV55K1J with nothing powered, every port read connected=false and
 *   no CEC device, and Home stayed; a console in standby is not tried yet.
 *
 * Known by Whaley's player being there (the same check as the inputs); the vendor classes are on
 * the boot class path of that firmware and reached by reflection, so other TVs never load them.
 */
object WhaleyBoot {
    data class HdmiPort(val number: Int, val connected: Boolean, val cecDevices: Int) {
        val occupied: Boolean get() = connected || cecDevices > 0
    }

    /** How long hot-plug and CEC discovery get after Aerial starts at power-on. */
    const val SCAN_MS = 8_000L
    private const val SCAN_STEP_MS = 500L
    private const val PORTS = 3

    /** EnumWtvInputSource ordinals, as in SystemState.whaleySources: HDMI 1–3 are 4–6. */
    private const val HDMI1_ORDINAL = 4
    private const val SOURCE_PREFIX = "whaley:"

    private const val PREFS = "system"
    private const val LIGHT_BOOT_ID = "whaley_light_boot_id"
    private const val LED_OFF = 1

    private val PLAYER = ComponentName("com.whaley.tv.tvplayer.ui", "com.whaley.tv.tvplayer.ui.RootActivity")

    fun isWhaley(context: Context): Boolean =
        runCatching { context.packageManager.getActivityInfo(PLAYER, 0) }.isSuccess

    /** The HDMI port (1–3) a Whaley input id names, or null for anything else. */
    fun hdmiPort(sourceId: String?): Int? {
        val ordinal = sourceId?.removePrefix(SOURCE_PREFIX)?.takeIf { it != sourceId }?.toIntOrNull() ?: return null
        return (ordinal - HDMI1_ORDINAL + 1).takeIf { it in 1..PORTS }
    }

    fun sourceId(port: Int): String = SOURCE_PREFIX + (HDMI1_ORDINAL + port - 1)

    /** The chosen port if it has a device, else the lowest that has one, else null (stay on Home). */
    fun choose(chosen: Int, ports: List<HdmiPort>): Int? =
        ports.firstOrNull { it.number == chosen && it.occupied }?.number
            ?: ports.filter { it.occupied }.minOfOrNull { it.number }

    /** Done early only once the chosen port has a device; any other gets the whole window. */
    fun scanDone(chosen: Int, ports: List<HdmiPort>, elapsedMs: Long): Boolean =
        elapsedMs >= SCAN_MS || ports.any { it.number == chosen && it.occupied }

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

    /**
     * Polls the ports for up to [SCAN_MS]; the port to open instead of [chosen], or null to stay
     * on Home. Null as well when the TV service cannot be read.
     */
    suspend fun findPort(chosen: Int): Int? = withContext(Dispatchers.IO) {
        val start = SystemClock.elapsedRealtime()
        var ports: List<HdmiPort>
        while (true) {
            ports = readPorts() ?: return@withContext null
            if (scanDone(chosen, ports, SystemClock.elapsedRealtime() - start)) break
            delay(SCAN_STEP_MS)
        }
        choose(chosen, ports).also { port ->
            DiagLog.i(
                "whaley: HDMI scan after ${SystemClock.elapsedRealtime() - start} ms, " +
                    ports.joinToString { "HDMI ${it.number} conn=${it.connected} cec=${it.cecDevices}" } +
                    " -> ${port?.let { "HDMI $it" } ?: "stay on Home"}",
            )
        }
    }

    private fun readPorts(): List<HdmiPort>? = runCatching {
        val sourceEnum = Class.forName("tv.whaley.api.mode.EnumWtvInputSource")
        val sources = sourceEnum.enumConstants ?: return null
        val input = Class.forName("tv.whaley.api.manager.WtvApiInputSourceManger").getMethod("getInstance").invoke(null)
        val cec = Class.forName("tv.whaley.api.manager.WtvApiCecManager").getMethod("getInstance").invoke(null)
        val connected = input.javaClass.getMethod("getHdmiConnectedStatus", sourceEnum)
        val cecCount = cec.javaClass.getMethod("getCecListCnt", Int::class.javaPrimitiveType)
        (1..PORTS).map { port ->
            HdmiPort(
                number = port,
                connected = connected.invoke(input, sources[HDMI1_ORDINAL + port - 1]) as? Boolean ?: false,
                cecDevices = cecCount.invoke(cec, port - 1) as? Int ?: 0,
            )
        }
    }.onFailure { DiagLog.i("whaley: HDMI state not readable: $it") }.getOrNull()
}
