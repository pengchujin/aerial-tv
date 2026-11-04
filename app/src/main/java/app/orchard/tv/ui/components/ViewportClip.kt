package app.orchard.tv.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/** A rectangular viewport for both drawing and hit testing, without moving its content. */
fun Modifier.clipBelow(top: Dp): Modifier = clip(TopInset(top))

private data class TopInset(val top: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rectangle(Rect(0f, with(density) { top.toPx() }.coerceIn(0f, size.height), size.width, size.height))
}
