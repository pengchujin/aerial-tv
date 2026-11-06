package app.orchard.tv.watchnext

import app.orchard.tv.apps.AppEntry

data class ProgramKey(val source: String, val id: Long)
data class WatchNextProgram(
    val key: ProgramKey,
    val title: String? = null,
    val episodeTitle: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val contentType: Int? = null,
    val watchNextType: Int? = null,
    val poster: String? = null,
    val thumbnail: String? = null,
    val posterAspect: Int? = null,
    val thumbnailAspect: Int? = null,
    val intentUri: String? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val engagementMs: Long? = null,
    val browsable: Boolean? = null,
    val description: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val reviewRating: String? = null,
    val reviewRatingStyle: Int? = null,
) {
    val progress: Float? get() = if (durationMs != null && durationMs > 0 && positionMs != null && positionMs >= 0)
        (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat() else null
}

enum class WatchNextStatus { Unsupported, Unauthorized, Disabled, Loading, Empty, Ready, Failed }
data class WatchNextCapability(val supported: Boolean, val granted: Boolean, val requestable: Boolean)

fun AppEntry.isWatchNextSource() = source == null && folder == null && shortcut == null

internal fun effectivePrograms(raw: List<WatchNextProgram>, enabled: Set<String>, allowed: Set<String>): List<WatchNextProgram> =
    raw.filter { it.key.source in enabled && it.key.source in allowed && it.browsable != false }
        .distinctBy { it.key }
        .sortedWith(compareByDescending<WatchNextProgram> { it.engagementMs ?: Long.MIN_VALUE }
            .thenBy { it.key.source }.thenBy { it.key.id })

internal fun adjacentIndex(index: Int, delta: Int, size: Int): Int =
    if (size <= 1) 0 else (((index.toLong() + delta) % size + size) % size).toInt()

internal fun retainedKey(old: List<WatchNextProgram>, key: ProgramKey?, next: List<WatchNextProgram>): ProgramKey? =
    next.firstOrNull { it.key == key }?.key ?: next.getOrNull(old.indexOfFirst { it.key == key }.coerceAtLeast(0).coerceAtMost((next.size - 1).coerceAtLeast(0)))?.key

internal object WatchNextDefaults {
    const val DwellMs = 8_000L
    const val TransitionMs = 320
    const val BackgroundTransitionMs = 520
    const val BackgroundBlendDp = 56f
    const val ContentExitMs = 120
    const val ContentGapMs = 20L
    const val ContentEnterMs = 300
    const val ContentExitDp = 10f
    const val ContentEnterDp = 16f
    const val BackgroundMaxWidth = 1920
    const val BackgroundMinAspect = 1.45
    const val BackgroundMaxAspect = 2.4
    const val CacheBytes = 12 * 1024 * 1024
    const val CacheEntries = 3
    const val HeightFraction = 0.96f
    const val MinArtworkSideDp = 16f
    const val ArtworkInsetDp = 12f
    const val ArtworkCornerDp = 14f
    const val ArtworkElevationDp = 10f
    const val ArtworkShiftDp = 4f
    const val ArtworkInitialScale = 0.98f
    const val IndicatorClearanceDp = 8f
    const val CompactDetailLineSp = 16f
    const val SourceIconWidthDp = 66f
    const val SourceIconHeightDp = 38f
    const val DotStepDp = 18f
    const val DotPaddingDp = 12f
    const val DotDiameterDp = 8f
    const val DotMaxStretchDp = 12f
    const val RemoteMaxBytes = 8 * 1024 * 1024
    const val RemoteTimeoutMs = 5_000
    const val RemoteDeadlineMs = 15_000L
    const val RemoteRedirects = 3
    const val DiskCacheBytes = 64L * 1024 * 1024
    const val DiskCacheLifetimeMs = 24L * 60 * 60 * 1000
}

fun WatchNextStatus.messageResource(): Int = when (this) {
    WatchNextStatus.Unsupported -> app.orchard.tv.R.string.watch_next_unsupported
    WatchNextStatus.Unauthorized -> app.orchard.tv.R.string.watch_next_permission
    WatchNextStatus.Disabled -> app.orchard.tv.R.string.watch_next_disabled
    WatchNextStatus.Loading -> app.orchard.tv.R.string.watch_next_loading
    WatchNextStatus.Empty -> app.orchard.tv.R.string.watch_next_empty
    WatchNextStatus.Ready -> app.orchard.tv.R.string.watch_next_ready
    WatchNextStatus.Failed -> app.orchard.tv.R.string.watch_next_failed
}
