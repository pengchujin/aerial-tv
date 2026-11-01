package app.orchard.tv.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout

/**
 * One focusable control in a sheet, drawn by hand in the full app's look (ui/home/Overlays.kt,
 * ui/control/ControlCenter.kt, ui/wallpaper/WallpaperSheet.kt): its plate turns near-white with
 * dark content when focused, and it grows a little. OK goes to [click]; left and right to [side]
 * first, which a slider takes.
 */
abstract class Control(context: Context) : View(context) {
    var focusedLook = false
        set(v) {
            if (field == v) return
            field = v
            invalidate()
            val s = if (v) focusScale else 1f
            if (isHardwareAccelerated) animate().scaleX(s).scaleY(s).setDuration(140).start() else { scaleX = s; scaleY = s }
        }
    open val focusScale = 1.03f
    var action: () -> Unit = {}
    open fun click() = action()
    open fun side(dir: Int): Boolean = false

    protected val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    protected val rect = RectF()
    /** Plates and borders with continuous corners (Shapes.Tint). */
    protected val tint = Shapes.Tint()

    protected fun baseline(p: Paint, centerY: Float) = centerY - (p.descent() + p.ascent()) / 2
    protected fun fit(text: String, p: TextPaint, room: Float) = TextUtils.ellipsize(text, p, room, TextUtils.TruncateAt.END).toString()
}

/** Overlays.MenuRow: a glyph, a label, maybe a value on the right; 44 high, corners 12. */
class MenuRow(context: Context, private val glyph: Glyph, var text: String, var trailing: String? = null, private val destructive: Boolean = false) : Control(context) {
    private val label = TextPaint(Ui.text(14f, 500))
    private val value = TextPaint(Ui.text(13f, 500))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.pxi(44f))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (focusedLook) tint.draw(canvas, Shapes.mask(width, height, Ui.px(12f)), 0f, 0f, Ui.FOCUS)
        val fg = when {
            focusedLook -> if (destructive) 0xFFD70015.toInt() else Ui.ON_FOCUS
            destructive -> Ui.RED
            else -> Ui.WHITE
        }
        val pad = Ui.px(14f)
        val g = Ui.pxi(18f)
        canvas.drawBitmap(Glyphs.image(glyph, g, fg), pad, (h - g) / 2, bitmapPaint)
        val x = pad + g + Ui.px(12f)
        var right = w - pad
        trailing?.let {
            value.color = if (focusedLook) 0x991D1D1F.toInt() else Ui.SECONDARY
            val t = fit(it, value, Ui.px(200f))
            right -= value.measureText(t)
            canvas.drawText(t, right, baseline(value, h / 2), value)
            right -= Ui.px(12f)
        }
        label.color = fg
        canvas.drawText(fit(text, label, right - x), x, baseline(label, h / 2), label)
    }
}

/** ControlCenter.RoundButton: a 48-unit disc with a glyph, a caption under it; 64 wide. */
class RoundButton(context: Context, private var glyph: Glyph, private val caption: String) : Control(context) {
    override val focusScale = 1.06f
    private val text = TextPaint(Ui.text(11f, 500))
    var level = 1f

    fun setGlyph(g: Glyph) { glyph = g; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(Ui.pxi(64f), Ui.pxi(72f))

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pivotX = width / 2f; pivotY = Ui.px(24f)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val r = Ui.px(24f)
        bg.color = if (focusedLook) Ui.FOCUS else Ui.FILL
        canvas.drawCircle(cx, r, r, bg)
        val g = Ui.pxi(20f)
        canvas.drawBitmap(Glyphs.image(glyph, g, if (focusedLook) Ui.ON_FOCUS else Ui.WHITE, level), cx - g / 2f, r - g / 2f, bitmapPaint)
        text.color = if (focusedLook) Ui.WHITE else Ui.SECONDARY
        text.textAlign = Paint.Align.CENTER
        canvas.drawText(fit(caption, text, width.toFloat()), cx, Ui.px(48f + 6f) - text.ascent(), text)
    }
}

/** ControlCenter's slider row: 40 high, corners 14, a glyph, a bar, the value; left and right step it. */
class SliderRow(
    context: Context,
    private val glyph: (Float) -> Glyph,
    private val read: () -> Pair<Int, Int>,
    private val write: (Int) -> Unit,
    private val shown: (Int, Int) -> String,
    /** How far one press moves it. */
    private val step: Int = 1,
) : Control(context) {
    private val text = TextPaint(Ui.text(15f, 600))

    override fun side(dir: Int): Boolean {
        val (v, max) = read()
        val to = (v + dir * step).coerceIn(0, max)
        if (to != v) write(to)
        invalidate()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.pxi(40f))

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        tint.draw(canvas, Shapes.mask(width, height, Ui.px(14f)), 0f, 0f, if (focusedLook) Ui.FOCUS else Ui.FILL)
        val fg = if (focusedLook) Ui.ON_FOCUS else Ui.WHITE
        val (v, max) = read()
        val fraction = if (max > 0) v / max.toFloat() else 0f
        val pad = Ui.px(14f)
        val g = Ui.pxi(18f)
        canvas.drawBitmap(Glyphs.image(glyph(fraction), g, fg, fraction), pad, (h - g) / 2, bitmapPaint)
        text.color = fg
        val value = shown(v, max)
        val valueW = maxOf(text.measureText(value), Ui.px(28f))
        canvas.drawText(value, w - pad - text.measureText(value), baseline(text, h / 2), text)
        val left = pad + g + Ui.px(12f)
        val right = w - pad - valueW - Ui.px(12f)
        val bh = Ui.px(6f)
        bg.color = (0x33 shl 24) or (fg and 0xFFFFFF)
        rect.set(left, (h - bh) / 2, right, (h + bh) / 2)
        canvas.drawRoundRect(rect, bh / 2, bh / 2, bg)
        bg.color = fg
        rect.set(left, (h - bh) / 2, left + (right - left) * fraction, (h + bh) / 2)
        canvas.drawRoundRect(rect, bh / 2, bh / 2, bg)
    }
}

/** WallpaperSheet's Chip: a pill for one of a few values, a check on the chosen one. */
class Chip(context: Context, private val label: String, private val on: () -> Boolean, private val glyphAfter: Glyph? = null) : Control(context) {
    override val focusScale = 1.06f
    private val text = TextPaint(Ui.text(14f, 500))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var w = Ui.px(40f) + text.measureText(label)
        if (on() || glyphAfter != null) w += Ui.px(14f + 8f)
        setMeasuredDimension(w.toInt() + 1, Ui.pxi(40f))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val chosen = on()
        bg.color = when {
            focusedLook -> Ui.FOCUS
            chosen || glyphAfter != null -> Ui.FILL_STRONG
            else -> Ui.FILL
        }
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, bg)
        val fg = if (focusedLook) Ui.ON_FOCUS else Ui.WHITE
        var x = Ui.px(20f)
        val g = Ui.pxi(14f)
        if (chosen && glyphAfter == null) {
            canvas.drawBitmap(Glyphs.image(Glyph.Check, g, fg), x, (h - g) / 2, bitmapPaint)
            x += g + Ui.px(8f)
        }
        text.color = fg
        canvas.drawText(label, x, baseline(text, h / 2), text)
        glyphAfter?.let {
            val gg = Ui.pxi(18f)
            canvas.drawBitmap(Glyphs.image(it, gg, fg), x + text.measureText(label) + Ui.px(6f), (h - gg) / 2, bitmapPaint)
        }
    }

    /** The chosen chip changed elsewhere in its row. */
    fun refresh() { requestLayout(); invalidate() }
}

/** PinPad's key: [w] by 48, corners 12, a digit, a word or a glyph in the middle. */
class KeyButton(context: Context, private val text: String?, private val glyph: Glyph? = null, private val w: Float = 64f) : Control(context) {
    override val focusScale = 1.08f
    private val paint = TextPaint(Ui.text(if (text != null && text.length == 1) 19f else 15f, 600))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(Ui.pxi(w), Ui.pxi(48f))

    override fun onDraw(canvas: Canvas) {
        val ww = width.toFloat(); val h = height.toFloat()
        tint.draw(canvas, Shapes.mask(width, height, Ui.px(12f)), 0f, 0f, if (focusedLook) Ui.FOCUS else Ui.FILL)
        val fg = if (focusedLook) Ui.ON_FOCUS else Ui.WHITE
        if (glyph != null) {
            val g = Ui.pxi(18f)
            canvas.drawBitmap(Glyphs.image(glyph, g, fg), (ww - g) / 2, (h - g) / 2, bitmapPaint)
        } else if (text != null) {
            paint.color = fg
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(text, ww / 2, baseline(paint, h / 2), paint)
        }
    }
}

/** WallpaperSheet's wallpaper card: a 168×94 picture with corners 12, its name under it, a check when chosen. */
class Thumb(context: Context, private val name: String, private val chosen: () -> Boolean) : Control(context) {
    override val focusScale = 1.06f
    var picture: Bitmap? = null
        set(v) { field = v; invalidate() }
    private val text = TextPaint(Ui.text(11f, 500, Ui.SECONDARY))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(Ui.pxi(168f), Ui.pxi(94f + 6f + 16f))

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pivotX = width / 2f; pivotY = Ui.px(47f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val ph = Ui.px(94f)
        val r = Ui.px(12f)
        rect.set(0f, 0f, w, ph)
        val pic = picture
        if (pic != null) {
            // Made with its corners already cut (Personalize's thumbnails).
            canvas.drawBitmap(pic, null, rect, bitmapPaint)
        } else {
            tint.draw(canvas, Shapes.mask(width, Ui.pxi(94f), r), 0f, 0f, Ui.FILL)
        }
        val on = chosen()
        val border = when {
            focusedLook -> Ui.FOCUS
            on -> 0x8CF5F5F7.toInt()
            else -> Ui.HAIRLINE
        }
        tint.draw(canvas, Shapes.band(width, Ui.pxi(94f), r, Ui.px(if (focusedLook || on) 3f else 1f)), 0f, 0f, border)
        if (on) {
            val d = Ui.px(22f)
            val cx = w - Ui.px(7f) - d / 2
            val cy = Ui.px(7f) + d / 2
            bg.color = Ui.FOCUS
            canvas.drawCircle(cx, cy, d / 2, bg)
            val g = Ui.pxi(13f)
            canvas.drawBitmap(Glyphs.image(Glyph.Check, g, Ui.ON_FOCUS), cx - g / 2f, cy - g / 2f, bitmapPaint)
        }
        text.color = if (focusedLook) Ui.WHITE else Ui.SECONDARY
        canvas.drawText(fit(name, text, w), 0f, ph + Ui.px(6f) - text.ascent(), text)
    }
}

/**
 * The window a [Sheet]'s content scrolls in: its child is as tall as its content (a FrameLayout
 * would hold a wrap-content child to its own height and squeeze the rows below together), and it
 * is moved by translation.
 */
class Viewport(context: Context, private val horizontal: Boolean = false) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (horizontal) child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.AT_MOST))
            else child.measure(
                MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
    }
}

/**
 * A panel of [Control]s on the remote: rows of controls, up and down between rows to the nearest
 * one, left and right along a row (or into a slider), OK as the full app's Pressable (only a press
 * that began here), Back closes. A [viewport] scrolls its content by translation to keep the
 * focused control in view.
 */
open class Sheet(context: Context, protected val close: () -> Unit) : FrameLayout(context) {
    private val rows = ArrayList<MutableList<Control>>()
    private var r = 0
    private var c = 0
    /** The column focus last left each row from; see move. */
    private val leftAt = HashMap<Int, Int>()
    private val press = Press()
    /** The content that scrolls, and the height it scrolls within; null for a sheet that fits. */
    var scroller: View? = null
    var viewport = 0
    /** Called when the focused control changes, for notes that follow focus. */
    var onFocusMoved: (Control) -> Unit = {}

    fun row(vararg controls: Control): List<Control> {
        rows += controls.toMutableList()
        return controls.toList()
    }

    val focused: Control? get() = rows.getOrNull(r)?.getOrNull(c)

    /** Where the focus is, as (row, column), to put it back after the rows are made again. */
    val position: Pair<Int, Int> get() = r to c

    /** Forgets every row, for a sheet that makes its rows again (Personalize when a row comes or goes). */
    protected fun clearRows() {
        focused?.focusedLook = false
        rows.clear()
        leftAt.clear()
        r = 0; c = 0
    }

    /**
     * A focused control grows a little (as the full app's Pressable); its parents must not clip it,
     * or the grown corners are cut square (Control Center's slider on Xiaomi MiTV-MFTR0). A
     * scrolling [scroller]'s viewport still clips, with room around its content for the growth.
     */
    protected fun unclip(v: View = this) {
        if (v is android.view.ViewGroup) {
            // With hardware drawing a parent's clipChildren clips each child to the child's own
            // bounds: the viewport is cut to its bounds by its parent's flag, not its own.
            val viewport = scroller?.parent as? View
            if (v !== viewport && v !== viewport?.parent) { v.clipChildren = false; v.clipToPadding = false }
            for (i in 0 until v.childCount) unclip(v.getChildAt(i))
        }
    }

    fun focusFirst(row: Int = 0, col: Int = 0) {
        unclip()
        focused?.focusedLook = false
        r = row.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        c = col.coerceIn(0, ((rows.getOrNull(r)?.size ?: 1) - 1).coerceAtLeast(0))
        focused?.focusedLook = true
        focused?.let { onFocusMoved(it) }
        // Rows made just now have no place yet: once they are laid out.
        if (focused?.isLaidOutCompat() == true) keepInView() else post { keepInView() }
    }

    private fun View.isLaidOutCompat() = width > 0 && height > 0

    open fun onKey(e: KeyEvent): Boolean {
        if (press.onKey(e, click = { focused?.click() })) return true
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) close()
            return true
        }
        val nav = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
        if (!nav) return false
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (focused?.side(-1) != true) move(0, -1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (focused?.side(1) != true) move(0, 1)
            KeyEvent.KEYCODE_DPAD_UP -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1, 0)
        }
        return true
    }

    private fun move(dr: Int, dc: Int) {
        val from = focused ?: return
        if (dc != 0) {
            val nc = c + dc
            if (nc !in rows[r].indices) return
            from.focusedLook = false
            c = nc
        } else {
            val nr = r + dr
            if (nr !in rows.indices) return
            // The control in the next row nearest the focused one's middle. From a row-wide one,
            // the one focus left that row from: Control Center's volume slider sits under three
            // buttons, and Up always found the middle one, whichever Down came from (#860).
            val x = centerX(from)
            from.focusedLook = false
            leftAt[r] = c
            r = nr
            val back = leftAt[r]?.takeIf { rows[r - dr].size == 1 && it in rows[r].indices }
            c = back ?: rows[r].indices.minByOrNull { kotlin.math.abs(centerX(rows[r][it]) - x) } ?: 0
        }
        focused?.focusedLook = true
        focused?.let { onFocusMoved(it) }
        keepInView()
    }

    private val loc = IntArray(2)
    private fun centerX(v: View): Float { v.getLocationInWindow(loc); return loc[0] + v.width / 2f }

    private fun keepInView() {
        val content = scroller ?: return
        val f = focused ?: return
        // The focused control's top and bottom within the content.
        var top = 0f
        var v: View? = f
        while (v != null && v !== content) { top += v.top; v = v.parent as? View }
        // Room below for the note under a group of rows, which follows the focus.
        val bottom = top + f.height + Ui.px(38f)
        val margin = Ui.px(16f)
        val shift = content.translationY
        val to = when {
            top + shift < margin -> -(top - margin)
            bottom + shift > viewport - margin -> -(bottom - viewport + margin)
            else -> shift
        }.coerceAtMost(0f)
        if (to != shift) {
            if (isHardwareAccelerated) content.animate().translationY(to).setDuration(200).start() else content.translationY = to
        }
    }
}
