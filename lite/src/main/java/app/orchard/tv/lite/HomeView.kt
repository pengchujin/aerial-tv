package app.orchard.tv.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.orchard.tv.lite.Ui.textPx

/**
 * Home, laid out as the full app's HomeScreen and its GridMetrics, with every layout choice in
 * Personalize: one row (the next peeking, or hidden until Home scrolls to it) or two rows on a
 * shelf, the glass plate or none, the focused app's name or every name, the clock small or large,
 * left or right. The tiles sit over the window's background (the baked wallpaper, the only
 * full-screen draw).
 *
 * Every tile is its own View, laid out once and placed by translation; focus scales it. These are
 * View properties, so with hardware acceleration they change without recording any drawing again,
 * which keeps Dalvik's main thread nearly idle while the focus moves. Nothing animates when no key
 * is pressed.
 */
class HomeView(context: Context, private val host: Host) : FrameLayout(context) {
    interface Host {
        fun launch(app: AppEntry)
        fun openFolder(folder: AppEntry)
        fun openMenu(app: AppEntry, tile: View)
        fun openControlCenter()
        fun startSaver()
        /** [key] was moved onto the slot [target] held before the move. */
        fun moved(key: String, target: String)
        /** Home went down into the grid (the frosted background) or back to the top row. */
        fun inGrid(grid: Boolean)
    }

    private val layer = FrameLayout(context)
    private val plate = PlateView(context)
    private val tiles = ArrayList<TileView>()
    private val names = ArrayList<TextView>()
    private val hint = TextView(context)
    val clock = ClockView(context)
    val pill = StatusPill(context)
    /** The pill, and the small clock beside it when it is on the right. */
    val topRight = LinearLayout(context)
    private val press = Press()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * How much of the bottom an opaque panel hides whole (Personalize's settled sheet): Home is not
     * drawn there, under it, with every frame of the panel (the full app's SheetCovers).
     */
    var coveredBelow = 0
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun dispatchDraw(canvas: Canvas) {
        if (coveredBelow <= 0) return super.dispatchDraw(canvas)
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, height - coveredBelow)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(saved)
    }

    /** The focused tile; [PILL] for the status pill. */
    private var focus = 0
    private var row = 0
    private var grid = false
    private var moving = false
    /** Home's order when the move began, to find the slot the app ended on. */
    private var beforeMove: List<String> = emptyList()

    // The full app's GridMetrics, in canvas units; worked out again whenever Home or a choice changes.
    private val canvasH = Ui.canvasH
    private var oneRow = true
    private var peek = true
    private var hasPlate = true
    private var plateNames = false
    private var firstGap = 0f
    private var topRowY = canvasH - PITCH - 12f
    private var shelfLift = 0f
    /** The rows on the shelf, over the plate: the top row, and in two-row mode the second too. */
    private var shelfRows = 1
    /** How far the second row sits under the top row on the shelf in two-row mode. */
    private var shelfSecond = PITCH
    /** The large clock's top: up beside the pill when two rows would not fit under it. */
    private var clockY = CLOCK_Y
    /** As the full app's: the row above clears the status pill, which four a row would reach at 37%. */
    private val restY get() = maxOf(canvasH * 0.37f, 72f + PITCH)

    var apps: List<AppEntry> = emptyList()
        private set

    init {
        clipChildren = false
        layer.clipChildren = false
        addView(layer, LayoutParams(Ui.screenW, Ui.screenH))
        layer.addView(plate, LayoutParams(Ui.screenW, Ui.pxi(TILE_H + 2 * PLATE_PAD_V + PLATE_NAME_ROOM)))
        plate.visibility = INVISIBLE

        topRight.orientation = LinearLayout.HORIZONTAL
        topRight.gravity = Gravity.CENTER_VERTICAL
        topRight.clipChildren = false
        topRight.addView(pill)
        pill.setOnClickListener { if (!moving) { focus = PILL; showFocus(animate = true); host.openControlCenter() } }
        addView(topRight, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            topMargin = Ui.pxi(28f)
            rightMargin = Ui.pxi(MARGIN - 14f)
        })
        hint.apply {
            setTextColor(Ui.WHITE)
            textPx(15f)
            typeface = Ui.font(500)
            text = context.getString(R.string.move_hint)
            setShadowLayer(Ui.px(4f), 0f, 0f, 0x99000000.toInt())
            visibility = INVISIBLE
        }
        addView(hint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.pxi(28f)
        })
        applySettings()
    }

    /**
     * Takes up the choices in Personalize: the layout, the plate, the names, the clock and the
     * focus style. Cheap enough to call after any of them changes.
     */
    fun applySettings() {
        clock.large = LiteSettings.largeClock
        clock.alignEnd = LiteSettings.clockRight
        (clock.parent as? android.view.ViewGroup)?.removeView(clock)
        if (!LiteSettings.largeClock && LiteSettings.clockRight) {
            // Just left of the pill, level with it.
            topRight.addView(clock, 0, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = Ui.pxi(14f)
            })
        } else {
            addView(clock, 1, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or if (LiteSettings.clockRight) Gravity.END else Gravity.START).apply {
                topMargin = Ui.pxi(if (LiteSettings.largeClock) clockY else 28f)
                leftMargin = Ui.pxi(MARGIN)
                rightMargin = Ui.pxi(MARGIN)
            })
        }
        relayout()
    }

    private fun relayout() {
        // Another number of apps a row: the tiles take its size (their faces follow with the reload).
        val tileW = Ui.pxi(TILE_W + 2 * TILE_EXT)
        if (tiles.firstOrNull()?.layoutParams?.width.let { it != null && it != tileW }) {
            for (t in tiles) t.layoutParams = t.layoutParams.apply { width = tileW; height = Ui.pxi(TILE_H + 2 * TILE_EXT) }
            for (n in names) n.layoutParams = n.layoutParams.apply { width = Ui.pxi(TILE_W + 36f) }
        }
        val rows = (apps.size + COLS - 1) / COLS
        // Tall enough to hold every row: Home scrolls the layer up by translation, and a click lands
        // on a tile only within the layer's own bounds. One screen high, the third row down and
        // below took no clicks once scrolled to (Mi TV 2, Android 4.4, issue #1094).
        val layerH = Ui.screenH * 2 + Ui.pxi(PITCH) * rows
        if (layer.layoutParams.height < layerH) layer.layoutParams = layer.layoutParams.apply { height = layerH }
        oneRow = LiteSettings.oneRowHome || rows <= 1
        peek = LiteSettings.peekNextRow
        hasPlate = LiteSettings.homePlate && apps.isNotEmpty()
        plateNames = LiteSettings.showAllNames && LiteSettings.homePlate
        firstGap = if (plateNames) PLATE_NAME_ROOM else 0f
        shelfRows = if (oneRow) 1 else 2
        // The full app's GridMetrics: two rows laid from the bottom up, on one plate closer
        // together when no names go between them, under the large clock, which goes up beside
        // the pill (at the left) when they would not fit, and lifts what still does not on the second row.
        shelfSecond = if (hasPlate && !plateNames) TILE_H + SHELF_ROW_GAP else PITCH
        val twoRowFit = canvasH - (if (hasPlate) PLATE_MARGIN_BOTTOM + PLATE_PAD_V + firstGap else ROW_NAME_ROOM) - shelfSecond - TILE_H
        val platePad = if (hasPlate) PLATE_PAD_V else 0f
        val newClockY = if (oneRow || !LiteSettings.largeClock) CLOCK_Y else
            (twoRowFit - platePad - LARGE_CLOCK_H).coerceIn(if (LiteSettings.clockRight) CLOCK_Y else 28f, CLOCK_Y)
        topRowY = when {
            !oneRow -> maxOf(twoRowFit, if (LiteSettings.largeClock) newClockY + LARGE_CLOCK_H + platePad else 0f)
            peek -> canvasH - PITCH - 12f - firstGap
            hasPlate -> canvasH - TILE_H - (PLATE_MARGIN_BOTTOM + PLATE_PAD_V) - firstGap
            else -> canvasH - TILE_H - 70f
        }
        shelfLift = if (oneRow) 0f else topRowY - twoRowFit
        if (newClockY != clockY) {
            clockY = newClockY
            (clock.layoutParams as? LayoutParams)?.let { if (LiteSettings.largeClock) clock.layoutParams = it.apply { topMargin = Ui.pxi(clockY) } }
        }
        // The plate holds both rows in two-row mode, as in the full app.
        val plateH = TILE_H + 2 * PLATE_PAD_V + firstGap + if (shelfRows > 1) shelfSecond else 0f
        plate.layoutParams.height.let { h ->
            if (h != Ui.pxi(plateH)) plate.layoutParams = plate.layoutParams.apply { height = Ui.pxi(plateH) }
        }
        plate.visibility = if (hasPlate) VISIBLE else INVISIBLE
        plate.translationY = Ui.px(topRowY - PLATE_PAD_V)
        plate.place(topRowY - PLATE_PAD_V, plateH)
        place(animate = false)
        showFocus(animate = false, force = true)
    }

    /** The glass for the plate: the frosted wallpaper, tinted as the full app's PlateTint. */
    fun setGlass(frost: Bitmap?) {
        plate.glass = frost
        plate.rebake()
    }

    /** Shows [list], keeping the focus on the same app when it is still there. */
    fun setApps(list: List<AppEntry>) {
        if (moving) return
        val focusedKey = apps.getOrNull(focus)?.key
        val old = HashMap<String, Pair<TileView, TextView>>()
        for (i in tiles.indices) old[tiles[i].entry.key] = tiles[i] to names[i]
        tiles.clear()
        names.clear()
        for (app in list) {
            val (t, n) = old.remove(app.key) ?: (TileView(context) to nameView(context)).also { (t, n) ->
                layer.addView(t, 1, LayoutParams(Ui.pxi(TILE_W + 2 * TILE_EXT), Ui.pxi(TILE_H + 2 * TILE_EXT)))
                layer.addView(n, LayoutParams(Ui.pxi(TILE_W + 36f), Ui.pxi(24f)))
                t.setOnClickListener { tapped(tiles.indexOf(t), long = false) }
                t.setOnLongClickListener { tapped(tiles.indexOf(t), long = true); true }
            }
            t.entry = app
            t.invalidate()
            n.text = app.label
            tiles += t
            names += n
        }
        for ((t, n) in old.values) { layer.removeView(t); layer.removeView(n) }
        apps = list
        if (focus != PILL) {
            focus = list.indexOfFirst { it.key == focusedKey }.takeIf { it >= 0 } ?: focus.coerceAtMost(list.size - 1).coerceAtLeast(0)
        }
        relayout()
    }

    /** Back to the first tile and the top, as after the Home key. */
    fun reset() {
        if (moving) finishMove()
        press.reset()
        focus = 0
        place(animate = false)
        showFocus(animate = false)
    }

    private fun x(i: Int) = MARGIN + (i % COLS) * (TILE_W + GAP)

    /** A row's top; below the top row, past the room for names on the plate while on the shelf. */
    private fun y(i: Int) = when {
        i / COLS == 1 && shelfRows > 1 && !grid -> topRowY + shelfSecond
        else -> topRowY + (i / COLS) * PITCH + if (i >= COLS && !grid) firstGap else 0f
    }

    /** The full app's GridMetrics.scrollFor: rows rest at 37% of the screen, the row above in view. */
    private fun scrollFor(row: Int): Float {
        if (row <= 0) return 0f
        val rows = (apps.size + COLS - 1) / COLS
        val wanted = topRowY + PITCH * row - restY
        val bottom = topRowY + PITCH * rows + 36f - canvasH
        return minOf(wanted, maxOf(bottom, topRowY + PITCH - restY))
    }

    /**
     * Without hardware acceleration (4.x boxes with no GPU driver for it, Android-x86) every frame
     * of an animation is drawn by the CPU and the 150 ms ones come out as two or three frames.
     * There the focus and the scroll jump, so each key press costs one frame. Effects: Lite does
     * the same anywhere.
     */
    private val smooth get() = LiteSettings.full(isHardwareAccelerated)

    private fun place(animate: Boolean) {
        tiles.forEachIndexed { i, t ->
            val tx = Ui.px(x(i) - TILE_EXT)
            val ty = Ui.px(y(i) - TILE_EXT)
            if (animate && smooth) t.animate().translationX(tx).translationY(ty).setDuration(180).setInterpolator(EASE).start()
            else { t.translationX = tx; t.translationY = ty }
        }
    }

    /** The rows below the shelf out of sight until Home scrolls to them: one row unless it peeks, two rows always. */
    private fun hiddenBelow(i: Int) = !grid && when {
        !oneRow -> i >= COLS * 2
        else -> !peek && i >= COLS
    }

    private fun showFocus(animate: Boolean, force: Boolean = false) {
        pill.focusedLook = focus == PILL
        val ring = LiteSettings.focusStyle == FocusStyle.HighContrast
        val shadow = LiteSettings.effects != Effects.Lite
        val newRow = if (focus == PILL) 0 else focus / COLS
        val newGrid = newRow >= if (oneRow) 1 else 2
        val gridChanged = newGrid != grid
        grid = newGrid
        if (gridChanged) place(animate)
        tiles.forEachIndexed { i, t ->
            val on = i == focus
            t.lifted = on
            t.ring = ring
            t.shadow = shadow
            t.moving = on && moving
            val s = if (on) FOCUS_SCALE else 1f
            if (animate && smooth) t.animate().scaleX(s).scaleY(s).setDuration(160).setInterpolator(EASE).start()
            else { t.scaleX = s; t.scaleY = s }
            t.alpha = if (hiddenBelow(i)) 0f else 1f
            placeName(i, on, ring)
        }
        hint.visibility = if (moving) VISIBLE else INVISIBLE
        if (newRow != row || gridChanged || !animate || force) {
            val wasGrid = row > 0 && !force
            row = newRow
            val lift = if (row > 0 && !grid) shelfLift else 0f
            // Two rows go into the grid a row further down; they scroll a row less, so the grid
            // opens as from one row, the first row whole at the top.
            val to = -Ui.px(scrollFor(if (grid) row - (shelfRows - 1) else 0) + lift)
            val top = if (grid) 0f else 1f
            if (animate && smooth) {
                layer.animate().translationY(to).setDuration(260).setInterpolator(EASE).start()
                clock.animate().alpha(top).setDuration(180).start()
                plate.animate().alpha(top).setDuration(200).start()
            } else {
                layer.translationY = to
                clock.alpha = top
                plate.alpha = top
            }
            pill.showTime = grid
            if (gridChanged || !animate || wasGrid != grid) host.inGrid(grid)
        }
    }

    /**
     * The name under tile [i]: on the plate only with every name shown; the focused app's alone
     * otherwise, the others a little dimmer when all are shown. It sits just under the grown tile
     * and its frame.
     */
    private fun placeName(i: Int, on: Boolean, ring: Boolean) {
        val n = names[i]
        val onPlate = hasPlate && i < COLS * shelfRows
        val show = (!onPlate || plateNames) && !hiddenBelow(i) && (on || LiteSettings.showAllNames) && !(on && moving)
        n.visibility = if (show) VISIBLE else INVISIBLE
        if (!show) return
        n.alpha = if (on) 1f else 0.7f
        var dy = TILE_H + 10f
        if (on) dy += TILE_H * (FOCUS_SCALE - 1) / 2 + if (onPlate || !ring) 0f else (RING_GAP + RING_WIDTH) * FOCUS_SCALE
        n.translationX = Ui.px(x(i) + TILE_W / 2) - n.layoutParams.width / 2f
        n.translationY = Ui.px(y(i) + dy)
    }

    fun onKey(e: KeyEvent): Boolean {
        val app = apps.getOrNull(focus)
        if (press.onKey(e, click = {
                when {
                    moving -> finishMove()
                    focus == PILL -> host.openControlCenter()
                    app?.folder != null -> host.openFolder(app)
                    app != null -> host.launch(app)
                }
            }, longPress = { if (focus != PILL && app != null && !moving) host.openMenu(app, tiles[focus]) })) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_UP) back()
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (e.action == KeyEvent.ACTION_UP && focus != PILL && app != null && !moving) host.openMenu(app, tiles[focus])
                return true
            }
            // The remote's gear key, which a stock launcher answers (the full app's issue #1190):
            // Control Center, where Settings is, behind the PIN when one is set.
            KeyEvent.KEYCODE_SETTINGS -> {
                if (e.action == KeyEvent.ACTION_UP && !moving) host.openControlCenter()
                return true
            }
        }
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            KeyEvent.KEYCODE_DPAD_UP -> -COLS
            KeyEvent.KEYCODE_DPAD_DOWN -> COLS
            else -> return false
        }
        if (e.action == KeyEvent.ACTION_DOWN) step(dir)
        return true
    }

    /**
     * A tap on a tile (a mouse or a touch screen) does what the full app's Pressable does: it takes
     * the focus there and opens the app, or its menu when held. Lite had no touch at all, and a
     * mouse only moved its pointer over Home (Mi TV 2, Android 4.4, issue #1094).
     */
    private fun tapped(i: Int, long: Boolean) {
        val app = apps.getOrNull(i) ?: return
        if (moving) { if (i == focus) finishMove(); return }
        focus = i
        showFocus(animate = true)
        when {
            long -> host.openMenu(app, tiles[i])
            app.folder != null -> host.openFolder(app)
            else -> host.launch(app)
        }
    }

    /** Back: in the grid, to the top row; on the top row, the screensaver (tvOS) if chosen. */
    private fun back() {
        when {
            moving -> finishMove()
            focus == PILL -> { focus = 0; showFocus(animate = true) }
            row > 0 -> { focus %= COLS; showFocus(animate = true) }
            LiteSettings.backStartsScreensaver -> host.startSaver()
        }
    }

    private fun step(dir: Int) {
        if (apps.isEmpty()) return
        if (focus == PILL) {
            if (dir == COLS) { focus = minOf(COLS - 1, apps.size - 1); showFocus(animate = true) }
            return
        }
        // Left and right stay in the row, as on tvOS.
        if ((dir == -1 && focus % COLS == 0) || (dir == 1 && (focus % COLS == COLS - 1 || focus == apps.size - 1))) return
        var to = focus + dir
        if (dir == -COLS && to < 0) {
            if (moving) return
            focus = PILL
            showFocus(animate = true)
            return
        }
        // Down onto a shorter last row lands on its last tile.
        if (dir == COLS && to >= apps.size) {
            if (focus / COLS == (apps.size - 1) / COLS) return
            to = apps.size - 1
        }
        if (moving) {
            val list = apps.toMutableList()
            tiles.add(to, tiles.removeAt(focus))
            names.add(to, names.removeAt(focus))
            list.add(to, list.removeAt(focus))
            apps = list
            focus = to
            place(animate = true)
        } else {
            focus = to
        }
        showFocus(animate = true)
    }

    fun startMove(app: AppEntry) {
        focus = apps.indexOfFirst { it.key == app.key }.takeIf { it >= 0 } ?: return
        moving = true
        beforeMove = apps.map { it.key }
        press.reset()
        showFocus(animate = true)
    }

    private fun finishMove() {
        moving = false
        val key = apps.getOrNull(focus)?.key
        val target = beforeMove.getOrNull(focus)
        showFocus(animate = true)
        if (key != null && target != null && key != target) host.moved(key, target)
    }

    /** The clock, redrawn once a minute on the minute and at no other time. */
    private val tick = object : Runnable {
        override fun run() {
            clock.refresh()
            pill.requestLayout()
            pill.invalidate()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }

    /** Starts the clock, or brings it up to date after the TV slept. */
    fun refreshClock() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    /**
     * The Liquid Glass plate under the top row (tvOS 26), the row's full width: the frosted
     * wallpaper behind it, tinted milky and cut to its outline with a lit edge, baked once.
     */
    private class PlateView(context: Context) : View(context) {
        var glass: Bitmap? = null
        private var baked: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val left = Ui.px(MARGIN - PLATE_PAD_H)
        private var top = 0f
        private var h = 0f

        /** Where the plate rests on screen, in canvas units: its glass comes from there. */
        fun place(top: Float, height: Float) {
            if (top == this.top && height == h) return
            this.top = top
            h = height
            rebake()
        }

        fun rebake() {
            val g = glass ?: run { baked = null; invalidate(); return }
            if (h <= 0f) return
            val at = RectF(left, Ui.px(top), Ui.px(MARGIN + (COLS - 1) * (TILE_W + GAP) + TILE_W + PLATE_PAD_H), Ui.px(top + h))
            baked = Shapes.glass(g, Ui.screenW, Ui.screenH, at, Ui.px(32f), Ui.px(1.2f), PLATE_TINT)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            baked?.let { canvas.drawBitmap(it, left, 0f, paint) }
        }
    }

    /**
     * One tile: its face (the picture cut to the continuous outline, made at focused size), and
     * when focused the full app's baked shadow under it and, in the High Contrast focus style, its
     * ring around it, all inside the View's margin so the focus scale carries them.
     */
    class TileView(context: Context) : View(context) {
        lateinit var entry: AppEntry
        var lifted = false
            set(v) { if (field != v) { field = v; invalidate() } }
        var moving = false
            set(v) { if (field != v) { field = v; invalidate() } }
        var ring = true
            set(v) { if (field != v) { field = v; invalidate() } }
        var shadow = true
            set(v) { if (field != v) { field = v; invalidate() } }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val dst = Rect()
        private val tmp = RectF()

        override fun onDraw(canvas: Canvas) {
            val ext = Ui.pxi(TILE_EXT)
            val w = width - 2 * ext
            val h = height - 2 * ext
            dst.set(ext, ext, ext + w, ext + h)
            if (lifted && shadow) {
                val s = Shapes.shadow(w, h, Ui.px(CORNER), Ui.px(14f))
                val drop = Ui.px(12f)
                tmp.set(ext - s.ext, ext - s.ext + drop, ext + w + s.ext, ext + h + s.ext + drop)
                canvas.drawBitmap(s.image, null, tmp, paint)
            }
            canvas.drawBitmap(entry.tile, null, dst, paint)
            // The yellow frame of a tile being moved shows in either focus style.
            if (lifted && (ring || moving)) {
                val r = Shapes.ring(w, h, Ui.px(CORNER), Ui.px(RING_GAP), Ui.px(RING_WIDTH), Ui.px(1f), if (moving) 0xFFFFD60A.toInt() else Ui.WHITE)
                tmp.set(ext - r.ext, ext - r.ext, ext + w + r.ext, ext + h + r.ext)
                canvas.drawBitmap(r.image, null, tmp, paint)
            }
        }
    }

    companion object {
        const val PILL = -1
        /** Apps a row, from Personalize; the sizes below follow it, as the full app's GridMetrics. */
        val COLS get() = LiteSettings.homeColumns
        const val MARGIN = 56f
        val GAP get() = when (COLS) { 4 -> 34f; 6 -> 24f; else -> 28f }
        val TILE_W get() = (960f - 2 * MARGIN - (COLS - 1) * GAP) / COLS
        val TILE_H get() = TILE_W * 9 / 16
        val PITCH get() = TILE_H + 50f
        const val FOCUS_SCALE = 1.15f
        const val CORNER = 12f
        const val RING_GAP = 5f
        const val RING_WIDTH = 3f
        /** Room around a tile for its shadow and ring. */
        const val TILE_EXT = 36f
        const val PLATE_PAD_H = 26f
        const val PLATE_PAD_V = 20f
        const val PLATE_MARGIN_BOTTOM = 30f
        /** The plate grows by this under the top row to hold every name. */
        const val PLATE_NAME_ROOM = 28f
        /** Top of the large clock: the date's capitals, just under the status pill. */
        const val CLOCK_Y = 80f
        /** The full app's ShelfRowGap, RowNameRoom and LargeClockHeight. */
        const val SHELF_ROW_GAP = 30f
        const val ROW_NAME_ROOM = 50f
        const val LARGE_CLOCK_H = 152f
        private val EASE = DecelerateInterpolator(1.6f)

        /** An app's name under its tile: Type.Headline, centred, with the full app's soft shadow. */
        fun nameView(context: Context) = TextView(context).apply {
            setTextColor(Ui.WHITE)
            textPx(15f)
            typeface = Ui.font(600)
            gravity = Gravity.CENTER_HORIZONTAL
            setSingleLine()
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setShadowLayer(Ui.px(4f), 0f, 0f, 0x99000000.toInt())
            visibility = INVISIBLE
        }
    }
}

/** The plate's tint: the frosted picture a little darker, lifted with white, so it reads as milky glass. The grid's background too. */
internal val PLATE_TINT = android.graphics.ColorMatrixColorFilter(floatArrayOf(
    0.8f, 0f, 0f, 0f, 30f,
    0f, 0.8f, 0f, 0f, 30f,
    0f, 0f, 0.8f, 0f, 30f,
    0f, 0f, 0f, 1f, 0f,
))
