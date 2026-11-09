package app.orchard.tv.wallpaper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.media.ExifInterface
import androidx.core.graphics.createBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** Bitmap helpers shared by the wallpaper code. All of it runs off the main thread. */
object Images {

    /** Decodes [file] no larger than needed for [w]×[h], upright, then centre-crops it to exactly that size. */
    fun decodeCover(file: File, w: Int, h: Int): Bitmap? = decodeUpright(file, w, h)?.let { src ->
        cover(src, w, h).also { if (it !== src) src.recycle() }
    }

    /**
     * For the photo screensaver: a picture turned the way the frame is as [decodeCover]; one turned
     * the other way whole, on a blurred and darkened copy of itself, instead of cut to its middle
     * third (issue #647; a landscape one on a screen turned to portrait, #1416). It fits
     * [SAVER_ZOOM] times inside the frame, so the slow push in never cuts it either.
     */
    fun decodeWhole(file: File, w: Int, h: Int): Bitmap? {
        val src = decodeUpright(file, w, h) ?: return null
        if ((src.height <= src.width) == (h <= w)) return cover(src, w, h).also { if (it !== src) src.recycle() }
        return fitWhole(src, w, h).also { src.recycle() }
    }

    /** How far the photo screensaver pushes in at most (Screensaver's Ken Burns: 1.04 to 1.12). */
    const val SAVER_ZOOM = 1.12f

    /** [src] whole and centred on [w]×[h], over itself frosted and darkened to fill the sides. */
    fun fitWhole(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        val back = cover(src, w, h)
        val frosted = frost(back)
        if (back !== src) back.recycle()
        canvas.drawBitmap(frosted, null, Rect(0, 0, w, h), filter)
        frosted.recycle()
        canvas.drawColor(0x66000000)
        val scale = min(w / src.width.toFloat(), h / src.height.toFloat()) / SAVER_ZOOM
        val fw = (src.width * scale).roundToInt()
        val fh = (src.height * scale).roundToInt()
        val left = (w - fw) / 2
        val top = (h - fh) / 2
        canvas.drawBitmap(src, null, Rect(left, top, left + fw, top + fh), filter)
        return out
    }

    /** [file] decoded no larger than needed for [w]×[h], and turned upright. */
    private fun decodeUpright(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val orientation = exifOrientation(file)
        val turned = quarterTurn(orientation)
        val bw = if (turned) bounds.outHeight else bounds.outWidth
        val bh = if (turned) bounds.outWidth else bounds.outHeight
        var sample = 1
        while (bw / (sample * 2) >= w && bh / (sample * 2) >= h) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return upright(decoded, orientation)
    }

    /**
     * How the camera stored [file]'s picture (EXIF Orientation). Phones keep the sensor's pixels as
     * they were and write the turn into the file, so a photo taken upright decoded on its side or
     * upside down (issue #647). Normal for anything without the tag (PNG, Bing's pictures).
     */
    fun exifOrientation(file: File): Int = runCatching {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

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

    fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val cw = (w / scale).roundToInt().coerceAtMost(src.width)
        val ch = (h / scale).roundToInt().coerceAtMost(src.height)
        val l = (src.width - cw) / 2
        val t = (src.height - ch) / 2
        val out = createBitmap(w, h)
        Canvas(out).drawBitmap(src, Rect(l, t, l + cw, t + ch), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /**
     * Shading baked into a home backdrop so the clock and the top row read over any picture, the way
     * tvOS darkens the area under its app rows. Baked once, so the TV draws a single full-screen layer.
     * Light since 1.28: the grid has its own darkened frosted background and the top row its plate,
     * and the old shade (88% black at the bottom, 42% more on the left) made photos look dim (#157).
     */
    /** The home shading, top to bottom: black at these alphas at [SHADE_AT]; and [SHADE_LEFT] fading out by mid-screen. */
    val SHADE = floatArrayOf(0.12f, 0.04f, 0.22f, 0.5f)
    val SHADE_AT = floatArrayOf(0f, 0.3f, 0.66f, 1f)
    const val SHADE_LEFT = 0.2f

    fun shadeForHome(b: Bitmap): Bitmap {
        val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                SHADE.map(::black).toIntArray(),
                SHADE_AT, Shader.TileMode.CLAMP,
            )
        })
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = LinearGradient(0f, 0f, w * 0.5f, 0f, black(SHADE_LEFT), 0, Shader.TileMode.CLAMP)
        })
        return out
    }

    /**
     * [shadeForHome] on its own, small, for a moving film: stretched over the screen in one draw.
     * Drawn as its two gradients every frame instead, it cost the GPU about 22 ms a frame on Xiaomi
     * MiTV-MFTR0, where the film keeps Home drawing: moving along the top row took 81 ms a frame
     * (median; GPU 30 ms), 31 ms (GPU 13 ms) with this picture.
     */
    val homeShade: Bitmap by lazy { shadeForHome(Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888)) }

    /**
     * A home backdrop frosted: shrunk and blurred three times over. Small
     * on purpose; it is only ever seen blurred, and it is drawn stretched in a single pass.
     */
    fun frost(b: Bitmap): Bitmap {
        // Turned with [b], so the blur stays round on a portrait screen (#1416).
        val upright = b.height > b.width
        val w = if (upright) 180 else 320
        val h = if (upright) 320 else 180
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 7) }
        val out = small.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== small) small.recycle() }
        out.setPixels(px, 0, w, 0, 0, w, h)
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
                val n = rnd.nextInt(-2, 3)
                val r = ((c shr 16) and 0xFF) + n
                val g = ((c shr 8) and 0xFF) + n
                val bl = (c and 0xFF) + n
                row[x] = (c and 0xFF000000.toInt()) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
            }
            b.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

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
