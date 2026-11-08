package app.orchard.tv.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.orchard.tv.lite.Ui.textPx

/**
 * An open folder, as the full app's FolderView: its name over its apps, at Home's tile size, on
 * Home frosted and dimmed. Apps open from here as from Home; holding one (or the menu key) gives
 * its menu, which comes back here. Back closes the folder.
 */
class FolderView(
    context: Context,
    close: () -> Unit,
    val folderId: String,
    name: String,
    apps: List<AppEntry>,
    private val launch: (AppEntry) -> Unit,
    private val menu: (AppEntry, View) -> Unit,
    /** Puts the first app on the second's slot in this folder (LayoutStore.moveInFolder). */
    private val moveTo: (String, String) -> Unit = { _, _ -> },
) : Sheet(context, close) {
    private val content = FrameLayout(context)
    private val tiles = ArrayList<HomeView.TileView>()
    private val names = ArrayList<TextView>()
    private var apps: List<AppEntry> = emptyList()
    private var focus = 0
    private val press = Press()
    /** The focused app follows the remote, as on Home (issue #1344). */
    private var moving = false
    private val hint = TextView(context)

    init {
        // Home frosted under 45% black: one picture, made once per wallpaper.
        addView(object : View(context) {
            private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            private val dst = RectF()
            override fun onDraw(canvas: Canvas) {
                val b = backdrop()
                if (b == null) { canvas.drawColor(0xE6000000.toInt()); return }
                dst.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawBitmap(b, null, dst, paint)
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.clipChildren = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(context.label(name, 26f, 600, display = true, spacingEm = -0.015f), LayoutParams(Ui.pxi(960f - 2 * HomeView.MARGIN), LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.pxi(HomeView.MARGIN); topMargin = Ui.pxi(56f)
        })
        // Home's move hint, at the foot.
        hint.apply {
            setTextColor(Ui.WHITE)
            textPx(15f)
            typeface = Ui.font(500)
            text = context.getString(R.string.move_hint)
            setShadowLayer(Ui.px(4f), 0f, 0f, 0x99000000.toInt())
            visibility = INVISIBLE
        }
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.pxi(28f)
        })
        setApps(apps)
        if (LiteSettings.full(isHardwareAccelerated)) {
            alpha = 0f; content.scaleX = 0.94f; content.scaleY = 0.94f
            post {
                animate().alpha(1f).setDuration(200).start()
                content.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }
        }
    }

    /** The folder's apps, again after a change from its menu or the phone page. */
    fun setApps(list: List<AppEntry>) {
        val focusedKey = apps.getOrNull(focus)?.key
        for (t in tiles) content.removeView(t)
        for (n in names) content.removeView(n)
        tiles.clear(); names.clear()
        apps = list
        list.forEachIndexed { i, app ->
            val t = HomeView.TileView(context).also { it.entry = app }
            content.addView(t, LayoutParams(Ui.pxi(HomeView.TILE_W + 2 * HomeView.TILE_EXT), Ui.pxi(HomeView.TILE_H + 2 * HomeView.TILE_EXT)))
            t.translationX = Ui.px(x(i) - HomeView.TILE_EXT)
            t.translationY = Ui.px(y(i) - HomeView.TILE_EXT)
            val n = HomeView.nameView(context).apply { text = app.label }
            content.addView(n, LayoutParams(Ui.pxi(HomeView.TILE_W + 36f), Ui.pxi(24f)))
            tiles += t; names += n
        }
        focus = list.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 } ?: focus.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        showFocus(animate = false)
    }

    private fun x(i: Int) = HomeView.MARGIN + (i % HomeView.COLS) * (HomeView.TILE_W + HomeView.GAP)
    private fun y(i: Int) = TOP + (i / HomeView.COLS) * HomeView.PITCH

    private fun showFocus(animate: Boolean) {
        val smooth = animate && LiteSettings.full(isHardwareAccelerated)
        val ring = LiteSettings.focusStyle == FocusStyle.HighContrast
        tiles.forEachIndexed { i, t ->
            val on = i == focus
            t.lifted = on
            t.ring = ring
            t.shadow = LiteSettings.effects != Effects.Lite
            t.moving = on && moving
            val s = if (on) HomeView.FOCUS_SCALE else 1f
            if (smooth) t.animate().scaleX(s).scaleY(s).setDuration(160).start() else { t.scaleX = s; t.scaleY = s }
            val n = names[i]
            n.visibility = if ((on || LiteSettings.showAllNames) && !(on && moving)) VISIBLE else INVISIBLE
            n.alpha = if (on) 1f else 0.7f
            var dy = HomeView.TILE_H + 10f
            if (on) dy += HomeView.TILE_H * (HomeView.FOCUS_SCALE - 1) / 2 + if (ring) (HomeView.RING_GAP + HomeView.RING_WIDTH) * HomeView.FOCUS_SCALE else 0f
            n.translationX = Ui.px(x(i) + HomeView.TILE_W / 2) - n.layoutParams.width / 2f
            n.translationY = Ui.px(y(i) + dy)
        }
        hint.visibility = if (moving) VISIBLE else INVISIBLE
        // Rows past the bottom scroll up, the focused one kept in view with room for its name.
        val bottom = y(focus) + HomeView.PITCH + 24f
        val shift = -(bottom - Ui.canvasH).coerceAtLeast(0f)
        if (smooth) content.animate().translationY(Ui.px(shift)).setDuration(220).start() else content.translationY = Ui.px(shift)
    }

    /** Starts moving [key] (from its menu). */
    fun startMove(key: String) {
        focus = apps.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return
        moving = true
        press.reset()
        showFocus(animate = true)
    }

    private fun endMove() { moving = false; showFocus(animate = true) }

    override fun onKey(e: KeyEvent): Boolean {
        val app = apps.getOrNull(focus)
        if (press.onKey(e, click = { if (moving) endMove() else app?.let(launch) }, longPress = { if (!moving) app?.let { menu(it, tiles[focus]) } })) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (e.action == KeyEvent.ACTION_UP) { if (moving) endMove() else close() }; return true }
            KeyEvent.KEYCODE_MENU -> { if (e.action == KeyEvent.ACTION_UP && !moving) app?.let { menu(it, tiles[focus]) }; return true }
        }
        val cols = HomeView.COLS
        val to = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (focus % cols == 0) focus else focus - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (focus % cols == cols - 1 || focus == apps.size - 1) focus else focus + 1
            KeyEvent.KEYCODE_DPAD_UP -> if (focus < cols) focus else focus - cols
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                focus + cols < apps.size -> focus + cols
                focus / cols < (apps.size - 1) / cols -> apps.size - 1
                else -> focus
            }
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN && to != focus) {
            // Moving: the app takes the slot, and setApps keeps focus on it.
            if (moving && app != null) moveTo(app.key, apps[to].key)
            else { focus = to; showFocus(animate = true) }
        }
        return true
    }

    private companion object {
        /** Where the first row starts: under the folder's name. */
        const val TOP = 56f + 31f + 36f
        private var frosted: Pair<Bitmap, Bitmap>? = null

        /** The frosted wallpaper under 45% black, made again when the wallpaper changes. */
        fun backdrop(): Bitmap? {
            val frost = Glass.frost ?: return null
            frosted?.takeIf { it.first === frost }?.let { return it.second }
            return Images.darkened(frost, 0.45f).also { frosted = frost to it }
        }
    }
}

/**
 * The parental PIN (the full app's PinPad, issue #10): four digits, from the remote's number keys
 * or the keypad on screen, or four arrow keys. [Mode.Create] asks which kind, then asks twice and
 * stores it; [Mode.Unlock] checks it. [done] runs once it is right.
 */
class PinPad(context: Context, close: () -> Unit, private val mode: Mode, private val done: () -> Unit) : Sheet(context, close) {
    /** [Verify] checks the PIN without unlocking the rest for a while (Kids mode). */
    enum class Mode { Unlock, Verify, Create }

    private var entered = ""
    private var first: String? = null
    private var error: Int? = null
    /** Null while a new PIN's kind is still to be chosen. */
    private var arrows: Boolean? = if (mode != Mode.Create) LiteSettings.pinArrows else null
    private val col = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(Ui.pxi(36f), Ui.pxi(28f), Ui.pxi(36f), Ui.pxi(28f))
    }
    private val dots = Dots(context)

    init {
        addView(View(context).apply { setBackgroundColor(0x73000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val panel = GlassBox(context, 28f)
        panel.addView(col)
        addView(panel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        build()
    }

    private fun build() {
        col.removeAllViews()
        clearRows()
        val title = when {
            mode != Mode.Create -> R.string.pin_enter
            first == null -> R.string.pin_create
            else -> R.string.pin_confirm
        }
        val hint = when {
            arrows == null -> R.string.pin_kind_hint
            arrows == true -> if (mode == Mode.Create) R.string.pin_arrows_create_hint else R.string.pin_arrows_hint
            mode == Mode.Create -> R.string.pin_create_hint
            else -> R.string.pin_enter_hint
        }
        col.addView(context.label(context.getString(title), 19f, 600, display = true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Read here: inside the TextView's apply, "error" would be TextView.getError().
        val wrong = error
        col.addView(note(context, context.getString(wrong ?: hint)).apply {
            setTextColor(if (wrong != null) Ui.RED else Ui.SECONDARY)
            gravity = Gravity.CENTER_HORIZONTAL
        }, LinearLayout.LayoutParams(Ui.pxi(340f), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(6f) })
        if (arrows == null) {
            val digits = KeyButton(context, context.getString(R.string.pin_kind_digits), w = 150f).apply { action = { arrows = false; build() } }
            val arrowKeys = KeyButton(context, context.getString(R.string.pin_kind_arrows), w = 150f).apply { action = { arrows = true; build() } }
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(digits)
            line.addView(arrowKeys, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(12f) })
            col.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(22f) })
            row(digits, arrowKeys)
            focusFirst()
            return
        }
        col.addView(dots, LinearLayout.LayoutParams(Ui.pxi(4 * 14f + 3 * 16f), Ui.pxi(14f)).apply { topMargin = Ui.pxi(18f) })
        dots.invalidate()
        if (arrows == true) return
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for ((ri, keys) in listOf("123", "456", "789", " 0<").withIndex()) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val controls = ArrayList<Control>()
            for ((ki, k) in keys.withIndex()) {
                val lp = LinearLayout.LayoutParams(Ui.pxi(64f), Ui.pxi(48f)).apply { if (ki > 0) leftMargin = Ui.pxi(12f) }
                when (k) {
                    ' ' -> line.addView(View(context), lp)
                    '<' -> KeyButton(context, null, Glyph.ChevronLeft).apply { action = ::erase }.also { line.addView(it, lp); controls += it }
                    else -> KeyButton(context, k.toString()).apply { action = { type(k) } }.also { line.addView(it, lp); controls += it }
                }
            }
            grid.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (ri > 0) topMargin = Ui.pxi(12f) })
            row(*controls.toTypedArray())
        }
        col.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(22f) })
        focusFirst()
    }

    private fun complete(pin: String) {
        entered = ""
        when (mode) {
            Mode.Unlock -> if (LiteSettings.unlock(pin)) { done(); return } else error = R.string.pin_wrong
            Mode.Verify -> if (LiteSettings.pinMatches(pin)) { done(); return } else error = R.string.pin_wrong
            Mode.Create -> when (val earlier = first) {
                null -> { first = pin; error = null }
                pin -> { LiteSettings.choosePin(pin, arrows = arrows == true); done(); return }
                else -> { if (earlier != pin) { first = null; error = R.string.pin_mismatch } }
            }
        }
        val (r, c) = position
        build()
        if (arrows == false) focusFirst(r, c)
    }

    private fun type(ch: Char) {
        if (entered.length >= LENGTH) return
        entered += ch
        dots.invalidate()
        if (entered.length == LENGTH) complete(entered)
    }

    private fun erase() { entered = entered.dropLast(1); dots.invalidate() }

    override fun onKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (arrows == true && code != KeyEvent.KEYCODE_BACK) {
            // Every arrow is an entry, never a move; OK does nothing.
            val symbol = ARROWS[code]
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && symbol != null) type(symbol)
            return true
        }
        if (arrows == false && e.action == KeyEvent.ACTION_DOWN) {
            if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { type('0' + (code - KeyEvent.KEYCODE_0)); return true }
            if (code == KeyEvent.KEYCODE_DEL) { erase(); return true }
        }
        return super.onKey(e)
    }

    /** The four dots, filled as the PIN is typed. */
    private inner class Dots(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val d = Ui.px(14f)
            val stroke = Ui.px(1.5f)
            for (i in 0 until LENGTH) {
                val cx = i * (d + Ui.px(16f)) + d / 2
                paint.color = Ui.WHITE
                if (i < entered.length) {
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, d / 2, d / 2, paint)
                } else {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = stroke
                    canvas.drawCircle(cx, d / 2, d / 2 - stroke / 2, paint)
                }
            }
        }
    }

    private companion object {
        const val LENGTH = 4
        val ARROWS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to 'U',
            KeyEvent.KEYCODE_DPAD_DOWN to 'D',
            KeyEvent.KEYCODE_DPAD_LEFT to 'L',
            KeyEvent.KEYCODE_DPAD_RIGHT to 'R',
        )
    }
}
