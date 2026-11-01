package app.orchard.tv.guard

/**
 * What usage stats say is in front: the activity that last moved to the front, and whether it has
 * moved to the back since with nothing brought up after it. LeTV X4-50 (eUI, Android 6.0) shows no
 * launcher at all when the last app is backed out of: the screen stays black with no window, and
 * usage stats end with that app's MOVE_TO_BACKGROUND. Taking its MOVE_TO_FOREGROUND alone, the
 * guard thought the app was still open and left the black screen (issue #1023).
 */
internal object UsageFront {
    const val MOVE_TO_FOREGROUND = 1 // UsageEvents.Event.MOVE_TO_FOREGROUND / ACTIVITY_RESUMED
    const val MOVE_TO_BACKGROUND = 2 // UsageEvents.Event.MOVE_TO_BACKGROUND / ACTIVITY_PAUSED

    data class Front(val pkg: String, val cls: String, val at: Long, val closed: Boolean)

    class Picker {
        private var front: Front? = null

        fun add(type: Int, pkg: String, cls: String?, at: Long) {
            if (cls == null) return
            val f = front
            when (type) {
                MOVE_TO_FOREGROUND -> front = Front(pkg, cls, at, closed = false)
                MOVE_TO_BACKGROUND -> if (f != null && f.pkg == pkg && f.cls == cls && at >= f.at) front = f.copy(closed = true)
            }
        }

        fun result(): Front? = front
    }
}
