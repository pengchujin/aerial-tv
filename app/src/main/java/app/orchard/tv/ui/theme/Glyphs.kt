package app.orchard.tv.ui.theme

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

/**
 * Aerial's glyphs: filled Material Symbols Rounded (GlyphPaths), whose soft, even weight sits
 * closest to SF Symbols among icons an Android app may ship (SF Symbols are licensed for Apple
 * platforms only). Vector paths, rasterized at each UI size, in a square box.
 */
enum class Glyph { Folder, Pencil, Wifi, Ethernet, Offline, Bluetooth, Gear, Input, Speaker, SpeakerMuted, Switches, EyeSlash, Move, Info, Trash, ChevronRight, ChevronLeft, Check, Hdmi, Antenna, Av, Sun, Photo, Play, Bag, CloudSun, Calendar, Cast, Shield, Globe, Moon, Cloud, CloudRain, CloudSnow, CloudFog, CloudBolt, Download, Power, Usb, Home, Kids }

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, size: Dp, modifier: Modifier = Modifier, level: Float = 1f) {
    val data = glyphPath(glyph, level)
    Box(modifier.size(size).drawWithCache {
        val w = this.size.width.roundToInt().coerceAtLeast(1)
        val h = this.size.height.roundToInt().coerceAtLeast(1)
        val key = Triple(data, w, h)
        val image = glyphImages.get(key) ?: glyphImage(data, w, h).also { glyphImages.put(key, it) }
        onDrawBehind { drawImage(image, colorFilter = ColorFilter.tint(color)) }
    })
}

/** [level] picks the variant of Wi-Fi (signal) and Speaker (volume): 0 to 1. */
fun DrawScope.drawGlyph(glyph: Glyph, color: Color, level: Float = 1f) = drawGlyph(glyphPath(glyph, level), color)

private fun glyphPath(glyph: Glyph, level: Float): String? {
    return when (glyph) {
        Glyph.Wifi -> when {
            level < 0.34f -> GlyphPaths.WIFI_1
            level < 0.67f -> GlyphPaths.WIFI_2
            else -> GlyphPaths.WIFI
        }
        Glyph.Ethernet -> GlyphPaths.LAN
        Glyph.Offline -> GlyphPaths.WIFI_OFF
        Glyph.Bluetooth -> GlyphPaths.BLUETOOTH
        Glyph.Gear -> GlyphPaths.SETTINGS
        Glyph.Input -> GlyphPaths.INPUT
        Glyph.Speaker -> when {
            level <= 0.01f -> GlyphPaths.VOLUME_MUTE
            level < 0.5f -> GlyphPaths.VOLUME_DOWN
            else -> GlyphPaths.VOLUME_UP
        }
        Glyph.SpeakerMuted -> GlyphPaths.VOLUME_OFF
        Glyph.Switches -> null
        Glyph.EyeSlash -> GlyphPaths.VISIBILITY_OFF
        Glyph.Move -> GlyphPaths.OPEN_WITH
        Glyph.Folder -> GlyphPaths.FOLDER
        Glyph.Pencil -> GlyphPaths.EDIT
        Glyph.Info -> GlyphPaths.INFO
        Glyph.Trash -> GlyphPaths.DELETE
        Glyph.ChevronRight -> GlyphPaths.CHEVRON_RIGHT
        Glyph.ChevronLeft -> GlyphPaths.CHEVRON_LEFT
        Glyph.Check -> GlyphPaths.CHECK
        Glyph.Hdmi -> GlyphPaths.HDMI
        Glyph.Antenna -> GlyphPaths.ANTENNA
        Glyph.Av -> GlyphPaths.COMPONENT
        Glyph.Sun -> GlyphPaths.LIGHT_MODE
        Glyph.Photo -> GlyphPaths.IMAGE
        Glyph.Play -> GlyphPaths.PLAY_CIRCLE
        Glyph.Bag -> GlyphPaths.SHOPPING_BAG
        Glyph.CloudSun -> GlyphPaths.PARTLY_CLOUDY_DAY
        Glyph.Calendar -> GlyphPaths.CALENDAR_MONTH
        Glyph.Cast -> GlyphPaths.CAST
        Glyph.Shield -> GlyphPaths.VERIFIED_USER
        Glyph.Globe -> GlyphPaths.LANGUAGE
        Glyph.Moon -> GlyphPaths.BEDTIME
        Glyph.Cloud -> GlyphPaths.CLOUD
        Glyph.CloudRain -> GlyphPaths.RAINY
        Glyph.CloudSnow -> GlyphPaths.WEATHER_SNOWY
        Glyph.CloudFog -> GlyphPaths.FOGGY
        Glyph.CloudBolt -> GlyphPaths.THUNDERSTORM
        Glyph.Download -> GlyphPaths.DOWNLOAD
        Glyph.Power -> GlyphPaths.POWER
        Glyph.Usb -> GlyphPaths.USB
        Glyph.Home -> GlyphPaths.HOME
        Glyph.Kids -> GlyphPaths.KIDS
    }
}

private fun DrawScope.drawGlyph(data: String?, color: Color) {
    val s = size.minDimension
    if (data == null) {
        drawPath(switches.getOrPut(s) { switchesPath(s) }, color)
        return
    }
    val path = parsed.getOrPut(data) { PathParser().parsePathString(data).toPath() }
    // The symbols' box runs from y = -960 to 0.
    scale(s / 960f, pivot = Offset.Zero) {
        translate(0f, 960f) { drawPath(path, color) }
    }
}

/**
 * The KKTV U86V9 leaves hardware paths jagged. Rasterize UI glyphs once on the CPU at twice their
 * display size, then downsample. A white mask can be tinted without baking every focus color;
 * bitmap generation for app tiles still draws paths directly.
 */
private fun glyphImage(data: String?, w: Int, h: Int): ImageBitmap {
    val large = ImageBitmap(w * 2, h * 2)
    // Snap Switches' geometry at the final size, before scaling the canvas for antialiasing.
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(large), Size(w.toFloat(), h.toFloat())) {
        scale(2f, pivot = Offset.Zero) { drawGlyph(data, Color.White) }
    }
    val small = Bitmap.createScaledBitmap(large.asAndroidBitmap(), w, h, true)
    val immutable = small.copy(Bitmap.Config.ARGB_8888, false)
    small.recycle()
    large.asAndroidBitmap().recycle()
    return immutable.asImageBitmap()
}

private val glyphImages = object : LruCache<Triple<String?, Int, Int>, ImageBitmap>(2 * 1024 * 1024) {
    override fun sizeOf(key: Triple<String?, Int, Int>, value: ImageBitmap) = value.width * value.height * 4
}

// Tiles are drawn off the main thread while the UI draws glyphs on it.
private val parsed = java.util.concurrent.ConcurrentHashMap<String, Path>()
private val switches = java.util.concurrent.ConcurrentHashMap<Float, Path>()

/**
 * Two toggles, the Control Center glyph, as SF Symbols' switch.2 draws it (Material has no close
 * match): an outlined track with its knob solid on the left over a solid track with its knob cut
 * out on the right. At 17dp it is 34 px on a 1080p UI, so every edge is placed on whole pixels and
 * the tracks keep a clear gap; the earlier strokes fell between pixels and the tracks ran together
 * (Xiaomi MiTV-MFTR0). One path, so a translucent color does not darken where parts overlap.
 */
private fun switchesPath(s: Float): Path {
    val line = maxOf(1f, kotlin.math.round(s / 16f))
    val h = kotlin.math.round(s * 0.36f)
    val gap = kotlin.math.round(s * 0.12f)
    val left = kotlin.math.round(s * 0.04f)
    val right = s - left
    val top = kotlin.math.round((s - 2 * h - gap) / 2f)
    val bottom = top + h + gap
    val r = h / 2f
    fun pill(l: Float, t: Float, w: Float, ht: Float) = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, l + w, t + ht, CornerRadius(ht / 2f)))
    }
    fun disc(cx: Float, cy: Float, radius: Float) = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(cx - radius, cy - radius, cx + radius, cy + radius))
    }
    val outline = Path.combine(
        PathOperation.Difference,
        pill(left, top, right - left, h),
        pill(left + line, top + line, right - left - 2 * line, h - 2 * line),
    )
    val upper = Path.combine(PathOperation.Union, outline, disc(left + r, top + r, r))
    val lower = Path.combine(PathOperation.Difference, pill(left, bottom, right - left, h), disc(right - r, bottom + r, r - line))
    return Path.combine(PathOperation.Union, upper, lower)
}
