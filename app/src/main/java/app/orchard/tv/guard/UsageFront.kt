package app.orchard.tv.guard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

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

    /**
     * Margin above wall-clock "now" when discarding events from a future-dated bucket: an event
     * stamped during the Binder query is kept, while stale future timestamps are dropped.
     */
    const val FUTURE_SLACK_MS = 5_000L

    /**
     * Reads usage events from [begin] onward without capping the query at [now].
     *
     * In AOSP's UserUsageStatsService.queryStats, the in-memory daily stats bucket is included
     * only when `endTime > currentStats.beginTime`. When a TV's usage-stats database has a
     * future-dated active bucket (seen on Xiaomi MiTV-MFTR0, Android 14, OS3.0.17.0.UFTMATV, with
     * an in-memory daily bucket dated 2027-12-15 while wall-clock time was 2026-11-22), new events
     * are still recorded into that bucket with real timestamps, but `queryEvents(begin, now)` skips
     * the in-memory bucket completely and returns empty. Passing [Long.MAX_VALUE] satisfies AOSP's
     * `validRange` (`beginTime <= currentTime && beginTime < endTime`) and includes the in-memory
     * bucket; callers then discard events stamped past `now + FUTURE_SLACK_MS`. Falls back to
     * `endTime = now` in case a firmware rejects future end times.
     */
    fun queryEvents(usm: UsageStatsManager, begin: Long, now: Long): UsageEvents? =
        queryEvents(begin, now, usm::queryEvents, UsageEvents::hasNextEvent)

    internal inline fun <T> queryEvents(
        begin: Long,
        now: Long,
        query: (Long, Long) -> T?,
        hasNext: (T) -> Boolean,
    ): T? {
        val start = begin.coerceAtLeast(0L)
        val events = query(start, Long.MAX_VALUE)
        if (events != null && hasNext(events)) return events
        return query(start, now)
    }

    data class Front(val pkg: String, val cls: String, val at: Long, val closed: Boolean)

    class Picker(private val maxAt: Long = Long.MAX_VALUE) {
        private var front: Front? = null
        private var pausedAt: Long? = null
        var skippedFuture: Boolean = false
            private set

        fun add(type: Int, pkg: String, cls: String?, at: Long) {
            if (at > maxAt) {
                skippedFuture = true
                return
            }
            if (cls == null) return
            val f = front
            when (type) {
                MOVE_TO_FOREGROUND -> if (f == null || at >= f.at) {
                    front = Front(pkg, cls, at, closed = false)
                    pausedAt = null
                }
                MOVE_TO_BACKGROUND -> if (f != null && f.pkg == pkg && f.cls == cls && at >= f.at) {
                    front = f.copy(closed = true)
                    pausedAt = at
                }
            }
        }

        fun result(now: Long = Long.MAX_VALUE, activitySettleMs: Long = 0): Front? {
            val f = front ?: return null
            val paused = pausedAt
            // ACTIVITY_PAUSED also precedes another activity in the same app. MiTV-ASTP0
            // (Android 9) reports SmartTube's BrowseActivity paused before its settings
            // activity resumes; taking that gap for an exit brings Home over the dialog.
            return if (f.closed && paused != null && now - paused < activitySettleMs) {
                f.copy(closed = false)
            } else f
        }
    }
}
