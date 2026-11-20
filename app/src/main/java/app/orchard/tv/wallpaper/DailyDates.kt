package app.orchard.tv.wallpaper

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** How long one of Bing's daily photos stays the newest. */
internal const val DAILY_PHOTO_MS = 24 * 60 * 60 * 1000L

/**
 * Whether the newest of Bing's photos, by their `fullstartdate` (yyyyMMddHHmm in UTC), is still
 * the current one at [now]: the next one starts a day after it. Blank or unreadable starts (an
 * index written before they were kept) count for nothing, so such an index is fetched again.
 */
internal fun dailyIsCurrent(starts: List<String>, now: Long): Boolean {
    val format = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
    val newest = starts.mapNotNull { start -> start.takeIf { it.length == 12 }?.let { runCatching { format.parse(it)?.time }.getOrNull() } }
        .maxOrNull() ?: return false
    return now - newest < DAILY_PHOTO_MS
}
