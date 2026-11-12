package app.orchard.tv.system

/**
 * How often Home may start itself again as Home (MainActivity.reopenAsHome): once in [WINDOW_MS].
 * A second start without the Home category that soon means the firmware turns Home intents into a
 * start by component, and reopening again would only loop (issue #1278). For this process.
 */
object HomeReopen {
    const val WINDOW_MS = 10_000L

    private var lastAt = Long.MIN_VALUE / 2

    /** Whether reopening at [now] (elapsed realtime) is allowed; if so, it counts as done. */
    @Synchronized
    fun mayReopen(now: Long): Boolean {
        if (now - lastAt < WINDOW_MS) return false
        lastAt = now
        return true
    }

    @Synchronized
    internal fun reset() {
        lastAt = Long.MIN_VALUE / 2
    }
}
