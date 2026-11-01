package app.orchard.tv.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Apple's continuous corner: the curvature eases in along the edge instead of jumping from a
 * straight line onto a circle, so there is no visible "kink" where a plain rounded corner starts.
 * Built as Figma's corner smoothing does it (a short circular arc between two cubic transitions);
 * 0.6 is what Figma calls iOS. A rounded rect of the same radius with [smoothing] 0.
 *
 * The GPU never sees these paths. Filling, stroking or clipping to one costs the Xiaomi MFTR0
 * (Mali, Android 14) a mask or a stencil pass every frame, and since every frame replays the
 * whole screen that took tiles from 11ms to 57ms of GPU time. So shapes are drawn once per size
 * on the CPU into bitmaps ([baked]) and the GPU only draws rects and images, as cheap as before.
 * The outline lies wholly inside a plain rounded rect of the same radius, so a layer can still
 * clip to that one, which the hardware does for free.
 */
const val AppleSmoothing = 0.6f

fun smoothRectPath(rect: Rect, radius: Float, bottomRadius: Float = radius, smoothing: Float = AppleSmoothing): Path =
    Path().apply { addSmoothRect(rect, radius, bottomRadius, smoothing) }

/** White inside a [w]×[h] rect with continuous corners, clear outside; made once per size. */
fun smoothMask(w: Int, h: Int, radius: Float, bottomRadius: Float = radius): Bitmap =
    baked(listOf("mask", w, h, radius, bottomRadius)) {
        createBitmap(w, h).also {
            val path = smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius, bottomRadius).asAndroidPath()
            Canvas(it).drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        }.frozen()
    }

/** White in a band [width] wide just inside a [w]×[h] continuous outline, clear elsewhere; made once per size. */
fun smoothRing(w: Int, h: Int, radius: Float, width: Float): Bitmap =
    baked(listOf("ring-in", w, h, radius, width)) {
        createBitmap(w, h).also {
            val canvas = Canvas(it)
            canvas.drawPath(
                smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius).asAndroidPath(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE },
            )
            canvas.drawPath(
                smoothRectPath(Rect(width, width, w - width, h - width), (radius - width).coerceAtLeast(0f)).asAndroidPath(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) },
            )
        }.frozen()
    }

/**
 * [src] stretched over [w]×[h] and cut to continuous corners, for a picture that would otherwise
 * be clipped every frame. Not kept here: the caller holds it. Off the main thread where it can be.
 */
fun smoothCut(src: Bitmap, w: Int, h: Int, radius: Float): Bitmap =
    createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1)).also {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(Matrix().apply { setScale(w.toFloat() / src.width, h.toFloat() / src.height) }) }
        }
        Canvas(it).drawPath(smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius).asAndroidPath(), fill)
    }.frozen()

/**
 * clip + background with continuous corners: the baked mask in [color], one image draw, nothing
 * clipped. Children are not clipped, so it suits a plate of color with padded content on it.
 */
fun Modifier.smoothBackground(color: Color, radius: Dp): Modifier = drawWithCache {
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    val mask = if (w > 0 && h > 0 && color.alpha > 0f) smoothMask(w, h, radius.toPx()).asImageBitmap() else null
    val tint = ColorFilter.tint(color)
    onDrawBehind { if (mask != null) drawImage(mask, dstSize = IntSize(w, h), colorFilter = tint) }
}

/** border with continuous corners: a baked band [width] wide inside the edge, over the content. */
fun Modifier.smoothBorder(width: Dp, color: Color, radius: Dp): Modifier = drawWithCache {
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    val ring = if (w > 0 && h > 0 && color.alpha > 0f) smoothRing(w, h, radius.toPx(), width.toPx()).asImageBitmap() else null
    val tint = ColorFilter.tint(color)
    onDrawWithContent {
        drawContent()
        if (ring != null) drawImage(ring, dstSize = IntSize(w, h), colorFilter = tint)
    }
}

/**
 * An immutable copy. The renderer uploads an immutable bitmap once; a mutable one used in a shader
 * is copied and uploaded again whenever the drawing that uses it is recorded, which for the plate
 * under the top row is every frame of a scroll (Xiaomi MFTR0: 4ms -> 13ms to issue a frame).
 */
fun Bitmap.frozen(): Bitmap = copy(Bitmap.Config.ARGB_8888, false).also { recycle() }

private val bakes = object : LinkedHashMap<List<Any>, Any>(16, 0.75f, true) {
    // Sizes come and go with layout (a panel opens, the screen rotates); a few dozen is plenty.
    // 64: panels' rows, keys, cards and their borders are baked too, each one small.
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any>, Any>?) = size > 64
}

/** A drawing made once per [key] (its kind and sizes) and kept while it is in use. */
@Suppress("UNCHECKED_CAST")
fun <T : Any> baked(key: List<Any>, make: () -> T): T = synchronized(bakes) { bakes.getOrPut(key, make) as T }

/** Adds [rect] with continuous corners, clockwise from the top left, as a closed contour. */
fun Path.addSmoothRect(rect: Rect, radius: Float, bottomRadius: Float = radius, smoothing: Float = AppleSmoothing) {
    val budget = min(rect.width, rect.height) / 2f
    val top = SmoothCorner(radius, smoothing, budget)
    val bot = SmoothCorner(bottomRadius, smoothing, budget)
    // Each corner in a local frame: the incoming edge runs along +u into the vertex, the outgoing
    // one leaves along +v. Clockwise: top right, bottom right, bottom left, top left.
    moveTo(rect.left + top.p, rect.top)
    top.addTo(this, Offset(rect.right, rect.top), Offset(1f, 0f), Offset(0f, 1f))
    bot.addTo(this, Offset(rect.right, rect.bottom), Offset(0f, 1f), Offset(-1f, 0f))
    bot.addTo(this, Offset(rect.left, rect.bottom), Offset(-1f, 0f), Offset(0f, -1f))
    top.addTo(this, Offset(rect.left, rect.top), Offset(0f, -1f), Offset(1f, 0f))
    close()
}

private class SmoothCorner(radius: Float, smoothing: Float, budget: Float) {
    val p: Float
    private val r: Float
    private val a: Float
    private val b: Float
    private val c: Float
    private val d: Float
    private val arc: Float // the arc's sweep, radians

    init {
        var s = smoothing
        // Not enough room for the full transition: give up smoothing before giving up radius.
        if (radius > 0f && (1f + s) * radius > budget) s = (budget / radius - 1f).coerceIn(0f, s)
        r = min(radius, budget).coerceAtLeast(0f)
        p = min((1f + s) * r, budget)
        arc = (PI / 2 * (1f - s)).toFloat()
        val arcLen = sin(arc / 2) * r * sqrt(2f)
        val alpha = (PI.toFloat() / 2 - arc) / 2
        val p3p4 = r * tan(alpha / 2)
        val beta = PI.toFloat() / 4 * s
        c = p3p4 * cos(beta)
        d = c * tan(beta)
        b = (p - arcLen - c - d) / 3f
        a = 2f * b
    }

    fun addTo(path: Path, vertex: Offset, u: Offset, v: Offset) {
        fun at(x: Float, y: Float) = vertex + u * x + v * y
        if (r <= 0f) {
            path.lineTo(vertex.x, vertex.y)
            return
        }
        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val p1 = at(x1, y1); val p2 = at(x2, y2); val p3 = at(x3, y3)
            path.cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
        }
        val s0 = at(-p, 0f)
        path.lineTo(s0.x, s0.y)
        cubic(-p + a, 0f, -p + a + b, 0f, -p + a + b + c, d)
        // The circular part, centred at (-r, r), symmetric about the diagonal; one cubic is exact
        // enough for under a quarter turn.
        val f0 = -PI.toFloat() / 4 - arc / 2
        val f1 = -PI.toFloat() / 4 + arc / 2
        val k = 4f / 3f * tan(arc / 4) * r
        val e0x = -r + r * cos(f0); val e0y = r + r * sin(f0)
        val e1x = -r + r * cos(f1); val e1y = r + r * sin(f1)
        cubic(e0x - k * sin(f0), e0y + k * cos(f0), e1x + k * sin(f1), e1y - k * cos(f1), e1x, e1y)
        cubic(0f, p - a - b, 0f, p - a, 0f, p)
    }
}
