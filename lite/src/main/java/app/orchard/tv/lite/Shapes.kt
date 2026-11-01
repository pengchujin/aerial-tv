package app.orchard.tv.lite

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The full app's shapes (ui/components/SmoothCorners.kt, ui/home/AppTile.kt, LiquidGlass.kt):
 * Apple's continuous corner, and everything drawn with it baked once into bitmaps, so the GPU
 * only ever draws rects and images.
 */
object Shapes {
    /** Figma's corner smoothing for iOS. */
    private const val SMOOTHING = 0.6f

    fun smoothRect(rect: RectF, radius: Float, bottomRadius: Float = radius): Path = Path().apply {
        val budget = min(rect.width(), rect.height()) / 2f
        val top = Corner(radius, SMOOTHING, budget)
        val bot = Corner(bottomRadius, SMOOTHING, budget)
        moveTo(rect.left + top.p, rect.top)
        top.addTo(this, rect.right, rect.top, 1f, 0f, 0f, 1f)
        bot.addTo(this, rect.right, rect.bottom, 0f, 1f, -1f, 0f)
        bot.addTo(this, rect.left, rect.bottom, -1f, 0f, 0f, -1f)
        top.addTo(this, rect.left, rect.top, 0f, -1f, 1f, 0f)
        close()
    }

    private class Corner(radius: Float, smoothing: Float, budget: Float) {
        val p: Float
        private val r: Float
        private val a: Float
        private val b: Float
        private val c: Float
        private val d: Float
        private val arc: Float

        init {
            var s = smoothing
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

        /** The corner at ([vx], [vy]); the incoming edge runs along ([ux], [uy]), the outgoing along ([wx], [wy]). */
        fun addTo(path: Path, vx: Float, vy: Float, ux: Float, uy: Float, wx: Float, wy: Float) {
            fun x(i: Float, j: Float) = vx + ux * i + wx * j
            fun y(i: Float, j: Float) = vy + uy * i + wy * j
            if (r <= 0f) { path.lineTo(vx, vy); return }
            path.lineTo(x(-p, 0f), y(-p, 0f))
            path.cubicTo(x(-p + a, 0f), y(-p + a, 0f), x(-p + a + b, 0f), y(-p + a + b, 0f), x(-p + a + b + c, d), y(-p + a + b + c, d))
            val f0 = -PI.toFloat() / 4 - arc / 2
            val f1 = -PI.toFloat() / 4 + arc / 2
            val k = 4f / 3f * tan(arc / 4) * r
            val e0x = -r + r * cos(f0); val e0y = r + r * sin(f0)
            val e1x = -r + r * cos(f1); val e1y = r + r * sin(f1)
            path.cubicTo(
                x(e0x - k * sin(f0), e0y + k * cos(f0)), y(e0x - k * sin(f0), e0y + k * cos(f0)),
                x(e1x + k * sin(f1), e1y - k * cos(f1)), y(e1x + k * sin(f1), e1y - k * cos(f1)),
                x(e1x, e1y), y(e1x, e1y),
            )
            path.cubicTo(x(0f, p - a - b), y(0f, p - a - b), x(0f, p - a), y(0f, p - a), x(0f, p), y(0f, p))
        }
    }

    private val bakes = object : LinkedHashMap<List<Any>, Any>(16, 0.75f, true) {
        // 64: panels' rows, keys and borders are baked too, each one small.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<List<Any>, Any>?) = size > 64
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> baked(key: List<Any>, make: () -> T): T = synchronized(bakes) { bakes[key] as T? ?: make().also { bakes[key] = it } }

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)

    /** White inside a [w]×[h] continuous outline, clear outside. */
    fun mask(w: Int, h: Int, radius: Float, bottomRadius: Float = radius): Bitmap = baked(listOf("mask", w, h, radius, bottomRadius)) {
        bitmap(w, h).also {
            Canvas(it).drawPath(smoothRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, bottomRadius), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        }
    }

    /** The tile's rim: a hairline just inside its outline, white at [alpha]. */
    private fun rim(w: Int, h: Int, radius: Float, width: Float, alpha: Float): Bitmap = baked(listOf("rim", w, h, radius, width, alpha)) {
        bitmap(w, h).also {
            val c = Canvas(it)
            c.drawPath(smoothRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb((alpha * 255).toInt(), 255, 255, 255) })
            c.drawPath(smoothRect(RectF(width, width, w - width, h - width), radius - width), Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        }
    }

    /** White in a band [width] wide just inside a [w]×[h] continuous outline: a border. */
    fun band(w: Int, h: Int, radius: Float, width: Float): Bitmap = rim(w, h, radius, width, 1f)

    /**
     * Draws a baked white shape ([mask], [band]) in one color, as the full app's smoothBackground and
     * smoothBorder: a plate or a border with continuous corners is one bitmap draw.
     */
    class Tint {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var color = 0

        fun draw(canvas: Canvas, shape: Bitmap, x: Float, y: Float, color: Int) {
            if (paint.colorFilter == null || color != this.color) {
                paint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                this.color = color
            }
            canvas.drawBitmap(shape, x, y, paint)
        }
    }

    /** The full app's resting rim strength (AppTile's RIM_AT_REST). */
    private const val RIM_AT_REST = 0.08f

    /** [art] cut to a [w]×[h] continuous outline with its rim: the whole tile as Home draws it. */
    fun face(art: Bitmap, w: Int, h: Int, radius: Float, rimWidth: Float): Bitmap {
        val out = bitmap(w, h)
        val c = Canvas(out)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(w.toFloat() / art.width, h.toFloat() / art.height) })
            }
        }
        c.drawPath(smoothRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius), fill)
        c.drawBitmap(rim(w, h, radius, rimWidth, RIM_AT_REST), 0f, 0f, null)
        return out
    }

    /** A soft black shadow under a [w]×[h] outline; [ext] px of it lie outside the tile on each side. */
    class Halo(val image: Bitmap, val ext: Float)

    fun shadow(w: Int, h: Int, corner: Float, blur: Float): Halo = baked(listOf("shadow", w, h, corner, blur)) {
        val ext = ceil(blur * 1.5f)
        val b = bitmap(w + 2 * ext.toInt(), h + 2 * ext.toInt())
        Canvas(b).apply {
            translate(ext, ext)
            drawPath(smoothRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xB3000000.toInt()
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            })
        }
        Halo(b, ext)
    }

    /** The high-contrast focus frame [gap] outside a [w]×[h] tile, lit from the top left. */
    fun ring(w: Int, h: Int, corner: Float, gap: Float, width: Float, dp: Float, color: Int = 0xFFFFFFFF.toInt()): Halo =
        baked(listOf("ring", w, h, corner, gap, width, dp, color)) {
            val out = gap + width / 2
            val halo = gap + width + dp
            val ext = ceil(halo + dp + 1f)
            val b = bitmap(w + 2 * ext.toInt(), h + 2 * ext.toInt())
            val c = Canvas(b).apply { translate(ext, ext) }
            fun around(d: Float) = smoothRect(RectF(-d, -d, w + d, h + d), corner + d)
            fun line(lineWidth: Float, argb: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = lineWidth; this.color = argb
            }
            c.drawPath(around(halo), line(2 * dp, 0x4D000000))
            val rgb = color and 0xFFFFFF
            c.drawPath(around(out), line(width, color).apply {
                shader = LinearGradient(-out, -out, w + out, h + out,
                    intArrayOf(color, (0xC7 shl 24) or rgb, (0xF0 shl 24) or rgb), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            })
            c.drawPath(around(out - width / 2 - 0.75f * dp), line(dp, 0x59FFFFFF))
            Halo(b, ext)
        }

    /** Liquid Glass's lit edge: a sheen fading by the middle and a rim brighter top left and bottom right. */
    fun glassEdge(w: Int, h: Int, radius: Float, rim: Float, bottomCorners: Boolean = true): Bitmap = baked(listOf("edge", w, h, radius, rim, bottomCorners)) {
        bitmap(w, h).also {
            val c = Canvas(it)
            val bottom = if (bottomCorners) h.toFloat() else h + radius
            fun white(a: Float) = android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255)
            c.drawPath(smoothRect(RectF(0f, 0f, w.toFloat(), bottom), radius), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, bottom, intArrayOf(white(0.10f), 0), floatArrayOf(0f, 0.5f), Shader.TileMode.CLAMP)
            })
            c.drawPath(smoothRect(RectF(rim / 2, rim / 2, w - rim / 2, bottom - rim / 2), radius - rim / 2), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = rim
                shader = LinearGradient(0f, 0f, w.toFloat(), bottom,
                    intArrayOf(white(0.55f), white(0.10f), white(0.06f), white(0.30f)), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
            })
        }
    }

    /**
     * A glass panel's picture: the part of [glass] (a frosted wallpaper stretched over [screenW]×
     * [screenH]) under [at], through [tint], cut to the outline, with its lit edge. Made once per
     * panel, so the panel is one image draw, as the full app's Liquid Glass.
     */
    fun glass(glass: Bitmap, screenW: Int, screenH: Int, at: RectF, radius: Float, rim: Float, tint: android.graphics.ColorFilter?, bottomCorners: Boolean = true): Bitmap {
        val w = at.width().toInt().coerceAtLeast(1)
        val h = at.height().toInt().coerceAtLeast(1)
        val out = bitmap(w, h)
        val c = Canvas(out)
        c.drawBitmap(glass, Matrix().apply {
            setScale(screenW.toFloat() / glass.width, screenH.toFloat() / glass.height)
            postTranslate(-at.left, -at.top)
        }, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = tint })
        c.drawBitmap(mask(w, h, radius, if (bottomCorners) radius else 0f), 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        c.drawBitmap(glassEdge(w, h, radius, rim, bottomCorners), 0f, 0f, null)
        return out
    }
}
