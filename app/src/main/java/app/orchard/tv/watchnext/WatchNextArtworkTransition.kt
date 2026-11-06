package app.orchard.tv.watchnext

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion

/** Only the displayed card is retained; decoding and caching still belong to Artwork. */
internal class WatchNextArtworkTransition(initial: Bitmap?, immediate: Boolean) {
    var image by mutableStateOf(initial)
    val visibility = Animatable(if (initial != null && immediate) 1f else 0f)
}

@Composable
internal fun rememberWatchNextArtworkTransition(
    displayed: ProgramKey, selected: ProgramKey, image: Bitmap?, animate: Boolean,
): WatchNextArtworkTransition {
    val reduced = LocalReducedMotion.current
    val state = remember(displayed) {
        WatchNextArtworkTransition(image.takeIf { displayed == selected }, reduced || !animate)
    }
    LaunchedEffect(displayed, selected, image, reduced, animate) {
        // Keep the old card through the content group's exit, including background preparation.
        // The displayed key changes only at the invisible handover (or an immediate reset).
        if (displayed != selected) return@LaunchedEffect
        if (reduced || !animate) {
            state.image = image
            state.visibility.snapTo(if (image != null) 1f else 0f)
        } else {
            if (state.image !== image) {
                if (state.image != null) state.visibility.animateTo(0f,
                    tween(WatchNextDefaults.ContentExitMs, easing = Motion.EaseOut))
                state.image = image
            }
            if (image != null) state.visibility.animateTo(1f,
                tween(WatchNextDefaults.TransitionMs, easing = Motion.EaseOut))
        }
    }
    return state
}
