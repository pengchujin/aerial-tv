package app.orchard.tv.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import app.orchard.tv.ui.theme.Motion
import kotlin.math.roundToInt

/**
 * The shelf's poster behind Home, over the wallpaper: it comes in with [show] (the shelf's
 * progress, 0 to 1), and the panel's glass is left as it is, not tinted to it. A new poster fades
 * in over the last. Only the draw phase reads the animations, so nothing here recomposes while
 * they run.
 */
@Composable
fun ShelfPoster(poster: ImageBitmap?, show: () -> Float) {
    var current by remember { mutableStateOf(poster) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(poster) {
        if (poster === current) return@LaunchedEffect
        // From nothing, the poster comes in with the shelf; replacing one it fades over it.
        previous = current
        current = poster
        if (previous == null) { fade.snapTo(1f); return@LaunchedEffect }
        fade.snapTo(0f)
        fade.animateTo(1f, tween(POSTER_FADE_MS, easing = Motion.EaseOut))
        previous = null
    }
    Canvas(Modifier.fillMaxSize()) {
        val shown = show()
        if (shown <= 0.01f) return@Canvas
        val dst = IntSize(size.width.roundToInt(), size.height.roundToInt())
        previous?.let { drawImage(it, dstSize = dst, alpha = shown * (1f - fade.value)) }
        current?.let { drawImage(it, dstSize = dst, alpha = shown * fade.value) }
    }
}

private const val POSTER_FADE_MS = 260
