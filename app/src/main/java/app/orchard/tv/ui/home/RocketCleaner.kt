package app.orchard.tv.ui.home

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.system.BackgroundCleaner
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CleaningPhase { Idle, Flying, Completing, Done, Returning }

/** Kept composed while hidden: changing focus must not cancel the backend operation. */
@Composable
fun RocketCleaner(
    visible: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(CleaningPhase.Idle) }
    var freed by remember { mutableStateOf(0L) }
    var supported by remember { mutableStateOf(true) }
    val label = remember { Animatable(0f) }
    // One flight cycle: the rocket leaves up its own axis, then comes in again from behind.
    val flight = remember { Animatable(0f) }
    // The rocket's presence (scale and alpha) and the result symbol's, swapped like SF Symbols'
    // replace effect; trace draws the result's stroke on.
    val rocketIn = remember { Animatable(1f) }
    val symbol = remember { Animatable(0f) }
    val trace = remember { Animatable(0f) }
    val expanded = phase != CleaningPhase.Idle && phase != CleaningPhase.Returning
    val reveal by animateFloatAsState(
        if (visible) 1f else 0f,
        tween(if (reduced) 120 else 380, easing = Motion.Drawer), label = "cleaner-reveal",
    )
    val width by animateDpAsState(
        if (expanded) 184.dp else 34.dp,
        tween(if (reduced) 120 else 440, easing = Motion.Drawer), label = "cleaner-width",
    )
    val caption = when {
        phase == CleaningPhase.Flying -> stringResource(R.string.cleanup_running)
        !supported -> stringResource(R.string.cleanup_unavailable)
        else -> stringResource(R.string.cleanup_done, freed)
    }
    val description = if (phase == CleaningPhase.Idle) stringResource(R.string.cleanup_action) else caption
    // Runs only while cleaning and shown; Completing takes the rocket out from where it is.
    LaunchedEffect(phase, reduced, visible) {
        if (phase == CleaningPhase.Flying && !reduced && visible) {
            while (true) {
                flight.snapTo(0f)
                flight.animateTo(1f, tween(FLIGHT_MS, easing = LinearEasing))
            }
        } else if (phase == CleaningPhase.Idle || phase == CleaningPhase.Flying) {
            flight.snapTo(0f)
        }
    }
    // The parent Row is right-anchored. Both reveal and expansion grow toward its left edge.
    Box(Modifier.width((width + 10.dp) * reveal).height(34.dp), contentAlignment = Alignment.CenterEnd) {
        Pressable(
            onClick = {
                if (phase == CleaningPhase.Idle && enabled && visible) scope.launch {
                    phase = CleaningPhase.Flying
                    supported = true
                    rocketIn.snapTo(1f)
                    symbol.snapTo(0f)
                    trace.snapTo(0f)
                    label.snapTo(0f)
                    launch { label.animateTo(1f, tween(240, delayMillis = 140, easing = Motion.EaseOut)) }
                    val began = SystemClock.elapsedRealtime()
                    try {
                        val result = BackgroundCleaner.clean(context)
                        freed = result.freedMb
                        supported = result.supported
                        DiagLog.i("background cleanup: supported=$supported freedMb=$freed")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        supported = false
                    }
                    if (!reduced) delay((850 - (SystemClock.elapsedRealtime() - began)).coerceAtLeast(0L))
                    phase = CleaningPhase.Completing
                    if (reduced) {
                        rocketIn.animateTo(0f, tween(120))
                        trace.snapTo(1f)
                        symbol.animateTo(1f, tween(120))
                    } else {
                        // Finish the current flight out of the circle instead of jumping back.
                        val out = if (flight.value < 0.5f) 0.5f else 1.5f
                        flight.animateTo(out, tween(((out - flight.value) * FLIGHT_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
                        rocketIn.snapTo(0f)
                        flight.snapTo(0f)
                        launch { trace.animateTo(1f, tween(340, delayMillis = 60, easing = Motion.EaseOut)) }
                        symbol.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
                    }
                    phase = CleaningPhase.Done
                    delay(2_000)
                    label.animateTo(0f, tween(220, easing = Motion.EaseOut))
                    phase = CleaningPhase.Returning
                    if (reduced) {
                        symbol.animateTo(0f, tween(120))
                        rocketIn.animateTo(1f, tween(120))
                    } else {
                        launch { symbol.animateTo(0f, tween(200, easing = Motion.EaseOut)) }
                        delay(90)
                        rocketIn.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
                    }
                    trace.snapTo(0f)
                    phase = CleaningPhase.Idle
                }
            },
            enabled = enabled && visible,
            focusRequester = focusRequester,
            focusedScale = 1.06f,
            modifier = modifier
                .wrapContentWidth(Alignment.End, unbounded = true)
                .requiredWidth(width).height(34.dp).offset(x = (-10).dp)
                .graphicsLayer {
                    alpha = reveal
                    translationX = if (reduced) 0f else (1f - reveal) * 24.dp.toPx()
                    scaleX = if (reduced) 1f else 0.84f + 0.16f * reveal
                    scaleY = if (reduced) 1f else 0.84f + 0.16f * reveal
                }
                .semantics { contentDescription = description; role = Role.Button },
        ) { state ->
            LaunchedEffect(state.focused) { onFocused(state.focused) }
            val tint by animateColorAsState(
                if (state.focused) Palette.Focus else Color.Transparent,
                tween(160, easing = Motion.EaseOut), label = "cleaner-focus",
            )
            val fg = if (state.focused) Palette.OnFocus else Color.White.copy(alpha = 0.9f)
            Row(Modifier.liquidGlass(17.dp).background(tint).height(34.dp).width(width), verticalAlignment = Alignment.CenterVertically) {
                // Clipped to the circle, so the rocket leaves through the glass edge rather than over it.
                Box(Modifier.size(34.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    if (rocketIn.value > 0.001f) {
                        GlyphIcon(Glyph.Rocket, fg, 18.dp, Modifier.graphicsLayer {
                            val f = flight.value % 1f
                            // Out: accelerates up the rocket's 45° axis and fades. In: glides back
                            // from the opposite corner and settles.
                            val along = if (f < 0.5f) cubic(f * 2f) else -cubic(1f - (f - 0.5f) * 2f)
                            val travel = ROCKET_TRAVEL.dp.toPx() * along
                            translationX = travel
                            translationY = -travel
                            val presence = rocketIn.value.coerceIn(0f, 1f)
                            alpha = (1f - abs(along)) * presence
                            scaleX = 0.5f + 0.5f * rocketIn.value
                            scaleY = scaleX
                        })
                    }
                    if (symbol.value > 0.001f) ResultSymbol(supported, fg, { symbol.value }, { trace.value })
                }
                if (width > 42.dp) {
                    BasicText(caption, style = Type.Callout.copy(color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 3.dp, end = 12.dp).graphicsLayer { alpha = label.value })
                }
            }
        }
    }
}

private const val FLIGHT_MS = 900
/** How far, in dp along each axis, the rocket travels before it is out of the 34dp circle. */
private const val ROCKET_TRAVEL = 15f

private fun cubic(t: Float) = t * t * t

/**
 * The result in the rocket's place, with the weight of a bold SF Symbol: a tick when the cleanup
 * ran, an exclamation mark when the system would not allow it. [presence] pops it in with a spring,
 * [trace] draws the stroke on.
 */
@Composable
private fun ResultSymbol(supported: Boolean, color: Color, presence: () -> Float, trace: () -> Float) {
    val path = remember { Path() }
    val piece = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(Modifier.size(20.dp).graphicsLayer {
        val p = presence()
        alpha = p.coerceIn(0f, 1f)
        scaleX = 0.4f + 0.6f * p
        scaleY = scaleX
    }) {
        val w = size.width
        val stroke = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val t = trace().coerceIn(0f, 1f)
        path.reset()
        if (supported) {
            path.moveTo(w * 0.2f, w * 0.53f)
            path.lineTo(w * 0.42f, w * 0.75f)
            path.lineTo(w * 0.81f, w * 0.27f)
        } else {
            path.moveTo(w * 0.5f, w * 0.2f)
            path.lineTo(w * 0.5f, w * 0.58f)
        }
        measure.setPath(path, false)
        piece.reset()
        measure.getSegment(0f, measure.length * t, piece, true)
        drawPath(piece, color, style = stroke)
        if (!supported && t > 0.6f) {
            drawCircle(color.copy(alpha = color.alpha * ((t - 0.6f) / 0.4f)), 1.45.dp.toPx(), Offset(w * 0.5f, w * 0.8f))
        }
    }
}