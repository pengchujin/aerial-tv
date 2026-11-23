package app.orchard.tv.system

/**
 * When a device switched on over HDMI-CEC on a Whaley TV, and on which port. Pure policy; the TV
 * side is WhaleyCecSwitch.
 *
 * On a WTV55K1J (MStar, Android 5.0.2, 2026-11-20) a PS5 woken by its controller sent Text View On
 * and Active Source; the TV's middleware let both pass ("CEC Command by pass") with an app in front,
 * and what reached apps was only E_CEC_CTRL_DEVICE_STATUS_CHANGED, with no port. The port shows in
 * the hot-plug state instead: HDMI 1 went from not connected to connected at 17.3 s, the CEC events
 * came at 18.6 s. A port coming up and a CEC event within [WINDOW_MS] of each other is a device
 * asking for the picture; either alone is not (a console's maintenance wake, a box on a power strip,
 * a console going to standby).
 */
class WhaleyCecWake {
    private var last: List<Boolean>? = null
    private val roseAt = HashMap<Int, Long>()
    private var cecAt: Long? = null

    /** The ports' connected state at [now] (index 0 = HDMI 1); the port to open, or null. */
    fun onPorts(now: Long, connected: List<Boolean>): Int? {
        last?.let { before ->
            connected.forEachIndexed { i, up -> if (up && before.getOrNull(i) == false) roseAt[i + 1] = now }
        }
        connected.forEachIndexed { i, up -> if (!up) roseAt.remove(i + 1) }
        last = connected
        return due(now)
    }

    /** A CEC device status event at [now]; the port to open, or null. */
    fun onCec(now: Long): Int? {
        cecAt = now
        return due(now)
    }

    private fun due(now: Long): Int? {
        val cec = cecAt ?: return null
        val port = roseAt.entries
            .filter { (_, at) -> kotlin.math.abs(at - cec) <= WINDOW_MS && now - at <= WINDOW_MS }
            .maxByOrNull { it.value }?.key ?: return null
        roseAt.remove(port)
        cecAt = null
        return port
    }

    companion object {
        const val WINDOW_MS = 5_000L
    }
}
