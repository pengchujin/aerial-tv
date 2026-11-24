package app.orchard.tv.lite

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The full app's glyphs (ui/theme/Glyphs.kt): filled Material Symbols Rounded, whose path data
 * GlyphPaths (copied from the full app at build time) holds in the symbols' 960-unit box.
 */
enum class Glyph { Folder, Pencil, Wifi, Ethernet, Offline, Gear, Input, Speaker, SpeakerMuted, Switches, EyeSlash, Move, Info, Trash, ChevronRight, ChevronLeft, Check, Hdmi, Antenna, Av, Sun, Photo, Play, Bag, CloudSun, Calendar, Cast, Shield, Globe, Moon, Cloud, CloudRain, CloudSnow, CloudFog, CloudBolt, Download, Power, Usb, Home, Kids, Mic }

object Glyphs {
    /** [level] picks the variant of Wi-Fi (signal) and Speaker (volume): 0 to 1. Null for Switches, drawn by hand. */
    fun data(glyph: Glyph, level: Float = 1f): String? = when (glyph) {
        Glyph.Wifi -> when {
            level < 0.34f -> GlyphPaths.WIFI_1
            level < 0.67f -> GlyphPaths.WIFI_2
            else -> GlyphPaths.WIFI
        }
        Glyph.Ethernet -> GlyphPaths.LAN
        Glyph.Offline -> GlyphPaths.WIFI_OFF
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
        Glyph.Mic -> GlyphPaths.MIC
    }

    private val parsed = HashMap<String, Path>()

    /** [data] as a path in its own units. */
    fun path(data: String): Path = synchronized(parsed) { parsed.getOrPut(data) { SvgPath.parse(data) } }

    /** Draws [glyph] filling an [s]-sized square at the canvas origin. */
    fun draw(canvas: Canvas, glyph: Glyph, s: Float, paint: Paint, level: Float = 1f) {
        val data = data(glyph, level)
        if (data == null) {
            canvas.drawPath(switches(s), paint)
            return
        }
        canvas.save()
        canvas.scale(s / 960f, s / 960f)
        // The symbols' box runs from y = -960 to 0.
        canvas.translate(0f, 960f)
        canvas.drawPath(path(data), paint)
        canvas.restore()
    }

    private val images = HashMap<List<Any>, Bitmap>()

    /**
     * [glyph] at [size] px in [color], rasterized once at twice the size and scaled down, as the
     * full app does for UI glyphs (paths drawn live came out jagged on some TV GPUs).
     */
    fun image(glyph: Glyph, size: Int, color: Int, level: Float = 1f): Bitmap {
        val key = listOf(glyph, size, color, data(glyph, level) ?: "")
        synchronized(images) { images[key]?.let { return it } }
        val large = Bitmap.createBitmap(size * 2, size * 2, Bitmap.Config.ARGB_8888)
        draw(Canvas(large), glyph, size * 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }, level)
        val small = Bitmap.createScaledBitmap(large, size, size, true)
        if (small !== large) large.recycle()
        synchronized(images) { images[key] = small }
        return small
    }

    /**
     * Two toggles, the Control Center glyph, as the full app draws it (after SF Symbols'
     * switch.2): an outlined track with its knob on the left over a solid track with its knob cut
     * out on the right, every edge on whole pixels.
     */
    private fun switches(s: Float): Path {
        val line = maxOf(1f, Math.round(s / 16f).toFloat())
        val h = Math.round(s * 0.36f).toFloat()
        val gap = Math.round(s * 0.12f).toFloat()
        val left = Math.round(s * 0.04f).toFloat()
        val right = s - left
        val top = Math.round((s - 2 * h - gap) / 2f).toFloat()
        val bottom = top + h + gap
        val r = h / 2f
        return Path().apply {
            fillType = Path.FillType.EVEN_ODD
            // Upper: an outline (outer minus inner pill) plus the solid knob on the left.
            addRoundRect(RectF(left, top, right, top + h), r, r, Path.Direction.CW)
            addRoundRect(RectF(left + line, top + line, right - line, top + h - line), r - line, r - line, Path.Direction.CW)
            addCircle(left + r, top + r, r - line, Path.Direction.CW)
            // Lower: a solid pill with its knob cut out on the right.
            addRoundRect(RectF(left, bottom, right, bottom + h), r, r, Path.Direction.CW)
            addCircle(right - r, bottom + r, r - line, Path.Direction.CW)
        }
    }
}

/**
 * SVG path data to an android.graphics.Path, every command (arcs as cubic Béziers). Android's own
 * parser is hidden before API 26 in androidx only, and Lite carries no AndroidX.
 */
object SvgPath {
    fun parse(d: String): Path {
        val path = Path()
        val t = Tokens(d)
        var cx = 0f; var cy = 0f // current point
        var sx = 0f; var sy = 0f // subpath start
        var lcx = 0f; var lcy = 0f // last control point
        var last = ' '
        var cmd = ' '
        while (true) {
            t.skipSeparators()
            if (t.done()) break
            if (t.atCommand()) cmd = t.command() else if (cmd == 'M') cmd = 'L' else if (cmd == 'm') cmd = 'l'
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { cx = ox + t.num(); cy = oy + t.num(); path.moveTo(cx, cy); sx = cx; sy = cy }
                'L' -> { cx = ox + t.num(); cy = oy + t.num(); path.lineTo(cx, cy) }
                'H' -> { cx = ox + t.num(); path.lineTo(cx, cy) }
                'V' -> { cy = oy + t.num(); path.lineTo(cx, cy) }
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num(); val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    path.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2
                }
                'S' -> {
                    val x1 = if (last.uppercaseChar() in "CS") 2 * cx - lcx else cx
                    val y1 = if (last.uppercaseChar() in "CS") 2 * cy - lcy else cy
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    path.cubicTo(x1, y1, x2, y2, cx, cy); lcx = x2; lcy = y2
                }
                'Q' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    cx = ox + t.num(); cy = oy + t.num()
                    path.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1
                }
                'T' -> {
                    val x1 = if (last.uppercaseChar() in "QT") 2 * cx - lcx else cx
                    val y1 = if (last.uppercaseChar() in "QT") 2 * cy - lcy else cy
                    cx = ox + t.num(); cy = oy + t.num()
                    path.quadTo(x1, y1, cx, cy); lcx = x1; lcy = y1
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    val x = ox + t.num(); val y = oy + t.num()
                    arc(path, cx, cy, x, y, rx, ry, rot, large, sweep)
                    cx = x; cy = y
                }
                'Z' -> { path.close(); cx = sx; cy = sy }
                else -> break
            }
            last = cmd
        }
        return path
    }

    private class Tokens(val s: String) {
        var i = 0
        fun done() = i >= s.length
        fun skipSeparators() { while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\t')) i++ }
        fun atCommand() = s[i].isLetter() && s[i] != 'e' && s[i] != 'E'
        fun command() = s[i++]
        fun flag(): Boolean { skipSeparators(); return s[i++] == '1' }
        fun num(): Float {
            skipSeparators()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length) { i++; if (s[i] == '-' || s[i] == '+') i++ }
                else break
            }
            return s.substring(start, i).toFloat()
        }
    }

    /** An SVG elliptical arc as cubic Béziers (the SVG spec's endpoint-to-center conversion). */
    private fun arc(path: Path, x0: Float, y0: Float, x: Float, y: Float, rxIn: Float, ryIn: Float, angle: Float, large: Boolean, sweep: Boolean) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn).toDouble(); var ry = abs(ryIn).toDouble()
        if (rx == 0.0 || ry == 0.0) { path.lineTo(x, y); return }
        val phi = Math.toRadians(angle.toDouble())
        val cosP = cos(phi); val sinP = sin(phi)
        val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
        val x1 = cosP * dx + sinP * dy
        val y1 = -sinP * dx + cosP * dy
        val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val den = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var coef = sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1 / ry
        val cyp = -coef * ry * x1 / rx
        val cx = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y0 + y) / 2.0
        val theta1 = atan2((y1 - cyp) / ry, (x1 - cxp) / rx)
        var delta = atan2((-y1 - cyp) / ry, (-x1 - cxp) / rx) - theta1
        if (!sweep && delta > 0) delta -= 2 * PI
        if (sweep && delta < 0) delta += 2 * PI
        val segments = ceil(abs(delta) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = delta / segments
        val k = 4.0 / 3.0 * tan(step / 4)
        var t = theta1
        for (n in 0 until segments) {
            val c1 = cos(t); val s1 = sin(t)
            val c2 = cos(t + step); val s2 = sin(t + step)
            fun px(ex: Double, ey: Double) = (cx + rx * ex * cosP - ry * ey * sinP).toFloat()
            fun py(ex: Double, ey: Double) = (cy + rx * ex * sinP + ry * ey * cosP).toFloat()
            path.cubicTo(
                px(c1 - k * s1, s1 + k * c1), py(c1 - k * s1, s1 + k * c1),
                px(c2 + k * s2, s2 - k * c2), py(c2 + k * s2, s2 - k * c2),
                px(c2, s2), py(c2, s2),
            )
            t += step
        }
    }
}
