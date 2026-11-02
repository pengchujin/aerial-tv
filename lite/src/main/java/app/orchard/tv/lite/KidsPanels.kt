package app.orchard.tv.lite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** "1 h 30 min" in the interface's language, as the full app's durationText. */
fun durationText(context: Context, ms: Long): String {
    val minutes = ((ms + 30_000L) / 60_000L).toInt().coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> context.getString(R.string.kids_m, m)
        m == 0 -> context.getString(R.string.kids_h, h)
        else -> context.getString(R.string.kids_hm, h, m)
    }
}

private fun clockText(ms: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return "%d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
}

/** Control Center's line about the day, as the full app's kidsTimeLine. */
fun kidsTimeLine(context: Context): String? {
    if (Kids.kind == KidsKind.Elders && !Kids.rules.any) return null
    val now = System.currentTimeMillis()
    val usage = Kids.usage
    if (usage.allowUntil > now) return context.getString(R.string.cc_kids_allowed_until, clockText(usage.allowUntil))
    val limit = Kids.limitToday(now)
    if (limit == 0) return context.getString(R.string.cc_kids_no_limit, durationText(context, usage.usedMs))
    return context.getString(R.string.cc_kids_left, durationText(context, (limit * 60_000L - usage.usedMs).coerceAtLeast(0)))
}

private val DAY_MINUTES = listOf(0, 30, 60, 90, 120)
private val END_MINUTES = listOf(0, 20 * 60, 21 * 60, 22 * 60)
private val BREAKS = listOf(0, 30, 45, 60)

/** As the full app: the few values here, and the phone page's after them when it is none of them. */
private fun withCustom(presets: List<Int>, current: Int) = if (current in presets) presets else presets + current

/**
 * Setting up Kids and Elders mode, as the full app's KidsSetupSheet but in one column, Lite's way:
 * who it is for and the limits as rows of chips, then Start, then every app as a row to pick (a
 * number says its place on the mode's Home).
 */
class KidsSetup(
    context: Context,
    close: () -> Unit,
    private val candidates: List<AppEntry>,
    private val onStart: () -> Unit,
) : Sheet(context, close) {
    private val col = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.pxi(12f), Ui.pxi(12f), Ui.pxi(12f), Ui.pxi(24f))
    }
    private val note = note(context, "")
    // Before init, which builds the rows.
    private val hints = HashMap<Control, String?>()

    init {
        addView(View(context).apply { setBackgroundColor(0x73000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val panelH = Ui.px(500f)
        val panel = GlassBox(context, 28f)
        addView(panel, LayoutParams(Ui.pxi(600f), panelH.toInt(), Gravity.CENTER))
        val viewportView = Viewport(context)
        panel.addView(viewportView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = Ui.pxi(28f - 12f); rightMargin = Ui.pxi(28f - 12f); topMargin = Ui.pxi(24f - 12f)
        })
        viewportView.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroller = col
        viewport = (panelH - Ui.px(12f)).toInt()
        onFocusMoved = { c -> note.text = hints[c] ?: "" }
        build()
        focusFirst()
    }

    private fun rebuild() {
        val (r, c) = position
        build()
        focusFirst(r, c)
    }

    private fun string(id: Int) = context.getString(id)

    private fun build() {
        col.removeAllViews()
        clearRows()
        hints.clear()
        val kids = Kids.kind == KidsKind.Kids
        val rules = Kids.rules
        col.addView(context.label(string(R.string.kids_title), 26f, 600, display = true, spacingEm = -0.015f))
        col.addView(context.label(string(R.string.kids_hint), 13f, 500, Ui.SECONDARY).apply { setSingleLine(false); maxLines = 3 },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(4f); bottomMargin = Ui.pxi(12f) })

        choices(R.string.kids_kind, string(if (kids) R.string.kids_kind_kids_hint else R.string.kids_kind_elders_hint),
            listOf(KidsKind.Kids to string(R.string.kids_kind_kids), KidsKind.Elders to string(R.string.kids_kind_elders)), Kids.kind) {
            // As in the full app: children have other apps kept away, older people let through.
            Kids.chooseKind(it); Kids.chooseBlockOthers(it == KidsKind.Kids)
        }
        val blockHint = if (android.os.Build.VERSION.SDK_INT < 21) R.string.kids_block_hint else R.string.kids_block_no_guard
        choices(R.string.kids_block, string(blockHint),
            listOf(true to string(R.string.kids_block_on), false to string(R.string.kids_block_off)), Kids.blockOthers) { Kids.chooseBlockOthers(it) }
        val minutesHint = string(R.string.kids_minutes_hint)
        choices(R.string.kids_weekday, minutesHint, withCustom(DAY_MINUTES, rules.weekdayMinutes).map { it to if (it == 0) string(R.string.kids_no_limit) else it.toString() }, rules.weekdayMinutes) {
            Kids.chooseRules(Kids.rules.copy(weekdayMinutes = it))
        }
        choices(R.string.kids_weekend, minutesHint, withCustom(DAY_MINUTES, rules.weekendMinutes).map { it to if (it == 0) string(R.string.kids_no_limit) else it.toString() }, rules.weekendMinutes) {
            Kids.chooseRules(Kids.rules.copy(weekendMinutes = it))
        }
        choices(R.string.kids_latest, context.getString(R.string.kids_latest_hint, KidsTime.clock(rules.startMinute)),
            withCustom(END_MINUTES, rules.endMinute).map { it to if (it == 0) string(R.string.kids_no_limit) else KidsTime.clock(it) }, rules.endMinute) {
            val next = Kids.rules.copy(endMinute = it)
            Kids.chooseRules(if (next.valid) next else next.copy(startMinute = KidsTime.DEFAULT_START))
        }
        choices(R.string.kids_break, context.getString(R.string.kids_break_hint, rules.breakMinutes),
            withCustom(BREAKS, rules.breakEvery).map { it to if (it == 0) string(R.string.st_off) else it.toString() }, rules.breakEvery) {
            Kids.chooseRules(Kids.rules.copy(breakEvery = it))
        }
        note.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(34f))
        note.setPadding(Ui.pxi(16f), Ui.pxi(6f), Ui.pxi(16f), 0)
        col.addView(note)

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val start = Chip(context, string(if (kids) R.string.kids_start_kids else R.string.kids_start_elders), { false }, Glyph.Kids).apply {
            action = { if (Kids.allowed.isNotEmpty()) onStart() }
        }
        actions.addView(start)
        val controls = arrayListOf<Control>(start)
        hints[start] = if (Kids.allowed.isEmpty()) string(if (candidates.isEmpty()) R.string.kids_loading else R.string.kids_pick_first) else null
        if (Kids.enabled && Kids.allowed.isNotEmpty()) {
            val off = Chip(context, string(R.string.kids_turn_off), { false }).apply { action = { Kids.chooseEnabled(false); rebuild() } }
            actions.addView(off, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(10f) })
            controls += off
        }
        col.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(4f) })
        row(*controls.toTypedArray())

        col.addView(context.label(context.getString(R.string.kids_apps, Kids.allowed.size), 15f, 600),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(22f); leftMargin = Ui.pxi(4f) })
        col.addView(note(context, string(R.string.kids_apps_hint)),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(4f); bottomMargin = Ui.pxi(8f); leftMargin = Ui.pxi(4f) })
        for (app in candidates) {
            val place = Kids.allowed.indexOf(app.key)
            val r = MenuRow(context, if (place >= 0) Glyph.Check else Glyph.EyeSlash, app.label, if (place >= 0) "${place + 1}" else null).apply {
                action = { Kids.toggleAllowed(app.key); rebuild() }
            }
            col.addView(r)
            row(r)
        }
    }

    /** A setting's row: its name, then one chip per value. */
    private fun <T> choices(label: Int, hint: String?, values: List<Pair<T, String>>, current: T, choose: (T) -> Unit) {
        val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.pxi(6f), 0, 0, 0) }
        line.addView(context.label(string(label), 14f, 500), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chips = values.map { (v, text) ->
            Chip(context, text, { current == v }).apply { action = { if (current != v) { choose(v); rebuild() } } }.also {
                line.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(6f) })
                hints[it] = hint
            }
        }
        col.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(50f)))
        row(*chips.toTypedArray())
    }
}

/**
 * Over Home while Kids mode is locked, as the full app's KidsLockScreen: why, and for a parent
 * (after the PIN) more time or leaving the mode. Back does nothing here.
 */
class KidsLockSheet(
    context: Context,
    val lock: KidsLock,
    private val onParent: (onRight: () -> Unit) -> Unit,
    private val onLeave: () -> Unit,
) : Sheet(context, {}) {
    private val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val sub: TextView = context.label("", 14f, 500, Ui.SECONDARY)
    private var granting = false
    private val handler = Handler(Looper.getMainLooper())
    private val countdown = object : Runnable {
        override fun run() {
            showSub()
            if (lock is KidsLock.Break) handler.postDelayed(this, 1_000)
        }
    }

    init {
        addView(View(context).apply { setBackgroundColor(0xDB000000.toInt()) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(col, LayoutParams(Ui.pxi(640f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        build()
        countdown.run()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun showSub() {
        sub.text = when (lock) {
            KidsLock.DayUsed -> context.getString(R.string.kids_lock_day_sub, durationText(context, Kids.usage.usedMs))
            KidsLock.Late -> context.getString(R.string.kids_lock_late_sub, KidsTime.clock(Kids.rules.startMinute), KidsTime.clock(Kids.rules.endMinute))
            is KidsLock.Break -> {
                val left = ((lock.untilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                context.getString(R.string.kids_lock_break_sub, "%d:%02d".format(left / 60, left % 60))
            }
        }
    }

    private fun build() {
        col.removeAllViews()
        clearRows()
        col.addView(GlyphView(context, if (lock is KidsLock.Break) Glyph.Sun else Glyph.Moon, 44f, Ui.WHITE))
        val title = when (lock) {
            KidsLock.DayUsed -> R.string.kids_lock_day
            KidsLock.Late -> R.string.kids_lock_late
            is KidsLock.Break -> R.string.kids_lock_break
        }
        col.addView(context.label(context.getString(title), 26f, 600, display = true),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(18f) })
        (sub.parent as? LinearLayout)?.removeView(sub)
        col.addView(sub, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(8f) })
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<Control>()
        fun chip(text: String, glyph: Glyph? = null, action: () -> Unit) {
            val c = Chip(context, text, { false }, glyph).apply { this.action = action }
            buttons.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (chips.isNotEmpty()) leftMargin = Ui.pxi(10f) })
            chips += c
        }
        if (!granting) {
            chip(context.getString(R.string.kids_parent), Glyph.Shield) { onParent { granting = true; build(); focusFirst() } }
            col.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(30f) })
            row(*chips.toTypedArray())
        } else {
            col.addView(context.label(context.getString(R.string.kids_grant_title), 15f, 600),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(26f) })
            chip(context.getString(R.string.kids_grant, 15)) { Kids.allow(15) }
            chip(context.getString(R.string.kids_grant, 30)) { Kids.allow(30) }
            chip(context.getString(R.string.kids_grant, 60)) { Kids.allow(60) }
            chip(context.getString(R.string.kids_grant_day)) { Kids.allow(null) }
            col.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(12f) })
            row(*chips.toTypedArray())
            val leave = Chip(context, context.getString(if (Kids.kind == KidsKind.Kids) R.string.cc_kids_leave else R.string.cc_elders_leave), { false }).apply { action = onLeave }
            col.addView(leave, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(10f) })
            row(leave)
        }
        focusFirst()
    }

    /** Nothing but the buttons: Back stays here. */
    override fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.onKey(e)
    }
}
