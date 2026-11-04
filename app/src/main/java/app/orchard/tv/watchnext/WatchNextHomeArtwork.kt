package app.orchard.tv.watchnext

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import app.orchard.tv.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/** Feature-owned image lifetime; the host only supplies visibility and its wallpaper fallback. */
internal data class WatchNextHomeArtwork(
    val frame: WatchNextBackgroundFrame,
    val localImage: Bitmap?,
    val coversFilm: Boolean,
    val transitionEnabled: Boolean,
    val visibleKeys: Set<ProgramKey>,
)

@Composable
internal fun rememberWatchNextHomeArtwork(
    state: WatchNextViewModel,
    program: WatchNextProgram?,
    layout: WatchNextHomeLayout,
    active: Boolean,
    previewingWallpaper: Boolean,
    frozen: Boolean,
    screenWidth: Int,
    screenHeight: Int,
): WatchNextHomeArtwork {
    val reduced = LocalReducedMotion.current
    val frame = watchNextBackground(state, program, active && !previewingWallpaper,
        screenWidth, screenHeight, frozen)
    val localImage = watchNextArtwork(state, program, layout,
        active && frame.image == null && layout.artWidth > 0f && layout.top >= 60f)
    var coversFilm by remember { mutableStateOf(false) }
    LaunchedEffect(frame.image != null, active, reduced) {
        if (!active) coversFilm = false
        else {
            if (!reduced) delay(WatchNextDefaults.BackgroundTransitionMs.toLong())
            coversFilm = frame.image != null
        }
    }
    val visibleKeys = remember(state.records) { state.records.mapTo(mutableSetOf()) { it.key } }
    return WatchNextHomeArtwork(frame, localImage, active && coversFilm,
        visibleKeys.isNotEmpty(), visibleKeys)
}

@Composable
private fun watchNextArtwork(state: WatchNextViewModel, program: WatchNextProgram?,
    layout: WatchNextHomeLayout, visible: Boolean,
): Bitmap? {
    val density = LocalDensity.current.density
    var image by remember(program, layout, visible, density) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(program, layout, visible, density) {
        if (program != null && visible) image = state.artwork.load(program,
            (layout.artWidth * density).toInt(), (layout.contentHeight * density).toInt(), density)
    }
    return image
}
