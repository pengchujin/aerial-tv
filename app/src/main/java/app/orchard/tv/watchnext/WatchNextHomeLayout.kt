package app.orchard.tv.watchnext

/** Geometry only, in the host's design dp. The host retains grid metrics and scrolling. */
data class WatchNextHomeLayout(val top: Float, val textWidth: Float, val artLeft: Float,
    val artWidth: Float, val artHeight: Float, val indicatorTop: Float, val textLeft: Float = 56f) {
    // Share the lower 90% of the safe band between text and artwork. Short bands keep
    // up to 150dp available so the title and playback controls do not lose their budget.
    val contentHeight: Float get() = minOf(artHeight, maxOf(artHeight * 0.9f, 150f))
    val contentTop: Float get() = top + artHeight - contentHeight

    companion object {
        fun calculate(canvasWidth: Float, shelfTop: Float, oneRow: Boolean,
            largeClock: Boolean = false, clockRight: Boolean = false,
        ): WatchNextHomeLayout {
            // Keep both the original corner clock and status pill clear; compact content gives
            // up optional details rather than moving above the host's controls.
            val top = 64f
            val text = minOf(if (oneRow) 376f else 440f,
                if (largeClock) (canvasWidth - 112f - 48f) / 2f else canvasWidth - 112f).coerceAtLeast(0f)
            val textLeft = if (largeClock && !clockRight) canvasWidth - 56f - text else 56f
            // The clock keeps its original side and dimensions. A local poster would occupy
            // that same space; only the optional full-screen backdrop remains in this layout.
            val width = if (largeClock) 0f else
                minOf(456f, canvasWidth - 56f - (56f + text) - 24f).coerceAtLeast(0f)
            val indicator = shelfTop - 12f - 24f
            return WatchNextHomeLayout(top, text, canvasWidth - 56f - width, width,
                (indicator - WatchNextDefaults.IndicatorClearanceDp - top).coerceAtLeast(0f), indicator, textLeft)
        }
    }
}

internal data class ArtworkSize(val width: Int, val height: Int)

/** Contain at any source resolution; reserve room for the card's shadow and entrance motion. */
internal fun artworkSize(sourceWidth: Int, sourceHeight: Int, columnWidth: Int, columnHeight: Int, density: Float): ArtworkSize? {
    if (sourceWidth <= 0 || sourceHeight <= 0 || density <= 0f) return null
    val aspect = sourceWidth.toDouble() / sourceHeight
    val inset = WatchNextDefaults.ArtworkInsetDp * 2.0 * density
    val height = minOf(columnHeight * WatchNextDefaults.HeightFraction.toDouble(),
        columnHeight - inset, (columnWidth - inset) / aspect).toInt()
    val width = (height * aspect).toInt()
    val minimum = WatchNextDefaults.MinArtworkSideDp * density
    return if (width >= minimum && height >= minimum) ArtworkSize(width, height) else null
}
