package app.orchard.tv.ui.control

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.orchard.tv.ui.theme.LocalReducedMotion

/**
 * A quarter arc turning around a Control Center round button while its action is under way (the
 * Wi-Fi radio switching). Composed only then, so nothing animates while idle; with reduced motion
 * it stands still.
 */
@Composable
internal fun BusyRing(color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val angle = if (reduced) null else rememberInfiniteTransition(label = "busy").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "busy-angle",
    )
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        val box = Size(size.width - 2 * inset, size.height - 2 * inset)
        drawArc(color.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
        drawArc(color, (angle?.value ?: 0f) - 90f, 90f, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}
