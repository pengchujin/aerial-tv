package app.orchard.tv.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Masks only the narrow top edge, leaving the wallpaper and the status siblings untouched. */
internal class HomeTileFade {
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var alphaRamp: Bitmap? = null
    private val maskBounds = RectF()

    fun draw(canvas: Canvas, width: Float, height: Float, top: Float, fade: Float, content: () -> Boolean): Boolean {
        val bottom = minOf(top + fade, height)
        if (bottom <= top || width <= 0f) return false
        val rampHeight = ceil((bottom - top).toDouble()).toInt().coerceAtLeast(1)
        if (alphaRamp?.height != rampHeight) {
            // Bake alpha once per size, rather than evaluating a gradient shader each frame.
            // Do not recycle the old bitmap: an earlier display list may still reference it.
            alphaRamp = Bitmap.createBitmap(1, rampHeight, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until rampHeight) {
                    val alpha = (255f * (y + 0.5f) / rampHeight).roundToInt()
                    setPixel(0, y, Color.argb(alpha, 0, 0, 0))
                }
            }
        }
        maskBounds.set(0f, top, width, bottom)
        // The two clips are disjoint: each pixel of content is drawn once. Only the edge has
        // an offscreen buffer, not the whole screen, which matters on Android 4.x TV GPUs.
        val bodySave = canvas.save()
        val body = try {
            canvas.clipRect(0f, bottom, width, height)
            content()
        } finally {
            canvas.restoreToCount(bodySave)
        }
        val edgeSave = canvas.save()
        val edge = try {
            canvas.clipRect(0f, top, width, bottom)
            @Suppress("DEPRECATION") // The flags overload is available on Lite's Android 4.0 minimum.
            val layer = canvas.saveLayer(0f, top, width, bottom, null, Canvas.ALL_SAVE_FLAG)
            try {
                val more = content()
                canvas.drawBitmap(alphaRamp!!, null, maskBounds, mask)
                more
            } finally {
                canvas.restoreToCount(layer)
            }
        } finally {
            canvas.restoreToCount(edgeSave)
        }
        // Never short-circuit the second draw when a child animation requests another frame.
        return body || edge
    }
}
