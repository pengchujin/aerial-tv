package app.orchard.tv.wallpaper

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** How long one of Bing's daily photos stays the newest. */
internal const val DAILY_PHOTO_MS = 24 * 60 * 60 * 1000L

private const val DAILY_ID_PREFIX = "daily:"
private const val DAILY_LATEST_ID = "daily:latest"

/** One entry in Bing's daily photo index (`daily/index.json`). */
internal data class DailyEntry(
    val date: String,
    val title: String,
    val caption: String,
    val start: String,
)

/**
 * The next `daily/index.json` to write and the `.jpg` filenames to keep in `daily/`.
 * [preservedFile] is the `.jpg` filename of a manually pinned daily photo kept past Bing's
 * 8-day window, or null when nothing outside the newly fetched list needs to be kept.
 */
internal data class DailyIndexPlan(
    val entries: List<DailyEntry>,
    val keepFiles: Set<String>,
    val preservedFile: String? = null,
)

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

/** The pinned Bing date (e.g. `20261110`) if [selectedId] names a specific daily photo, else null. */
internal fun pinnedDailyDate(selectedId: String): String? =
    if (selectedId != DAILY_LATEST_ID && selectedId.startsWith(DAILY_ID_PREFIX)) {
        selectedId.removePrefix(DAILY_ID_PREFIX).takeIf { it.isNotEmpty() }
    } else {
        null
    }

/**
 * Merges the newly [fetched] Bing daily entries with the currently selected pinned daily photo
 * from [existing], if [selectedId] names a specific daily date that rolled out of [fetched] and
 * its `.jpg` file is present in [existingFiles] (issue #1653).
 *
 * The pinned entry is appended after [fetched] so Bing's newest photo stays first for
 * `daily:latest`, and [dailyIsCurrent] continues to measure freshness from the newest `start`.
 */
internal fun mergeDailyIndex(
    fetched: List<DailyEntry>,
    existing: List<DailyEntry>,
    selectedId: String,
    existingFiles: Set<String>,
): DailyIndexPlan {
    val pinnedDate = pinnedDailyDate(selectedId)
    val pinned = if (pinnedDate != null && fetched.none { it.date == pinnedDate } && "$pinnedDate.jpg" in existingFiles) {
        existing.firstOrNull { it.date == pinnedDate }
    } else {
        null
    }
    val entries = if (pinned != null) fetched + pinned else fetched
    val keepFiles = entries.mapTo(LinkedHashSet(entries.size)) { "${it.date}.jpg" }
    return DailyIndexPlan(entries, keepFiles, preservedFile = pinned?.let { "${it.date}.jpg" })
}
