package app.orchard.tv.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTileFadeTest {
    private val background = Color.rgb(32, 64, 96)
    private val white = Paint().apply { color = Color.WHITE }

    @Test fun alphaRampRevealsWallpaperWithoutMaskingSiblings() {
        val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        val saves = canvas.saveCount
        var calls = 0
        val more = HomeTileFade().draw(canvas, 960f, 540f, 72f, 24f) {
            calls++
            canvas.drawRect(0f, 0f, 960f, 540f, white)
            calls == 1
        }
        assertTrue(more)
        assertEquals("Both disjoint regions must draw even when the first asks for another frame", 2, calls)
        assertEquals(saves, canvas.saveCount)
        for (y in 0 until 72) assertEquals(background, bitmap.getPixel(100, y))
        var previous = Color.red(background)
        for (y in 72 until 96) {
            val red = Color.red(bitmap.getPixel(100, y))
            assertTrue("Ramp should increase at $y", red > previous)
            assertBlend(bitmap.getPixel(100, y), Color.WHITE, (y - 72 + 0.5f) / 24f)
            previous = red
        }
        for (y in 96 until 540) assertEquals(Color.WHITE, bitmap.getPixel(100, y))
        // The renderer must restore its clip and blend state for the status/clock siblings.
        canvas.drawRect(20f, 28f, 30f, 38f, Paint().apply { color = Color.RED })
        assertEquals(Color.RED, bitmap.getPixel(25, 33))
        bitmap.recycle()
    }

    @Test fun childAnimationFlagsFromEitherPassArePreserved() {
        val bitmap = Bitmap.createBitmap(100, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fade = HomeTileFade()
        for (body in listOf(false, true)) for (edge in listOf(false, true)) {
            var calls = 0
            val more = fade.draw(canvas, 100f, 120f, 72f, 24f) {
                val value = if (calls == 0) body else edge
                calls++
                value
            }
            assertEquals(2, calls)
            assertEquals(body || edge, more)
        }
        bitmap.recycle()
    }

    @Test fun scrollingAndFocusZoomKeepTheFadeFixedThroughReversals() {
        val fade = HomeTileFade()
        for (columns in 4..6) for (zoom in listOf(1f, 1.15f, 1.25f)) {
            for (scroll in listOf(0f, 68.4f, 149.6f, 307.5f, 850.2f, 307.5f, 0f, 149.6f, 0f)) {
                val actual = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
                val reference = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(actual)
                canvas.drawColor(background)
                val referenceCanvas = Canvas(reference)
                referenceCanvas.drawColor(background)
                drawRows(referenceCanvas, columns, zoom, scroll)
                assertEquals(false, fade.draw(canvas, 960f, 540f, 72f, 24f) {
                    drawRows(canvas, columns, zoom, scroll)
                    false
                })
                for (x in listOf(60, 100, 500, 880)) {
                    for (y in 0 until 72) assertEquals(background, actual.getPixel(x, y))
                    for (y in 72 until 96) {
                        assertBlend(actual.getPixel(x, y), reference.getPixel(x, y), (y - 72 + 0.5f) / 24f)
                    }
                    for (y in 96 until 540) assertEquals(reference.getPixel(x, y), actual.getPixel(x, y))
                }
                if (columns == 5 && zoom == 1.15f && scroll == 149.6f) {
                    val file = File("build/reports/viewport/lite-five-columns.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                actual.recycle()
                reference.recycle()
            }
        }
    }

    private fun drawRows(canvas: Canvas, columns: Int, zoom: Float, scroll: Float) {
        val gap = when (columns) { 4 -> 34f; 6 -> 24f; else -> 28f }
        val width = (960f - 112f - gap * (columns - 1)) / columns
        val height = width * 9f / 16f
        val saved = canvas.save()
        canvas.translate(0f, -scroll.roundToInt().toFloat())
        repeat(12) { row ->
            repeat(columns) { column ->
                val x = 56f + (width + gap) * column
                val y = 72f + (height + 50f) * row
                val tile = canvas.save()
                canvas.scale(zoom, zoom, x + width / 2, y + height / 2)
                canvas.drawRect(x, y, x + width, y + height, white)
                canvas.restoreToCount(tile)
            }
        }
        canvas.restoreToCount(saved)
    }

    private fun assertBlend(actual: Int, unmasked: Int, alpha: Float) {
        for (shift in listOf(16, 8, 0)) {
            val behind = background shr shift and 255
            val front = unmasked shr shift and 255
            val expected = (behind + (front - behind) * alpha).roundToInt()
            assertTrue("Expected channel $expected, got ${actual shr shift and 255}",
                abs((actual shr shift and 255) - expected) <= 3)
        }
        assertEquals(255, Color.alpha(actual))
    }
}
