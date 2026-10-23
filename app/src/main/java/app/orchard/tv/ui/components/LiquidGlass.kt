package app.orchard.tv.ui.components

import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint as ComposePaint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.wallpaper.Wallpapers
import kotlin.math.roundToInt

/**
 * True where a panel sits over another app's window (AppInfoActivity), not over Aerial's wallpaper:
 * it is then plain translucent dark glass, so that app shows through. There is no live blur here,
 * and Aerial's frosted wallpaper would be the wrong picture behind it.
 */
val LocalGlassOverApp = compositionLocalOf { false }

/** Dark glass for [LocalGlassOverApp]: dense enough for white text, thin enough to see the app. */
private val OverAppGlass = Palette.Glass.copy(alpha = 0.86f)

/**
 * Liquid Glass, the material of tvOS 26's panels, without a live blur: the TVs here mostly run
 * Android 9–11 (no RenderEffect) and their GPUs could not afford one. The surface shows the part
 * of the frosted wallpaper (Wallpapers.frosted, baked small) that lies behind it, dimmed so white
 * text reads on any picture, and light catches its rim. The frosted picture is stretched over the
 * screen through a shader, so a panel costs one fill of its own area, as a flat color did. In
 * "Follow App" mode there is no frosted wallpaper and the panel is the plain dark glass color.
 *
 * Replaces clip + background + border; [bottomCorners] false for a sheet rising from the bottom.
 * The continuous outline is a baked mask (see SmoothCorners.kt); children are clipped to a plain
 * rounded rect, which holds it.
 */
fun Modifier.liquidGlass(corner: Dp, bottomCorners: Boolean = true): Modifier = composed {
    val overApp = LocalGlassOverApp.current
    val frost = Wallpapers.frosted.takeIf { Wallpapers.backdrop != null && !overApp }
    var at by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(IntSize.Zero) }
    val shape = if (bottomCorners) RoundedCornerShape(corner) else RoundedCornerShape(topStart = corner, topEnd = corner)
    this
        .onGloballyPositioned { c ->
            at = c.positionInRoot()
            var r = c
            while (true) r = r.parentLayoutCoordinates ?: break
            root = r.size
        }
        .clip(shape)
        .drawWithCache {
            val shader = frost?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val radius = corner.toPx()
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            val mask = smoothMask(w, h, radius, if (bottomCorners) radius else 0f)
            val brush = shader?.let { ShaderBrush(it) }
            val flat = mask.asImageBitmap()
            onDrawBehind {
                if (shader == null || brush == null || root == IntSize.Zero) {
                    drawImage(flat, dstSize = IntSize(w, h), colorFilter = ColorFilter.tint(if (overApp) OverAppGlass else Palette.Glass))
                } else {
                    shader.setLocalMatrix(Matrix().apply {
                        setScale(root.width.toFloat() / frost.width, root.height.toFloat() / frost.height)
                        postTranslate(-at.x, -at.y)
                    })
                    // The outline is drawn first in a layer of the panel's size and the glass kept
                    // where it lies. One shader per draw: a ComposeShader of the glass and the mask
                    // (1.27-1.30) came out an opaque dark slab on Xiaomi MiTV-ASTP0 (Android 9,
                    // Mali-G31), and before Android 9 hardware drawing cannot compose two bitmaps.
                    drawContext.canvas.saveLayer(Rect(Offset.Zero, size), ComposePaint())
                    drawImage(flat, dstSize = IntSize(w, h))
                    drawRect(brush, colorFilter = PanelTint, blendMode = BlendMode.SrcIn)
                    drawContext.canvas.restore()
                }
                drawGlassEdge(size, radius, bottomCorners)
            }
        }
}

/**
 * The lit edge of a piece of glass: a sheen inside the top, and a rim bright where light falls on
 * it from the top left, faint along the sides, and catching again at the far corner.
 */
private fun DrawScope.drawGlassEdge(size: Size, radius: Float, bottomCorners: Boolean) {
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    if (w <= 0 || h <= 0) return
    drawImage(glassEdge(w, h, radius, 1.2.dp.toPx(), bottomCorners), dstSize = IntSize(w, h))
}

/** The lit edge of a [w]×[h] piece of glass at full strength, white with alpha; made once per size. */
fun glassEdge(w: Int, h: Int, radius: Float, rim: Float, bottomCorners: Boolean = true): ImageBitmap =
    baked(listOf("glass-edge", w, h, radius, rim, bottomCorners)) {
        val bitmap = createBitmap(w, h)
        val canvas = android.graphics.Canvas(bitmap)
        // A sheet's rim runs off the bottom with its corners.
        val bottom = if (bottomCorners) h.toFloat() else h + radius
        fun white(alpha: Float) = Color.White.copy(alpha = alpha).toArgb()
        // The sheen fades out by the middle.
        canvas.drawPath(
            smoothRectPath(Rect(0f, 0f, w.toFloat(), bottom), radius).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, bottom, intArrayOf(white(0.10f), 0), floatArrayOf(0f, 0.5f), Shader.TileMode.CLAMP)
            },
        )
        canvas.drawPath(
            smoothRectPath(Rect(rim / 2, rim / 2, w - rim / 2, bottom - rim / 2), radius - rim / 2).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = rim
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), bottom,
                    intArrayOf(white(0.55f), white(0.10f), white(0.06f), white(0.30f)),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        bitmap.frozen().asImageBitmap()
    }

/** Panels: the frosted picture at half its light, lifted a little, so white text reads on snow. */
private val PanelTint = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.48f, 0f, 0f, 0f, 14f,
    0f, 0.48f, 0f, 0f, 14f,
    0f, 0f, 0.48f, 0f, 14f,
    0f, 0f, 0f, 1f, 0f,
)))
