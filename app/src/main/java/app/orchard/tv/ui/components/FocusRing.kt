package app.orchard.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val RingWidth = 2.dp

/**
 * The high-contrast focus frame on a card, as on the app tiles: a thin white ring flush against its
 * edge, outside it, with the faint dark edge that keeps it visible over bright sky or snow. Drawn
 * behind the content, so it goes before the card's clip in the chain; [show] is 0 to 1.
 */
fun Modifier.focusRing(corner: Dp, show: Float): Modifier = if (show <= 0.01f) this else drawBehind {
    val w = RingWidth.toPx()
    val r = corner.toPx()
    val dp = 1.dp.toPx()
    // Each line is centred [d] outside the card's edge.
    fun around(d: Float, width: Float, color: Color) = drawRoundRect(
        color, Offset(-d, -d), Size(size.width + 2 * d, size.height + 2 * d), CornerRadius(r + d), Stroke(width),
    )
    around(w + dp, 2 * dp, Color.Black.copy(alpha = 0.3f * show))
    around(w / 2, w, Color.White.copy(alpha = show))
}
