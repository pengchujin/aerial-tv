package app.orchard.tv.ui.home

import android.graphics.Bitmap

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal val HomeTileFadeHeight = 24.dp

/** A fixed viewport for tiles; the shelf clock and status pill remain outside it. */
@Composable
internal fun HomeTileViewport(scroll: () -> Float, content: @Composable BoxScope.() -> Unit) {
    // Padding before clipping reserves the header for both drawing and pointer hit testing.
    // Keep all tile positions in the original full-screen coordinates by undoing that padding
    // inside the viewport. Scaled faces, focus frames and shadows are clipped together.
    Box(Modifier.fillMaxSize().padding(top = RowAboveTop).clipToBounds().drawWithCache {
        val fade = HomeTileFadeHeight.toPx().coerceAtMost(size.height)
        // Bake the ramp once per geometry change. Per-frame gradients are too costly on
        // older TVs; a one-pixel-wide alpha bitmap needs only a tiny cached texture.
        val maskHeight = fade.roundToInt().coerceAtLeast(1)
        val mask = Bitmap.createBitmap(IntArray(maskHeight) { y ->
            (((y + 0.5f) / maskHeight * 255f).roundToInt() shl 24)
        }, 1, maskHeight, Bitmap.Config.ARGB_8888).asImageBitmap()
        val paint = Paint()
        onDrawWithContent {
            // Draw disjoint regions, not a full-screen offscreen texture. Only the 24dp edge
            // needs an alpha mask; the wallpaper behind it must keep its original colors.
            clipRect(top = fade) { this@onDrawWithContent.drawContent() }
            clipRect(bottom = fade) {
                val canvas = drawContext.canvas
                canvas.saveLayer(Rect(0f, 0f, size.width, fade), paint)
                try {
                    this@onDrawWithContent.drawContent()
                    drawImage(
                        mask, dstSize = IntSize(size.width.roundToInt(), maskHeight),
                        blendMode = BlendMode.DstIn, filterQuality = FilterQuality.Low,
                    )
                } finally {
                    canvas.restore()
                }
            }
        }
    }) {
        Box(
            Modifier.fillMaxSize().offset {
                IntOffset(0, -RowAboveTop.roundToPx() - scroll().roundToInt())
            },
            content = content,
        )
    }
}
