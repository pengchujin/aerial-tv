package app.orchard.tv.ui.control

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/** How fast an overflowing caption travels, in dp per second: slow enough to read while it moves. */
internal const val MARQUEE_SPEED_DP = 28f

/** How long it rests at each end before turning round. */
internal const val MARQUEE_PAUSE_MS = 1500L

/** How far the text has to travel, in px, for its end to come into view; 0 when it fits. */
internal fun marqueeTravel(textWidth: Int, boxWidth: Int): Int = (textWidth - boxWidth).coerceAtLeast(0)

/** The time of one leg, in ms, at [speedPxPerSec]; 0 when there is nothing to travel. */
internal fun marqueeLegMs(travelPx: Int, speedPxPerSec: Float): Int =
    if (travelPx <= 0 || speedPxPerSec <= 0f) 0 else (travelPx * 1000f / speedPxPerSec).toInt().coerceAtLeast(1)

/** Whether anything moves: only text that overflows, only while it is [running] (Control Center on screen), never under reduced motion. */
internal fun marqueeScrolls(travelPx: Int, running: Boolean, reducedMotion: Boolean): Boolean =
    travelPx > 0 && running && !reducedMotion

/**
 * Where a caption of its own width sits in its box: centred when it fits, at the left edge when it is
 * wider (it then scrolls from there). A Box gives its child loose constraints, so wrapContentWidth cannot
 * centre it; the position has to come from the Box.
 */
private val FitCentredOverflowStart = Alignment { size, space, _ ->
    IntOffset(if (size.width >= space.width) 0 else (space.width - size.width) / 2, (space.height - size.height) / 2)
}

/**
 * One line of caption in a box [width] wide (it may be wider than the column it sits in; the caller's
 * layout does not change). Text that fits is centred. With [scroll], text that does not fit is
 * shown from its start and, while [running], travels slowly to its end and back, resting at both
 * ends (marqueeScrolls); it stops where it is when [running] ends, and nothing is animated while
 * it fits. Without [scroll], and under reduced motion, it is cut with an ellipsis.
 */
@Composable
internal fun CaptionText(
    text: String,
    style: TextStyle,
    width: Dp,
    scroll: Boolean,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    if (!scroll || reduced) {
        BasicText(
            text, modifier.requiredWidth(width), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = style.copy(textAlign = TextAlign.Center),
        )
        return
    }
    val density = LocalDensity.current
    var textWidth by remember(text) { mutableIntStateOf(0) }
    val boxWidth = with(density) { width.roundToPx() }
    val travel = marqueeTravel(textWidth, boxWidth)
    val speed = with(density) { MARQUEE_SPEED_DP.dp.toPx() }
    // A new text starts at its beginning.
    val offset = remember(text) { Animatable(0f) }
    LaunchedEffect(offset, travel, running) {
        // Not running: stay where it is (the panel is fading out), and move nothing.
        if (!marqueeScrolls(travel, running, reduced)) return@LaunchedEffect
        offset.snapTo(0f)
        val leg = tween<Float>(marqueeLegMs(travel, speed), easing = LinearEasing)
        while (true) {
            delay(MARQUEE_PAUSE_MS)
            offset.animateTo(travel.toFloat(), leg)
            delay(MARQUEE_PAUSE_MS)
            offset.animateTo(0f, leg)
        }
    }
    Box(modifier.requiredWidth(width).clipToBounds()) {
        BasicText(
            text, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, style = style,
            modifier = Modifier
                // Placed centred while it fits, from its start once it does not (decided at layout, so
                // a long text is never drawn centred for the frame before its width is known).
                .align(FitCentredOverflowStart)
                // Measured at its own width, not the box's.
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .onSizeChanged { textWidth = it.width }
                // Read in the draw phase: a frame of the scroll redraws the caption, it does not recompose anything.
                .graphicsLayer { translationX = if (travel > 0) -offset.value else 0f },
        )
    }
}
