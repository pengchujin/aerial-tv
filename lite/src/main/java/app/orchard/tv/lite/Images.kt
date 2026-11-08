package app.orchard.tv.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.ExifInterface
import kotlin.math.roundToInt
import kotlin.random.Random

/** Bitmap helpers, from the full app's wallpaper/Images.kt. All of it runs off the main thread. */
object Images {
    /** Decodes [file] no larger than needed for [w]×[h], then centre-crops it to exactly that size (the full app's decodeCover). */
    fun decodeCover(file: java.io.File, w: Int, h: Int, whole: Boolean = false): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = exifOrientation(file)
        val turned = quarterTurn(orientation)
        val bw = if (turned) bounds.outHeight else bounds.outWidth
        val bh = if (turned) bounds.outWidth else bounds.outHeight
        var sample = 1
        while (bw / (sample * 2) >= w && bh / (sample * 2) >= h) sample *= 2
        val decoded = android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val src = upright(decoded, orientation)
        // The photo screensaver shows a portrait photo whole (the full app's decodeWhole, #647).
        if (whole && src.height > src.width) return fitWhole(src, w, h).also { src.recycle() }
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val cw = (w / scale).roundToInt().coerceAtMost(src.width)
        val ch = (h / scale).roundToInt().coerceAtMost(src.height)
        val l = (src.width - cw) / 2
        val t = (src.height - ch) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, android.graphics.Rect(l, t, l + cw, t + ch), android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    /** [src] whole and centred, 1.12 times inside the frame (the Ken Burns push), over itself frosted and darkened. */
    private fun fitWhole(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        // Squeezed to 320×180 by frost: only ever seen blurred.
        val frosted = frost(src)
        canvas.drawBitmap(frosted, null, android.graphics.Rect(0, 0, w, h), filter)
        frosted.recycle()
        canvas.drawColor(0x66000000)
        val scale = minOf(w / src.width.toFloat(), h / src.height.toFloat()) / 1.12f
        val fw = (src.width * scale).roundToInt()
        val fh = (src.height * scale).roundToInt()
        val left = (w - fw) / 2
        val top = (h - fh) / 2
        canvas.drawBitmap(src, null, android.graphics.Rect(left, top, left + fw, top + fh), filter)
        return out
    }

    /** How the camera stored [file]'s picture (EXIF Orientation); the full app's, issue #647. */
    fun exifOrientation(file: java.io.File): Int = try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Whether [orientation] turns the picture a quarter, so its width and height swap. */
    fun quarterTurn(orientation: Int): Boolean = when (orientation) {
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE, ExifInterface.ORIENTATION_ROTATE_270 -> true
        else -> false
    }

    /** [src] turned and mirrored upright for [orientation]; [src] itself when it already is, else recycled. */
    fun upright(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }

    /** The home shading, the same as the full app's, baked in so the TV draws one full-screen layer. */
    private val SHADE = floatArrayOf(0.12f, 0.04f, 0.22f, 0.5f)
    private val SHADE_AT = floatArrayOf(0f, 0.3f, 0.66f, 1f)
    private const val SHADE_LEFT = 0.2f

    fun shadeForHome(b: Bitmap) {
        val c = Canvas(b)
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, IntArray(SHADE.size) { black(SHADE[it]) }, SHADE_AT, Shader.TileMode.CLAMP)
        })
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.5f, 0f, black(SHADE_LEFT), 0, Shader.TileMode.CLAMP)
        })
    }

    /**
     * A picture frosted: shrunk to 320×180 and blurred three times over, as the full app's
     * Images.frost. Small on purpose: it is only ever seen blurred, stretched in a single pass.
     */
    fun frost(b: Bitmap): Bitmap {
        val w = 320
        val h = 180
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 7) }
        val out = small.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== small) small.recycle() }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** [src] under [alpha] black, as a new bitmap: the grid's background. */
    /** [src] through [tint], baked. */
    fun tinted(src: Bitmap, tint: android.graphics.ColorFilter): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, android.graphics.Paint().apply { colorFilter = tint })
        return out
    }

    fun darkened(src: Bitmap, alpha: Float): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawColor(black(alpha))
        return out
    }

    /** Fine noise, so long gradients do not band on 8-bit panels. */
    fun dither(b: Bitmap) {
        val w = b.width
        val h = b.height
        val row = IntArray(w)
        val rnd = Random(7)
        for (y in 0 until h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val n = rnd.nextInt(5) - 2
                val r = clamp(((c shr 16) and 0xFF) + n)
                val g = clamp(((c shr 8) and 0xFF) + n)
                val bl = clamp((c and 0xFF) + n)
                row[x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
            }
            b.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = if (horizontal) src[l * w + j] else src[j * w + l]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val idx = if (horizontal) l * w + i else i * w + l
                dst[idx] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private fun black(alpha: Float) = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
}
