package app.orchard.tv.lite

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.orchard.tv.lite.Ui.textPx
import java.net.NetworkInterface

/**
 * The full app's Liquid Glass for panels: the frosted wallpaper behind the panel, at half its light
 * so white text reads on snow (LiquidGlass's PanelTint), cut to the panel's outline with its lit
 * edge. Baked once the panel knows where it sits, so a panel is one image draw.
 */
class GlassBox(context: Context, private val radius: Float, private val bottomCorners: Boolean = true) : FrameLayout(context) {
    private var baked: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    /**
     * The stand-in's own paint: drawn with [paint], its 90% stayed in the paint's alpha and every
     * baked glass after it let Home show through (Personalize over the tiles, MiTV-MFTR0).
     */
    private val fill = Shapes.Tint()

    init { setWillNotDraw(false) }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || baked == null) post { bake() }
    }

    /**
     * Baked from where layout puts the panel, not where it is drawn this frame: a sheet sliding in
     * or a menu growing out of its tile is moved and scaled by View properties, and baked in
     * passing it took its glass from the wrong part of the wallpaper (off the screen: clear).
     */
    private fun bake() {
        val frost = Glass.frost ?: return invalidate()
        var x = 0f; var y = 0f
        var v: View? = this
        while (v != null) { x += v.left - v.scrollX; y += v.top - v.scrollY; v = v.parent as? View }
        val at = RectF(x, y, x + width, y + height)
        baked = Shapes.glass(frost, Ui.screenW, Ui.screenH, at, Ui.px(radius), Ui.px(1.2f), Glass.PANEL_TINT, bottomCorners)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = baked
        if (b != null) canvas.drawBitmap(b, 0f, 0f, paint)
        else if (width > 0 && height > 0) {
            val r = Ui.px(radius)
            fill.draw(canvas, Shapes.mask(width, height, r, if (bottomCorners) r else 0f), 0f, 0f, Ui.GLASS)
        }
    }
}

object Glass {
    /** The frosted wallpaper panels are made from; null in no wallpaper's light. */
    @Volatile var frost: Bitmap? = null

    val PANEL_TINT = ColorMatrixColorFilter(floatArrayOf(
        0.48f, 0f, 0f, 0f, 14f,
        0f, 0.48f, 0f, 0f, 14f,
        0f, 0f, 0.48f, 0f, 14f,
        0f, 0f, 0f, 1f, 0f,
    ))
}

/** A line of text in one of the full app's Type styles. */
fun Context.label(text: String, size: Float, weight: Int, color: Int = Ui.WHITE, display: Boolean = false, spacingEm: Float = 0f): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        textPx(size)
        typeface = Ui.font(weight, if (display) 32f else 14f)
        includeFontPadding = false
        setSingleLine()
        ellipsize = TextUtils.TruncateAt.END
        if (spacingEm != 0f && Build.VERSION.SDK_INT >= 21) letterSpacing = spacingEm
    }

private fun space(context: Context, h: Float) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, Ui.pxi(h)) }

private fun LinearLayout.add(v: View, w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, top: Float = 0f): View {
    addView(v, LinearLayout.LayoutParams(w, h).apply { topMargin = Ui.pxi(top) })
    return v
}

/** Control Center's pages, as the full app's CcPage: inputs only where Lite can list them, no updates. */
enum class CcPage { Main, Boot, Inputs }

/**
 * Control Center, as the full app's ControlCenter main page: the time and date with the network,
 * three round buttons (inputs, network, settings), volume and brightness, Personalize, what the TV
 * starts on, and whether Aerial is the TV's Home. Home stays sharp behind it under a dark veil.
 * The network and settings sit behind the parental PIN ([locked]).
 */
class ControlCenter(
    context: Context,
    close: () -> Unit,
    page: CcPage,
    private val open: (Intent) -> Unit,
    private val locked: (() -> Unit) -> Unit,
    private val personalize: () -> Unit,
    private val goTo: (CcPage) -> Unit,
    private val bootLabel: String?,
    private val clearBoot: () -> Unit,
    private val noInputs: () -> Unit,
    /** Kids and Elders mode: leaving it (behind the PIN), and back into it. */
    private val kidsLeave: () -> Unit = {},
    private val kidsBack: () -> Unit = {},
) : Sheet(context, close) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.pxi(16f), Ui.pxi(16f), Ui.pxi(16f), Ui.pxi(16f)) }

    init {
        // The veil over Home: the full app's 45% black.
        addView(View(context).apply { setBackgroundColor(0x73000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val panel = GlassBox(context, 24f)
        addView(panel, LayoutParams(Ui.pxi(300f), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.pxi(24f); rightMargin = Ui.pxi(32f)
        })
        panel.addView(col)
        when (page) {
            CcPage.Main -> if (Kids.active) kidsMain() else main()
            CcPage.Boot -> boot()
            CcPage.Inputs -> inputs()
        }
        // In Kids mode on leaving it, as the full app.
        focusFirst(if (page == CcPage.Main && Kids.active && OutputDevice.showsVolume(context)) 1 else 0)
    }

    private fun volumeRow(): SliderRow = SliderRow(
        context,
        glyph = { if (it <= 0f) Glyph.SpeakerMuted else Glyph.Speaker },
        read = { audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) },
        write = { audio.setStreamVolume(AudioManager.STREAM_MUSIC, it, AudioManager.FLAG_PLAY_SOUND) },
        shown = { v, _ -> if (v == 0) context.getString(R.string.cc_muted) else v.toString() },
    )

    /** Kids and Elders mode's page, as the full app's KidsPage: the time, volume, the day's time left, leaving. */
    private fun kidsMain() {
        col.add(context.label(timeText(context), 27.3f, 600, spacingEm = 0.05f))
        col.add(context.label(dateText(), 13f, 500, Ui.SECONDARY), top = 4f)
        kidsTimeLine(context)?.let { col.add(context.label(it, 13f, 500, Ui.SECONDARY), top = 6f) }
        // A box or stick with its volume fixed has no sound level of its own (OutputDevice, #1307).
        val volume = if (OutputDevice.showsVolume(context)) volumeRow() else null
        if (volume != null) col.add(volume, top = 12f)
        val leave = MenuRow(context, Glyph.Shield, context.getString(if (Kids.kind == KidsKind.Kids) R.string.cc_kids_leave else R.string.cc_elders_leave))
            .apply { action = kidsLeave }
        col.add(leave, top = if (volume != null) 6f else 12f)
        if (volume != null) row(volume)
        row(leave)
    }

    private fun main() {
        // The time (Type.SmallClock at LargeTitle × 1.05) and date (Callout); the network on the right.
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val times = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        times.addView(context.label(timeText(context), 27.3f, 600, spacingEm = 0.05f))
        times.add(context.label(dateText(), 13f, 500, Ui.SECONDARY), top = 4f)
        head.addView(times, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val (netGlyph, netName, netLevel, netVpn) = network(context)
        val net = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        net.addView(GlyphView(context, netGlyph, 14f, Ui.SECONDARY, netLevel))
        net.addView(context.label(netName, 11f, 500, Ui.SECONDARY).apply { maxWidth = Ui.pxi(110f) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(6f) })
        if (netVpn) net.addView(VpnBadgeView(context, 9f, Ui.SECONDARY),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(6f) })
        head.addView(net, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = Ui.pxi(2f) })
        col.add(head)

        // The round buttons, spread across the panel.
        val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val inputs = RoundButton(context, Glyph.Input, context.getString(R.string.cc_inputs)).apply {
            action = {
                if (SystemIntents.xiaomiInputs(context).isNotEmpty()) goTo(CcPage.Inputs)
                // The TV's own input list where its remote's key shows one (SystemIntents): the
                // panel goes away then, as it does for an app; another make's picker keeps it.
                else if (SystemIntents.openInputsPanel(context)) close()
                else if (!SystemIntents.openInputPicker(context)) noInputs()
            }
        }
        val netButton = RoundButton(context, netGlyph, context.getString(R.string.cc_network)).apply {
            level = netLevel
            action = {
                locked {
                    // The TV's own network page where its remote's key shows one (SystemIntents).
                    if (SystemIntents.openNetworkPanel(context)) close()
                    else open(SystemIntents.network(context, netGlyph == Glyph.Ethernet))
                }
            }
        }
        val settings = RoundButton(context, Glyph.Gear, context.getString(R.string.cc_settings)).apply {
            action = {
                locked {
                    if (!SystemIntents.openSettingsPanel(context)) {
                        val intent = SystemIntents.settings(context)
                        SystemIntents.noteSettingsOpened(intent)
                        open(intent)
                    }
                }
            }
        }
        for ((i, b) in listOf(inputs, netButton, settings).withIndex()) {
            if (i > 0) buttons.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            buttons.addView(b)
        }
        col.add(buttons, top = 12f)
        row(inputs, netButton, settings)

        // A box or stick has neither a panel nor, with its volume fixed, a sound level of its own (#1307).
        if (OutputDevice.showsVolume(context)) {
            val volume = volumeRow()
            col.add(volume, top = 12f)
            row(volume)
        }

        // Brightness where Lite can set the panel's (Brightness), else the TV's own picture settings.
        if (!OutputDevice.showsBrightness()) Unit
        else if (Brightness.level(context) != null) {
            val brightness = SliderRow(
                context,
                glyph = { Glyph.Sun },
                // In percent, five at a time, as the full app.
                read = { Math.round((Brightness.level(context) ?: 0f) * 100) to 100 },
                write = { to -> Brightness.adjust(context, if (to > Math.round((Brightness.level(context) ?: 0f) * 100)) 1 else -1) },
                shown = { v, _ -> v.toString() },
                step = 5,
            )
            col.add(brightness, top = 6f)
            row(brightness)
        } else {
            val brightness = MenuRow(context, Glyph.Sun, context.getString(R.string.cc_brightness), context.getString(R.string.cc_brightness_in_settings) + " ›")
                .apply { action = { if (Brightness.openPictureSettings(context)) close() } }
            col.add(brightness, top = 6f)
            row(brightness)
        }

        // A parent left Kids and Elders mode: one press back into it, no PIN.
        if (Kids.enabled && Kids.suspended) {
            val back = MenuRow(context, Glyph.Kids, context.getString(if (Kids.kind == KidsKind.Kids) R.string.cc_kids_back else R.string.cc_elders_back))
                .apply { action = kidsBack }
            col.add(back, top = 6f)
            row(back)
        }
        val pers = MenuRow(context, Glyph.Photo, context.getString(R.string.cc_personalize)).apply { action = personalize }
        col.add(pers, top = 6f)
        row(pers)
        // What the TV opens when switched on: easy to forget, and puzzling when it happens.
        bootLabel?.let { name ->
            val boot = MenuRow(context, Glyph.Power, context.getString(R.string.cc_boot), "$name ›").apply { action = { goTo(CcPage.Boot) } }
            col.addView(boot)
            row(boot)
        }

        // Whether Lite is the TV's Home, and the version.
        val status = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.pxi(14f), 0, Ui.pxi(14f), 0) }
        val home = isHome(context)
        status.addView(Dot(context, if (home) Ui.GREEN else 0xFFFF9F0A.toInt()), LinearLayout.LayoutParams(Ui.pxi(7f), Ui.pxi(7f)))
        status.addView(context.label(context.getString(if (home) R.string.cc_home_default else R.string.cc_home_not_default), 11f, 500, Ui.SECONDARY),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = Ui.pxi(8f) })
        status.addView(context.label("Aerial Lite " + version(context), 11f, 500, Ui.TERTIARY))
        col.add(status, top = 8f)
    }

    /** What the TV starts on: an app is chosen from its tile's menu; here it shows, and "nothing" clears it. */
    private fun boot() {
        val back = MenuRow(context, Glyph.ChevronLeft, context.getString(R.string.cc_boot)).apply { action = { goTo(CcPage.Main) } }
        col.addView(back)
        row(back)
        col.add(context.label(context.getString(R.string.cc_inputs_boot), 13f, 600).apply { setPadding(Ui.pxi(14f), 0, 0, 0) }, top = 10f)
        val none = MenuRow(context, if (bootLabel == null) Glyph.Check else Glyph.Power, context.getString(R.string.cc_inputs_boot_none)).apply { action = clearBoot }
        col.add(none, top = 6f)
        row(none)
        bootLabel?.let { name ->
            val app = MenuRow(context, Glyph.Check, name)
            col.addView(app)
            row(app)
        }
    }

    /** The inputs Lite can open itself (SystemIntents.xiaomiInputs), as the full app's list. */
    private fun inputs() {
        val back = MenuRow(context, Glyph.ChevronLeft, context.getString(R.string.cc_inputs)).apply { action = { goTo(CcPage.Main) } }
        col.addView(back)
        row(back)
        for ((label, intent) in SystemIntents.xiaomiInputs(context)) {
            val input = MenuRow(context, Glyph.Input, label).apply { action = { open(intent) } }
            col.add(input, top = 6f)
            row(input)
        }
    }

    companion object {
        /** The network's glyph, name and Wi-Fi level, and whether a VPN is up, as the full app's status pill shows them. */
        fun network(context: Context): NetStatus {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val info = runCatching { cm.activeNetworkInfo }.getOrNull()
            @Suppress("DEPRECATION")
            return when {
                info == null || !info.isConnected -> NetStatus(Glyph.Offline, context.getString(R.string.net_offline), 0f)
                info.type == ConnectivityManager.TYPE_ETHERNET -> NetStatus(Glyph.Ethernet, context.getString(R.string.net_ethernet), 1f, vpnUp(cm))
                else -> {
                    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    val conn = runCatching { wifi?.connectionInfo }.getOrNull()
                    val level = conn?.let { WifiManager.calculateSignalLevel(it.rssi, 3) / 2f } ?: 1f
                    val ssid = conn?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && !it.contains("unknown ssid") }
                    NetStatus(Glyph.Wifi, ssid ?: context.getString(R.string.net_wifi), level, vpnUp(cm))
                }
            }
        }

        /**
         * Before Android 5.0 a VPN is no network of its own to ConnectivityManager, only an interface:
         * tun for VpnService apps, ppp for the built-in PPTP/L2TP. From 5.0 also by its transport.
         */
        private fun vpnUp(cm: ConnectivityManager): Boolean {
            if (Build.VERSION.SDK_INT >= 21 && runCatching {
                    cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
                }.getOrDefault(false)) return true
            return runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { it.isUp && (it.name.startsWith("tun") || it.name.startsWith("ppp")) }
            }.getOrDefault(false)
        }

        fun isHome(context: Context): Boolean = runCatching {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == context.packageName
        }.getOrDefault(false)

        fun version(context: Context): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
}

data class NetStatus(val glyph: Glyph, val name: String, val level: Float, val vpn: Boolean = false)

/** A caption under a group or a list: tertiary, up to three lines. */
fun note(context: Context, text: String): TextView = TextView(context).apply {
    this.text = text
    setTextColor(Ui.TERTIARY); textPx(11f); typeface = Ui.font(500)
    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
    setLineSpacing(Ui.px(2f), 1f)
}

/** A glyph as a plain view. */
class GlyphView(context: Context, private val glyph: Glyph, private val size: Float, private val color: Int, private val level: Float = 1f) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(Ui.pxi(size), Ui.pxi(size))
    override fun onDraw(canvas: Canvas) = canvas.drawBitmap(Glyphs.image(glyph, Ui.pxi(size), color, level), 0f, 0f, paint)
}

private class Dot(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        paint.color = color
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, paint)
    }
}

/**
 * A tile's menu, as the full app's AppMenu: a glass card 250 wide, under the tile when the whole
 * menu fits below it, else above, the app's name over glyph rows.
 */
class TileMenu(context: Context, close: () -> Unit, title: String, anchor: RectF, rows: List<MenuRow>) : Sheet(context, close) {
    init {
        val w = Ui.px(250f)
        val panel = GlassBox(context, 20f)
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.pxi(8f), Ui.pxi(8f), Ui.pxi(8f), Ui.pxi(8f)) }
        col.addView(context.label(title, 15f, 600), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.pxi(14f); rightMargin = Ui.pxi(14f); topMargin = Ui.pxi(8f); bottomMargin = Ui.pxi(6f)
        })
        for (r in rows) { col.addView(r); row(r) }
        panel.addView(col)
        val x = anchor.left.coerceIn(Ui.px(24f), (Ui.screenW - w - Ui.px(24f)).coerceAtLeast(Ui.px(24f)))
        addView(panel, LayoutParams(w.toInt(), LayoutParams.WRAP_CONTENT).apply { leftMargin = x.toInt() })
        panel.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                val gap = Ui.px(18f)
                // Under the tile when the whole menu fits there, as the full app's AppMenu.
                val below = anchor.bottom + gap + panel.height <= Ui.screenH - Ui.px(24f)
                val y = if (below) anchor.bottom + gap else anchor.top - gap - panel.height
                // Placed by layout, so its glass is baked from where it stays.
                (panel.layoutParams as LayoutParams).topMargin = y.coerceAtLeast(Ui.px(24f)).toInt()
                panel.requestLayout()
                // The menu grows out of the tile, as in the full app.
                panel.pivotX = (anchor.centerX() - x).coerceIn(0f, w)
                panel.pivotY = if (below) 0f else panel.height.toFloat()
                if (panel.isHardwareAccelerated) {
                    panel.alpha = 0f; panel.scaleX = 0.92f; panel.scaleY = 0.92f
                    panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
                }
                // Not this frame: it would show the menu at the top for one frame before its place.
                return false
            }
        })
        focusFirst()
    }
}

/**
 * Personalize, as the full app's WallpaperSheet, row for row: a glass sheet from the bottom, the
 * wallpaper cards, then tvOS-style groups of setting rows (how Home is laid out, the clock, how the
 * remote feels, the screensaver, the weather, the parental PIN) with a note under each that
 * follows the focused row; on the right, the QR code of the phone page. Rows that only some
 * choices have come and go, so the rows are made again after every choice, focus kept in place.
 */
class Personalize(
    context: Context,
    close: () -> Unit,
    private val hiddenCount: Int,
    private val onWallpaper: (String) -> Unit,
    private val onLayout: () -> Unit,
    private val onHidden: () -> Unit,
    private val onHomeLock: (Boolean) -> Unit,
    private val onSaverNow: () -> Unit,
    private val onPin: (Boolean) -> Unit,
    private val onKids: () -> Unit = {},
) : Sheet(context, close) {
    private val col = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.pxi(12f), Ui.pxi(12f), Ui.pxi(12f), Ui.pxi(24f))
    }
    private var strip = LinearLayout(context)
    private var stripShift = 0f
    private val hints = HashMap<Control, Pair<String?, TextView>>()
    private val cardNotes = HashMap<Thumb, String?>()
    private var cardNote: TextView? = null
    private val qrView = android.widget.ImageView(context)
    private val phoneBody = TextView(context)
    private val phoneUrl = TextView(context)

    /** How much of the screen's bottom the sheet hides whole once in: its height less its round top corners. */
    val covers = Ui.pxi(360f - 30f)

    init {
        val sheetH = Ui.px(360f)
        val panel = GlassBox(context, 30f, bottomCorners = false)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, sheetH.toInt(), Gravity.BOTTOM))
        val inner = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            // 12 units of room around the scrolling column for a focused control's growth, taken
            // back by the column's padding so everything sits where the full app has it.
            leftMargin = Ui.pxi(40f - 12f); rightMargin = Ui.pxi(32f); topMargin = Ui.pxi(24f - 12f)
        })
        // The viewport clips the scrolling column.
        val viewportView = Viewport(context)
        inner.addView(viewportView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        viewportView.addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        scroller = col
        viewport = (sheetH - Ui.px(12f)).toInt()
        inner.addView(phoneCard(), LinearLayout.LayoutParams(Ui.pxi(220f), LinearLayout.LayoutParams.MATCH_PARENT).apply {
            leftMargin = Ui.pxi(28f - 12f); topMargin = Ui.pxi(12f + 6f)
        })

        onFocusMoved = { c ->
            hints[c]?.let { (hint, view) -> view.text = hint ?: view.tag as? String ?: "" }
            if (c is Thumb) {
                cardNote?.text = cardNotes[c] ?: ""
                if (c.width > 0) keepThumbInView(c) else c.post { keepThumbInView(c) }
            }
        }
        build()
        // Focus starts on the chosen wallpaper.
        focusFirst(0, cards().indexOfFirst { it.id == Wallpapers.selectedId }.coerceAtLeast(0))
        loadPhone()

        // It slides up, as the full app's sheet (the iOS sheet curve, near enough).
        panel.translationY = sheetH
        panel.post {
            if (LiteSettings.full(panel.isHardwareAccelerated)) panel.animate().translationY(0f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            else panel.translationY = 0f
        }
    }

    /** After a choice here or on the phone page: the rows again, focus where it was. */
    fun refreshChips() {
        val (r, c) = position
        build()
        focusFirst(r, c)
    }

    /** New daily photos or photos from the phone: their cards too. */
    fun wallpapersChanged() {
        synchronized(faces) { faces.remove(Wallpapers.LATEST_DAILY); faces.remove(Wallpapers.ROTATE_PHOTOS) }
        refreshChips()
    }

    // ---- the rows ----

    private fun build() {
        col.removeAllViews()
        clearRows()
        hints.clear()
        cardNotes.clear()

        col.add(context.label(context.getString(R.string.wp_title), 26f, 600, display = true, spacingEm = -0.015f))
        col.add(section(R.string.wp_wallpaper), top = 16f)
        wallpaperRow()
        // How often "My Photos, in turn" moves on (issue #1314); there whenever that card is.
        // Built once with the panel, so not only while it is the one chosen, as in the full app.
        if (Wallpapers.items.count { it.kind == WallpaperKind.Photo } > 1) group {
            choices(R.string.st_photo_every, string(R.string.st_photo_every_hint),
                LiteSettings.PHOTO_MINUTES.map { m ->
                    m to when {
                        m >= LiteSettings.DAY_MINUTES -> string(R.string.st_photo_every_day)
                        m >= 60 -> context.getString(R.string.kids_h, m / 60)
                        else -> context.getString(R.string.kids_m, m)
                    }
                },
                LiteSettings.photoMinutes) { LiteSettings.choosePhotoMinutes(it); onLayout() }
        }

        col.add(section(R.string.st_home_layout), top = 6f)
        group {
            choices(R.string.st_layout, string(R.string.st_home_layout_hint), listOf(true to R.string.st_home_one_row, false to R.string.st_home_standard),
                LiteSettings.oneRowHome) { LiteSettings.chooseOneRowHome(it); onLayout() }
            // Two rows show the second row whole; only one row has a peek to hide.
            if (LiteSettings.oneRowHome) choices(R.string.st_peek, string(R.string.st_peek_hint), listOf(true to R.string.st_peek_show, false to R.string.st_peek_hide),
                LiteSettings.peekNextRow) { LiteSettings.choosePeekNextRow(it); onLayout() }
            choices(R.string.st_columns, string(R.string.st_columns_hint), LiteSettings.HOME_COLUMNS.map { it to it.toString() },
                LiteSettings.homeColumns) { LiteSettings.chooseHomeColumns(it); onLayout() }
            choices(R.string.st_plate, string(R.string.st_plate_hint), listOf(true to R.string.st_plate_glass, false to R.string.st_plate_none),
                LiteSettings.homePlate) { LiteSettings.chooseHomePlate(it); onLayout() }
            choices(R.string.st_names, string(R.string.st_names_hint), listOf(false to R.string.st_names_focused, true to R.string.st_names_all),
                LiteSettings.showAllNames) { LiteSettings.chooseShowAllNames(it); onLayout() }
            val hidden = Chip(context, if (hiddenCount == 0) string(R.string.st_hidden_none) else context.getString(R.string.st_hidden_count, hiddenCount), { false }, Glyph.ChevronRight)
                .apply { action = onHidden }
            settingRow(R.string.st_hidden_apps, string(R.string.st_hidden_apps_hint), listOf(hidden))
            choices(R.string.st_home_lock, string(R.string.st_home_lock_hint), listOf(false to R.string.st_off, true to R.string.st_on),
                LiteSettings.homeLocked) { if (it != LiteSettings.homeLocked) onHomeLock(it) }
        }
        col.add(space(context, 10f))
        group {
            choices(R.string.st_clock, string(R.string.st_clock_hint), listOf(false to R.string.st_clock_left, true to R.string.st_clock_right),
                LiteSettings.clockRight) { LiteSettings.chooseClockRight(it); onLayout() }
            choices(R.string.st_clock_size, string(R.string.st_clock_size_hint), listOf(false to R.string.st_clock_small, true to R.string.st_clock_large),
                LiteSettings.largeClock) { LiteSettings.chooseLargeClock(it); onLayout() }
            choices(R.string.st_clock_hours, string(R.string.st_clock_hours_hint),
                listOf(ClockHours.System to R.string.st_clock_hours_system, ClockHours.H24 to R.string.st_clock_hours_24, ClockHours.H12 to R.string.st_clock_hours_12),
                LiteSettings.clockHours) { LiteSettings.chooseClockHours(it); onLayout() }
            choices(R.string.wp_weather, null, listOf(true to R.string.st_on, false to R.string.st_off),
                LiteSettings.showWeather) { LiteSettings.chooseShowWeather(it); onLayout() }
        }
        col.add(space(context, 10f))
        group {
            choices(R.string.st_focus_style, string(if (LiteSettings.focusStyle == FocusStyle.HighContrast) R.string.st_focus_high_hint else R.string.st_focus_standard_hint),
                listOf(FocusStyle.HighContrast to R.string.st_focus_high, FocusStyle.Standard to R.string.st_focus_standard),
                LiteSettings.focusStyle) { LiteSettings.chooseFocusStyle(it); onLayout() }
            choices(R.string.st_sounds, string(if (LiteSettings.uiSounds == UiSounds.Tv) R.string.st_sounds_tv_hint else R.string.st_sounds_hint),
                listOf(UiSounds.Tv to R.string.st_sounds_tv, UiSounds.Aerial to R.string.st_sounds_aerial, UiSounds.Off to R.string.st_off),
                LiteSettings.uiSounds) { LiteSettings.chooseUiSounds(it); Sounds.prepare() }
            choices(R.string.st_effects,
                string(if (LiteSettings.effects == Effects.Auto && !(context as android.app.Activity).window.decorView.isHardwareAccelerated) R.string.st_effects_auto_lite_hint else R.string.st_effects_hint),
                listOf(Effects.Auto to R.string.st_effects_auto, Effects.Full to R.string.st_effects_full, Effects.Lite to R.string.st_effects_lite),
                LiteSettings.effects) { LiteSettings.chooseEffects(it); onLayout() }
        }

        col.add(section(R.string.wp_screensaver), top = 22f)
        val mode = Wallpapers.screensaver
        group {
            val kinds = listOf(
                ScreensaverMode.Aerial to R.string.ss_aerial,
                ScreensaverMode.Photos to R.string.ss_photos,
                ScreensaverMode.Clock to R.string.ss_clock,
                ScreensaverMode.Videos to R.string.ss_videos,
            ).map { (m, label) -> Chip(context, string(label), { Wallpapers.screensaver == m }).apply { action = { Wallpapers.chooseScreensaver(m); refreshChips() } } }
            val start = Chip(context, string(R.string.ss_start), { false }, Glyph.Play).apply { action = onSaverNow }
            settingRow(R.string.ss_kind, when {
                mode == ScreensaverMode.Videos -> string(R.string.ss_videos_hint)
                Wallpapers.screensaverFollows -> string(R.string.ss_follow_hint)
                LiteSettings.backStartsScreensaver -> string(R.string.ss_back_hint)
                else -> null
            }, kinds + start)
            // The clock screensaver is its clock, and the others show none.
            if (mode == ScreensaverMode.Aerial || mode == ScreensaverMode.Photos) choices(R.string.ss_show_time, string(R.string.ss_show_time_hint),
                listOf(true to R.string.st_on, false to R.string.st_off), LiteSettings.screensaverClock) { LiteSettings.chooseScreensaverClock(it) }
            choices(R.string.ss_delay, null, LiteSettings.screensaverChoices.map { it to if (it == LiteSettings.NEVER) string(R.string.ss_never) else context.getString(R.string.ss_minutes, it) },
                LiteSettings.screensaverMinutes) { LiteSettings.chooseScreensaverMinutes(it) }
            choices(R.string.ss_back, string(R.string.ss_back_choice_hint), listOf(true to R.string.ss_back_on, false to R.string.ss_back_off),
                LiteSettings.backStartsScreensaver) { LiteSettings.chooseBackStartsScreensaver(it) }
        }

        col.add(section(R.string.wp_weather), top = 22f)
        group(string(if (Weather.chosen) R.string.wp_weather_chosen else R.string.wp_weather_guessed)) {
            settingRow(R.string.wp_weather_city, null, emptyList(), value = weatherValue())
        }
        col.add(space(context, 10f))
        // The last row: one row says what it is; a title over it would only repeat it.
        group(string(R.string.st_pin_hint)) {
            choices(R.string.st_pin, null, listOf(false to R.string.st_pin_off, true to R.string.st_pin_on), LiteSettings.pinSet) { on ->
                if (LiteSettings.pinSet != on) onPin(on)
            }
            val kids = Chip(context, string(when {
                !Kids.enabled -> R.string.st_off
                Kids.kind == KidsKind.Kids -> R.string.kids_kind_kids
                else -> R.string.kids_kind_elders
            }), { false }, Glyph.ChevronRight).apply { action = onKids }
            settingRow(R.string.st_kids, string(R.string.st_kids_hint), listOf(kids))
        }
    }

    private fun string(id: Int) = context.getString(id)

    private fun section(title: Int) = context.label(context.getString(title), 15f, 600).apply { setPadding(Ui.pxi(4f), 0, 0, Ui.pxi(10f)) }

    /** The rows of one group being made: the card they go in, and its note. */
    private var card: LinearLayout? = null
    private var cardNoteView: TextView? = null
    private var firstInCard = true

    /**
     * A card of setting rows, as in tvOS Settings (white at 5%, corners 16), with one note under it
     * that follows the focused row, or says [note] when that row has none.
     */
    private fun group(note: String? = null, rows: () -> Unit) {
        val box = object : LinearLayout(context) {
            private val tint = Shapes.Tint()
            init { orientation = VERTICAL; setWillNotDraw(false) }
            override fun onDraw(canvas: Canvas) {
                if (width > 0 && height > 0) tint.draw(canvas, Shapes.mask(width, height, Ui.px(16f)), 0f, 0f, 0x0DFFFFFF)
            }
        }
        val noteView = note(context, note ?: "").apply {
            maxLines = 2
            tag = note
            setPadding(Ui.pxi(16f), Ui.pxi(7f), Ui.pxi(16f), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(38f))
        }
        card = box; cardNoteView = noteView; firstInCard = true
        rows()
        col.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        col.addView(noteView)
        card = null
    }

    /**
     * One row: the setting's name on the left, its chips (or a [value]) on the right, a hairline
     * above all but the first. Its chips make one row of the remote's focus.
     */
    private fun settingRow(label: Int, hint: String?, controls: List<Control>, value: View? = null) {
        val box = card ?: return
        if (!firstInCard) box.addView(View(context).apply { setBackgroundColor(Ui.HAIRLINE) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = Ui.pxi(18f) })
        firstInCard = false
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.pxi(18f), 0, Ui.pxi(10f), 0)
        }
        line.addView(context.label(context.getString(label), 14f, 500), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        for (c in controls) {
            line.addView(c, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = Ui.pxi(10f) })
            hints[c] = hint to cardNoteView!!
        }
        value?.let { line.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.pxi(8f) }) }
        box.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(58f)))
        if (controls.isNotEmpty()) row(*controls.toTypedArray())
    }

    /** A row of chips for one of a few values; [labels] are string ids or the text itself. */
    private fun <T> choices(label: Int, hint: String?, labels: List<Pair<T, Any>>, current: T, choose: (T) -> Unit) {
        val chips = labels.map { (v, text) ->
            Chip(context, if (text is Int) string(text) else text.toString(), { current == v }).apply {
                action = { if (current != v) { choose(v); refreshChips() } }
            }
        }
        settingRow(label, hint, chips)
    }

    /** Where the weather is for and what it is now; the city is set on the phone page. */
    private fun weatherValue(): TextView {
        val w = Weather.now
        val text = if (w != null) {
            val kind = string(when (w.kind) {
                Weather.Kind.Clear -> R.string.wx_clear
                Weather.Kind.PartlyCloudy -> R.string.wx_partly_cloudy
                Weather.Kind.Cloudy -> R.string.wx_cloudy
                Weather.Kind.Fog -> R.string.wx_fog
                Weather.Kind.Rain -> R.string.wx_rain
                Weather.Kind.Snow -> R.string.wx_snow
                Weather.Kind.Thunder -> R.string.wx_thunder
            })
            listOfNotNull(Weather.place, context.getString(R.string.weather_temperature, w.celsius) + " $kind").joinToString(" · ")
        } else string(R.string.wp_weather_none)
        return context.label(text, 14f, 500, Ui.SECONDARY)
    }

    // ---- wallpaper cards ----

    /**
     * Every wallpaper in one row, in the full app's order: Bing's photo of the day, the owner's
     * photos (with "every day another" from two up), a few of the built-in pictures. Past daily
     * photos and the other built-ins only when one of them is the current choice.
     */
    private fun cards(): List<Wallpaper> {
        val items = Wallpapers.items
        val selected = Wallpapers.selectedId
        return buildList {
            if (items.any { it.kind == WallpaperKind.Daily }) add(Wallpaper(Wallpapers.LATEST_DAILY, WallpaperKind.Daily, string(R.string.wp_daily_latest)))
            addAll(items.filter { it.kind == WallpaperKind.Daily && it.id == selected })
            val photos = items.filter { it.kind == WallpaperKind.Photo }
            if (photos.size > 1) add(Wallpaper(Wallpapers.ROTATE_PHOTOS, WallpaperKind.Photo, string(R.string.wp_photos_rotate)))
            addAll(photos)
            addAll(items.filter { it.kind == WallpaperKind.Builtin && (it.id in FEATURED || it.id == selected) })
        }
    }

    private fun wallpaperRow() {
        // Wider than the sheet: as many cards as there are, moved along by translation.
        val clip = Viewport(context, horizontal = true)
        strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; translationX = stripShift }
        clip.addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        col.add(clip)
        val thumbs = ArrayList<Thumb>()
        for (wp in cards()) {
            val t = Thumb(context, wp.title) { Wallpapers.selectedId == wp.id }
            t.action = {
                Wallpapers.select(wp.id)
                for (other in thumbs) other.invalidate()
                onWallpaper(wp.id)
            }
            synchronized(faces) { faces[wp.id] }?.let { t.picture = it }
            strip.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = Ui.pxi(18f) })
            thumbs += t
            cardNotes[t] = when {
                wp.id == Wallpapers.LATEST_DAILY -> string(R.string.wp_daily_note)
                wp.id == Wallpapers.ROTATE_PHOTOS -> string(R.string.wp_photos_rotate_note)
                wp.kind == WallpaperKind.Photo -> string(R.string.wp_photo_note)
                wp.kind == WallpaperKind.Daily -> wp.caption
                else -> null
            }
        }
        row(*thumbs.toTypedArray())
        cardNote = note(context, "").apply {
            maxLines = 2
            setPadding(Ui.pxi(4f), Ui.pxi(8f), 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.pxi(42f))
        }
        col.addView(cardNote)
        renderThumbs(thumbs.zip(cards()).filter { it.first.picture == null })
    }

    private fun keepThumbInView(t: Thumb) {
        val room = (strip.parent as View).width
        val left = t.left + strip.translationX
        val to = when {
            left < 0 -> -t.left.toFloat()
            left + t.width > room -> (room - t.right).toFloat()
            else -> strip.translationX
        }.coerceAtMost(0f)
        stripShift = to
        if (LiteSettings.full(isHardwareAccelerated)) strip.animate().translationX(to).setDuration(200).start() else strip.translationX = to
    }

    /** The cards' pictures, made small in the background with their corners cut, and kept. */
    private fun renderThumbs(todo: List<Pair<Thumb, Wallpaper>>) {
        if (todo.isEmpty()) return
        val w = Ui.pxi(168f); val h = Ui.pxi(94f)
        Thread {
            for ((t, wp) in todo) {
                val pic = runCatching { Wallpapers.thumbnail(wp.id) }.getOrNull() ?: continue
                val face = Shapes.face(pic, w, h, Ui.px(12f), 0f)
                synchronized(faces) { faces[wp.id] = face }
                t.post { t.picture = face }
            }
        }.start()
    }

    // ---- the phone card ----

    /** The phone page's QR code, as the full app's PhoneCard: a white card, a title, how to use it, the address. */
    private fun phoneCard(): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val white = object : FrameLayout(context) {
            private val tint = Shapes.Tint()
            init { setWillNotDraw(false); setPadding(Ui.pxi(14f), Ui.pxi(14f), Ui.pxi(14f), Ui.pxi(14f)) }
            override fun onDraw(canvas: Canvas) {
                if (width > 0 && height > 0) tint.draw(canvas, Shapes.mask(width, height, Ui.px(18f)), 0f, 0f, Ui.WHITE)
            }
        }
        qrView.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        white.addView(qrView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        box.addView(white, LinearLayout.LayoutParams(Ui.pxi(176f), Ui.pxi(176f)))
        // Two lines where the title is long (English in Roboto, before Android 8 has Inter).
        val title = context.label(string(R.string.wp_phone_title), 15f, 600).apply {
            setSingleLine(false); maxLines = 2; gravity = Gravity.CENTER_HORIZONTAL
        }
        box.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(14f) })
        phoneBody.apply {
            setTextColor(Ui.WHITE); textPx(11f); typeface = Ui.font(500); gravity = Gravity.CENTER_HORIZONTAL
            includeFontPadding = false; setLineSpacing(Ui.px(2f), 1f)
            text = string(R.string.wp_phone_body)
        }
        box.addView(phoneBody, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(4f) })
        phoneUrl.apply { setTextColor(Ui.SECONDARY); textPx(11f); typeface = Ui.font(500); gravity = Gravity.CENTER_HORIZONTAL; setSingleLine() }
        box.addView(phoneUrl, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.pxi(6f) })
        return box
    }

    /** The address is read again each time: the TV's may have changed. */
    private fun loadPhone() {
        Thread {
            val url = PhoneServer.address(context)
            val qr = url?.let { runCatching { PhoneServer.qr(it) }.getOrNull() }
            post {
                if (url == null) {
                    phoneBody.text = string(R.string.wp_phone_offline)
                    qrView.setImageBitmap(Glyphs.image(Glyph.Offline, Ui.pxi(40f), 0x59000000))
                    qrView.scaleType = android.widget.ImageView.ScaleType.CENTER
                } else {
                    // Sharp modules: no filtering when the code is scaled to the card.
                    qrView.setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, qr).apply { setFilterBitmap(false) })
                    phoneUrl.text = url.removePrefix("http://")
                }
            }
        }.start()
    }

    companion object {
        /** The full app's Wallpapers.FEATURED_BUILTINS. */
        private val FEATURED = setOf("grad:hills_dawn", "grad:hills_night", "grad:dunes_gold", "grad:sea_sunset", "grad:waves_blue", "grad:aurora")
        /** Cards' pictures by wallpaper, kept while Lite runs. */
        private val faces = HashMap<String, Bitmap>()
    }
}

/** Hidden apps, as a glass card like Control Center: pick one to put it back on Home. */
class HiddenApps(context: Context, close: () -> Unit, apps: List<AppEntry>, unhide: (AppEntry) -> Unit) : Sheet(context, close) {
    init {
        addView(View(context).apply { setBackgroundColor(0x73000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val panel = GlassBox(context, 24f)
        addView(panel, LayoutParams(Ui.pxi(320f), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = Ui.pxi(24f); rightMargin = Ui.pxi(32f)
        })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.pxi(8f), Ui.pxi(16f), Ui.pxi(8f), Ui.pxi(12f)) }
        panel.addView(col)
        col.addView(context.label(context.getString(R.string.hidden_title), 19f, 600, display = true), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = Ui.pxi(14f)
        })
        col.addView(context.label(context.getString(if (apps.isEmpty()) R.string.hidden_empty else R.string.hidden_hint), 13f, 500, Ui.SECONDARY),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Ui.pxi(14f); topMargin = Ui.pxi(6f); bottomMargin = Ui.pxi(8f)
            })
        for (app in apps.take(9)) {
            val r = MenuRow(context, Glyph.EyeSlash, app.label).apply { action = { unhide(app) } }
            col.addView(r)
            row(r)
        }
        focusFirst()
    }
}
