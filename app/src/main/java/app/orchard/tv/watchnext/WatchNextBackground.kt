package app.orchard.tv.watchnext

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion

data class WatchNextBackground(val sourceKey: String, val backdrop: ImageBitmap, val frosted: ImageBitmap)
data class WatchNextBackgroundFrame(val image: WatchNextBackground? = null, val direction: Int = 1,
    val programKey: ProgramKey? = null)

internal fun backgroundSize(sourceWidth: Int, sourceHeight: Int, screenWidth: Int, screenHeight: Int): ArtworkSize? {
    if (sourceWidth !in 1..32768 || sourceHeight !in 1..32768 || screenWidth <= 0 || screenHeight <= 0) return null
    val aspect = sourceWidth.toDouble() / sourceHeight
    if (aspect !in WatchNextDefaults.BackgroundMinAspect..WatchNextDefaults.BackgroundMaxAspect) return null
    val width = minOf(screenWidth, WatchNextDefaults.BackgroundMaxWidth)
    val height = (width.toLong() * screenHeight / screenWidth).toInt()
    return if (height > 0 && sourceWidth >= width && sourceHeight >= height) ArtworkSize(width, height) else null
}

@Composable
fun watchNextBackground(state: WatchNextViewModel, program: WatchNextProgram?, enabled: Boolean,
    width: Int, height: Int, frozen: Boolean, paused: Boolean = false): WatchNextBackgroundFrame {
    return rememberWatchNextBackground(listOf(program, width, height), enabled && program != null, frozen,
        state.navigationDirection, program?.key, state.records.mapTo(mutableSetOf()) { it.key }, paused) {
        state.artwork.background(requireNotNull(program), width, height)
    }
}

/** Retain the displayed background until the next visit resolves, never publish an intermediate null. */
@Composable
internal fun rememberWatchNextBackground(request: Any?, enabled: Boolean, frozen: Boolean,
    direction: Int = 1, programKey: ProgramKey? = null,
    allowedKeys: Set<ProgramKey>? = null,
    paused: Boolean = false,
    load: suspend () -> WatchNextBackground?): WatchNextBackgroundFrame {
    var resolved by remember(enabled) { mutableStateOf<Pair<Any?, WatchNextBackgroundFrame>?>(null) }
    val freeze = rememberUpdatedState(frozen)
    LaunchedEffect(request, enabled, paused) {
        if (enabled && !paused) {
            val entryDirection = direction
            val ready = resolved?.takeIf { it.first == request }?.second
                ?: WatchNextBackgroundFrame(load(), entryDirection, programKey)
            snapshotFlow { freeze.value }.collect { held ->
                if (!held) resolved = request to ready
            }
        }
    }
    // Retention is visual continuity, never permission to show a removed source or record.
    val result = resolved?.second ?: WatchNextBackgroundFrame()
    return result.takeIf { it.programKey == null || allowedKeys == null || it.programKey in allowedKeys }
        ?: WatchNextBackgroundFrame()
}

private data class WatchNextPicture(val image: ImageBitmap?, val coversFilm: Boolean)

/** Keep film coverage with each picture, including the outgoing half of a transition. */
@Composable
internal fun WatchNextPictureTransition(
    image: ImageBitmap?, coversFilm: Boolean, direction: Int = 1, animate: Boolean = true,
    revealFilm: Boolean = false,
    filmCover: () -> Float, content: @Composable (ImageBitmap?, () -> Float) -> Unit,
) {
    WatchNextBackgroundTransition(WatchNextPicture(image, coversFilm), direction, animate) { picture ->
        // A carried screensaver reveals the existing player without changing the retained
        // picture's identity, so waking does not start another programme slide.
        content(picture.image) { if (picture.coversFilm && !revealFilm) 0f else filmCover() }
    }
}

/** Full-screen coverage is partitioned; only the narrow seam is blended in a bounded layer. */
@Composable
fun <T> WatchNextBackgroundTransition(target: T, direction: Int = 1, animate: Boolean = true,
    content: @Composable (T) -> Unit) {
    val reduced = LocalReducedMotion.current
    var shown by remember { mutableStateOf(target) }
    var previous by remember { mutableStateOf(target) }
    var transitioning by remember { mutableStateOf(false) }
    var travel by remember { mutableStateOf(1) }
    val progress = remember { Animatable(1f) }
    val layerPaint = remember { Paint() }
    LaunchedEffect(target, reduced, animate) {
        if (shown == target && !transitioning) return@LaunchedEffect
        if (reduced || !animate) {
            shown = target; previous = target; transitioning = false; progress.snapTo(1f)
        } else {
            travel = if (direction < 0) -1 else 1
            previous = if (progress.value >= 0.5f) shown else previous
            shown = target; transitioning = true
            progress.snapTo(0f)
            progress.animateTo(1f, tween(WatchNextDefaults.BackgroundTransitionMs, easing = Motion.EaseOut))
            previous = shown; transitioning = false
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        if (transitioning) Box(Modifier.fillMaxSize().drawWithContent {
            val seam = if (travel > 0) (1f - progress.value) * size.width else progress.value * size.width
            val half = minOf(WatchNextDefaults.BackgroundBlendDp.dp.toPx() / 2f,
                minOf(progress.value, 1f - progress.value) * size.width)
            clipRect(left = if (travel > 0) 0f else (seam - half).coerceAtLeast(0f),
                right = if (travel > 0) (seam + half).coerceAtMost(size.width) else size.width) { this@drawWithContent.drawContent() }
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val half = minOf(WatchNextDefaults.BackgroundBlendDp.dp.toPx() / 2f,
                    minOf(progress.value, 1f - progress.value) * size.width)
                translationX = travel * (-progress.value * size.width + half)
            }) { content(previous) }
        }
        Box(Modifier.fillMaxSize().drawWithContent {
            if (!transitioning || progress.value >= 1f) { drawContent(); return@drawWithContent }
            if (progress.value <= 0f) return@drawWithContent
            val seam = if (travel > 0) (1f - progress.value) * size.width else progress.value * size.width
            val half = minOf(WatchNextDefaults.BackgroundBlendDp.dp.toPx() / 2f,
                minOf(progress.value, 1f - progress.value) * size.width)
            val left = (seam - half).coerceAtLeast(0f)
            val right = (seam + half).coerceAtMost(size.width)
            // Draw the opaque region once, then only the overlapping seam with an alpha ramp.
            clipRect(left = if (travel > 0) right else 0f, right = if (travel > 0) size.width else left) {
                this@drawWithContent.drawContent()
            }
            clipRect(left = left, right = right) {
                val canvas = drawContext.canvas
                canvas.saveLayer(Rect(left, 0f, right, size.height), layerPaint)
                try {
                    this@drawWithContent.drawContent()
                    drawRect(Brush.horizontalGradient(
                        if (travel > 0) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent),
                        startX = seam - half, endX = seam + half,
                    ), blendMode = BlendMode.DstIn)
                } finally { canvas.restore() }
            }
        }) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                val half = minOf(WatchNextDefaults.BackgroundBlendDp.dp.toPx() / 2f,
                    minOf(progress.value, 1f - progress.value) * size.width)
                translationX = if (transitioning) travel * ((1f - progress.value) * size.width - half) else 0f
            }) { content(shown) }
        }
    }
}
