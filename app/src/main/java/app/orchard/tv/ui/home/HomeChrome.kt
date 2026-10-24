package app.orchard.tv.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.graphics.PorterDuff
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.Images
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.frozen
import app.orchard.tv.ui.components.glassEdge
import app.orchard.tv.ui.components.smoothMask
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import app.orchard.tv.weather.WeatherNow
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Tick on the minute and refresh immediately after waking or a system clock correction. */
@Composable
fun rememberNow(): State<LocalDateTime> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    DisposableEffect(context, lifecycle) {
        fun refresh() { now.value = LocalDateTime.now() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        var registered = false
        fun stop() {
            if (registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                    registered = true
                    refresh()
                }
                Lifecycle.Event.ON_RESUME -> refresh()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        // A minute-long coroutine delay survives standby with its old value on Hisense VIDAA.
        // Adding the observer also dispatches ON_START/ON_RESUME if already in the foreground.
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    return now
}

@Composable
fun timeText(now: LocalDateTime): String {
    val context = LocalContext.current
    // 24-hour unless the owner chose 12-hour. With the setting unset, Android falls back to the
    // locale, and zh_CN means 12-hour, which TV clocks here never show (TCL 65S515D, issue #15).
    val twelve = runCatching { android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24) }.getOrNull() == "12"
    val pattern = if (twelve) "h:mm" else "H:mm"
    return now.format(DateTimeFormatter.ofPattern(pattern))
}

@Composable
fun dateText(now: LocalDateTime): String {
    val locale = Locale.getDefault()
    // zh's best pattern runs the weekday into the date ("M月d日EEEE"); a space keeps them apart.
    val pattern = remember(locale) {
        DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE")
            .replace(Regex("(?<=[^\\s,，'])EEEE"), " EEEE")
            .replace(Regex("EEEE(?=[^\\s,，'])"), "EEEE ")
    }
    // Android's SimpleDateFormat (ICU), not java.time: through desugaring, java.time gave the month
    // its stand-alone form, "23 октябрь" in Russian where a date needs "23 октября".
    val millis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}

/**
 * The home background, in two states as on tvOS 26. Focus in the top row: the picture itself, with
 * a plate of Liquid Glass under the row ([plate], in px at scroll 0). Focus down in the grid: the
 * whole screen turns to the picture frosted and darker, so the rows read over anything.
 *
 * Everything follows [scroll] (the grid's scroll, [collapse] being where the grid has fully come
 * in), so the change is exactly as fast as the movement that causes it. Nothing is blurred live:
 * the frosted copy is baked small ahead of time (Images.frost) and darkened here once, and the
 * plate shows the part of that frosted copy that lies under it, stretched over the screen through
 * a shader. At rest a frame is one full-screen draw (plus the plate in the top row); both pictures
 * are drawn only through the middle of the scroll, since each frame that draws both costs a second
 * full-screen fill (Xiaomi MiTV-MFTR0: 90th percentile 20 -> 30 ms when the crossfade ran end to end).
 */
@Composable
fun HomeBackground(
    backdrop: ImageBitmap?,
    frosted: ImageBitmap?,
    plate: Rect?,
    scroll: () -> Float,
    collapse: Float,
    /** How far the aerial wallpaper's film shows through (LiveWallpaper): picture and plate give way to it. */
    cover: () -> Float = { 0f },
    /**
     * How far the film is stopped (0 to 1) with its glass ready ([frosted]) but its frame not yet
     * the picture, which takes a moment more where the frame is decoded again (Xiaomi
     * MiTV-MFTR0). Meanwhile the plate stands over the paused film, so the top row is back on
     * its glass with the rest of Home, the same plate it keeps once the picture is in.
     */
    filmStopped: () -> Float = { 0f },
    /** How far Home has given way to the screensaver carrying its wallpaper on: the plate goes. */
    toSaver: () -> Float = { 0f },
    /** The wallpaper's picture unshaded, shown [saverReveal] of the way and [saverPush] into its draw-in. */
    saverImage: ImageBitmap? = null,
    saverReveal: () -> Float = { 0f },
    saverPush: () -> Float = { 0f },
) {
    Box(Modifier.fillMaxSize()) {
        // Over a paused film, under the pictures: the shading its picture will have. The picture
        // fades in over it as the film gives way, so the screen keeps the same light throughout.
        Canvas(Modifier.fillMaxSize()) {
            val stopped = filmStopped()
            // Deep in the grid its frosted background covers everything.
            if (stopped < 0.01f || cover() < 0.01f || scroll() > collapse * 0.7f) return@Canvas
            drawRect(Brush.verticalGradient(*Images.SHADE_AT.indices.map { Images.SHADE_AT[it] to Color.Black.copy(alpha = Images.SHADE[it]) }.toTypedArray()), alpha = stopped)
            drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = Images.SHADE_LEFT), 1f to Color.Transparent, endX = size.width / 2), alpha = stopped)
        }
        Pictures(backdrop, frosted, scroll, collapse, cover)
        if (saverImage != null) Canvas(Modifier.fillMaxSize()) {
            val a = saverReveal()
            if (a > 0.01f) drawDrawingIn(saverImage, saverPush(), a)
        }
        Plate(frosted ?: backdrop, plate, scroll, collapse, cover, filmStopped, toSaver)
    }
}

/** The sharp picture and the grid's frosted one, crossfading when the wallpaper changes. */
@Composable
private fun Pictures(backdrop: ImageBitmap?, frosted: ImageBitmap?, scroll: () -> Float, collapse: Float, cover: () -> Float) {
    Crossfade(
        targetState = backdrop to frosted,
        animationSpec = tween(450, easing = Motion.EaseOut),
        modifier = Modifier.fillMaxSize(),
        label = "ambient",
    ) { (bmp, frost) ->
        // In "Follow App" mode there is no frosted copy: the app's light is small and soft
        // already, so it makes the glass itself.
        val glassSrc = frost ?: bmp
        val dark = remember(glassSrc) { glassSrc?.let { darkened(it, GRID_SHADE) } }
        Canvas(Modifier.fillMaxSize()) {
            val film = cover()
            // On the top row, the film alone through the window: nothing of Compose's full-screen.
            if (film > 0.99f && scroll() < collapse * 0.3f) return@Canvas
            if (bmp == null || dark == null) {
                drawRect(Color.Black)
                return@Canvas
            }
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val p = (scroll() / collapse).coerceIn(0f, 1f)
            // The sharp picture and the grid's frosted one cross only through the middle of the
            // scroll; each end is one full-screen draw.
            val g = ((p - 0.3f) / 0.4f).coerceIn(0f, 1f)
            if (g < 0.99f && film < 0.99f) drawImage(bmp, dstSize = full, filterQuality = FilterQuality.Low, alpha = 1f - film)
            if (g > 0.01f) drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = g)
        }
    }
}

/**
 * The plate under the top row, one layer over whatever is behind it: the picture, or the film
 * once stopped. It gives way only to a moving film, so the swap from the paused film to its
 * picture leaves it as it is. A new glass fades in over the old one, which stays whole beneath
 * it: two plates fading against each other let what is behind show through halfway.
 */
@Composable
private fun Plate(glass: ImageBitmap?, plate: Rect?, scroll: () -> Float, collapse: Float, cover: () -> Float, filmStopped: () -> Float, toSaver: () -> Float) {
    var shown by remember { mutableStateOf(glass) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val swap = remember { Animatable(1f) }
    fun visibility(): Float {
        val rest = 1f - smooth(((scroll() / collapse).coerceIn(0f, 1f) / 0.55f).coerceIn(0f, 1f))
        return rest * (1f - cover() * (1f - filmStopped())) * (1f - toSaver())
    }
    LaunchedEffect(glass) {
        if (glass === shown) return@LaunchedEffect
        // Not on screen (a moving film, or the grid): the new glass simply takes over. Faded in
        // over the old one, a plate appearing for a stopped film would flash the last film's colours.
        if (visibility() < 0.05f) {
            shown = glass
            under = null
            swap.snapTo(1f)
            return@LaunchedEffect
        }
        under = shown.takeIf { swap.value > 0.5f } ?: under
        shown = glass
        swap.snapTo(0f)
        swap.animateTo(1f, tween(450, easing = Motion.EaseOut))
        under = null
    }
    val top = remember(shown) { shown?.let { PlateLayer(plateTinted(it)) } }
    val bottom = remember(under) { under?.let { PlateLayer(plateTinted(it)) } }
    Canvas(Modifier.fillMaxSize()) {
        if (plate == null) return@Canvas
        // The plate rides with its row and dissolves into the frosted background: it fades
        // over the first half of the scroll, swelling a little as if lifting off, and settles
        // back the same way when focus returns to the top row. Kept small on purpose: the
        // whole screen shrinking back into the plate read as too much.
        val p = (scroll() / collapse).coerceIn(0f, 1f)
        val rest = 1f - smooth((p / 0.55f).coerceIn(0f, 1f))
        val alpha = visibility()
        if (alpha < 0.01f) return@Canvas
        val grow = PlateSwell.toPx() * (1f - rest)
        val r = plate.translate(0f, -scroll()).inflate(grow)
        val t = swap.value
        if (t < 1f) bottom?.draw(this, plate, r, alpha)
        top?.draw(this, plate, r, if (bottom != null) alpha * t else alpha)
    }
}

/**
 * Glass, continuous outline and lit edge baked into one picture at the plate's resting place and
 * size, stretched as it swells and riding up with its row as it fades. The GPU draws one image.
 * Composing them in shaders while drawing (1.27) came out solid white on older GPUs (Xiaomi
 * MiTV-ASTP0, Android 9, Mali-G31, issue #183; a HuiQu projector, #181) and drawn apart they cost
 * 3 ms more a frame than the plain rounded plate. Baked while drawing, for one screen and place.
 */
private class PlateLayer(private val src: ImageBitmap) {
    private var baked: Pair<Pair<Size, Rect>, ImageBitmap>? = null

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope, plate: Rect, at: Rect, alpha: Float) = with(scope) {
        val image = baked?.takeIf { it.first == size to plate }?.second
            ?: bakePlate(src, size, plate, PlateCorner.toPx(), 1.2.dp.toPx()).also { baked = (size to plate) to it }
        drawImage(
            image,
            dstOffset = IntOffset(at.left.roundToInt(), at.top.roundToInt()),
            dstSize = IntSize(at.width.roundToInt(), at.height.roundToInt()),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }
}

private fun smooth(x: Float) = x * x * (3f - 2f * x)

/** The Liquid Glass plate under the top row: how far it reaches past the tiles, and its corners. */
val PlatePadH = 26.dp
val PlatePadV = 20.dp
private val PlateCorner = 32.dp

/** The grid's background: the frosted picture under this much black. */
private const val GRID_SHADE = 0.55f

/** How much the plate swells as it dissolves into the grid's background. */
private val PlateSwell = 14.dp

/** The plate's tint: the frosted picture a little darker, lifted with white, so it reads as milky glass. */
private val PlateTint = floatArrayOf(
    0.8f, 0f, 0f, 0f, 30f,
    0f, 0.8f, 0f, 0f, 30f,
    0f, 0f, 0.8f, 0f, 30f,
    0f, 0f, 0f, 1f, 0f,
)

/** The glass picture with the plate's tint applied ahead of time, so the plate is one plain draw. */
private fun plateTinted(src: ImageBitmap): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(PlateTint) }
    android.graphics.Canvas(out).drawBitmap(src.asAndroidBitmap(), 0f, 0f, paint)
    return out.frozen().asImageBitmap()
}

/** The part of [glass] (stretched over [screen]) under [plate], cut to the plate's outline, with its lit edge. */
private fun bakePlate(glass: ImageBitmap, screen: Size, plate: Rect, radius: Float, rim: Float): ImageBitmap {
    val w = plate.width.roundToInt().coerceAtLeast(1)
    val h = plate.height.roundToInt().coerceAtLeast(1)
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawBitmap(
        glass.asAndroidBitmap(),
        Matrix().apply { setScale(screen.width / glass.width, screen.height / glass.height); postTranslate(-plate.left, -plate.top) },
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    canvas.drawBitmap(smoothMask(w, h, radius), 0f, 0f, android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    canvas.drawBitmap(glassEdge(w, h, radius, rim).asAndroidBitmap(), 0f, 0f, null)
    return out.frozen().asImageBitmap()
}

/**
 * What Home shows behind panels. Folders and Hidden Apps open on Home frosted, as on tvOS: the
 * grid's frosted background fading over it [frost] of the way, one full-screen draw of a small
 * picture (in "Follow App" mode, which has none, a dark veil). Control Center keeps Home sharp
 * under a dark [veil], its own panel the glass (1.41 frosted all of Home behind it too, which
 * owners took for everything gone blurry).
 */
// Equivalent to the previous two black scrims (45% then 28%), in one pass.
internal const val CONTROL_CENTER_DIM = 1f - (1f - 0.45f) * (1f - 0.28f)

@Composable
fun PanelBackdrop(frosted: ImageBitmap?, frost: () -> Float, veil: () -> Float) {
    val dark = remember(frosted) { frosted?.let { darkened(it, GRID_SHADE) } }
    Canvas(Modifier.fillMaxSize()) {
        val f = frost()
        if (f >= 0.01f) {
            if (dark == null) drawRect(Color.Black, alpha = 0.55f * f)
            else drawImage(dark, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = f)
        }
        val v = veil()
        if (v >= 0.01f) drawRect(Color.Black, alpha = CONTROL_CENTER_DIM * v)
    }
}

/** A small picture darkened ahead of time, so the grid's background costs no tint per frame. */
private fun darkened(src: ImageBitmap, alpha: Float): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0))
    return out.asImageBitmap()
}

/**
 * The large clock, as on the lock screen: the date over the time, and under it the weather, the
 * city it is for (issue #59) and today's high and low. Left or right, the three lines share one
 * edge, the tiles' edge. The digits stay proportional: tabular ones leave a narrow "1" short of
 * that edge. A soft shadow keeps them readable on bright photos. The clock screensaver shows the same.
 */
@Composable
fun ShelfClock(
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    /** How far the time has moved to the screensaver's place ([toSaverClock]); the date and weather go. */
    toSaver: () -> Float = { 0f },
) {
    val shadow = Shadow(Color.Black.copy(alpha = 0.28f), Offset(0f, 2f), blurRadius = 18f)
    val secondary = Color.White.copy(alpha = 0.78f)
    val aside = Modifier.graphicsLayer { alpha = 1f - (toSaver() * 2.5f).coerceIn(0f, 1f) }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        BasicText(dateText(now), style = Type.Title.copy(color = Color.White.copy(alpha = 0.9f), shadow = shadow), modifier = aside)
        BasicText(
            timeText(now),
            style = Type.Clock.copy(shadow = shadow),
            // The digits' cap top, in the trimmed box: the 104sp line puts it 7.6dp down, less the trim.
            modifier = Modifier.toSaverClock(toSaver, Type.Clock.fontSize, 7.6.dp - ClockTrimTop)
                .trimmed(top = ClockTrimTop, bottom = ClockTrimBottom),
        )
        Weather.now?.let { w ->
            Row(aside, verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(glyphFor(w), Color.White, 22.dp, Modifier.padding(end = 8.dp))
                BasicText("${w.celsius}°", style = Type.Title.copy(color = Color.White, shadow = shadow))
                val place = Weather.place?.takeIf { it.length <= 12 }
                val range = if (w.high != null && w.low != null) stringResource(R.string.weather_high_low, w.high, w.low) else null
                val detail = listOfNotNull(place, stringResource(conditionName(w.kind)), range).joinToString("  ·  ")
                BasicText(detail, style = Type.Headline.copy(color = secondary, shadow = shadow), modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/** Inter's cap top below the top of a line of natural height, as a share of the font size. */
private const val CAP_TOP = 0.242f

/**
 * Carries Home's time to where the screensaver shows it, top right and at its size, as [progress]
 * goes from 0 to 1: when the screensaver carries on the aerial wallpaper's film, Home's clock
 * becomes the screensaver's rather than one fading out and another in. [fontSize] is the time's
 * size on Home and [capTop] where its digits' cap top lies in this element's box.
 */
private fun Modifier.toSaverClock(progress: () -> Float, fontSize: TextUnit, capTop: Dp): Modifier = composed {
    // Where Home lays the time out, read before this element's own transform.
    var placed by remember { mutableStateOf<Pair<Rect, Float>?>(null) }
    onGloballyPositioned { placed = it.boundsInRoot() to it.findRootCoordinates().size.width.toFloat() }
        .graphicsLayer {
            val p = progress()
            val (box, rootW) = placed ?: return@graphicsLayer
            if (p <= 0f) return@graphicsLayer
            val s = SaverClockStyle.fontSize.toPx() / fontSize.toPx()
            val k = 1f + (s - 1f) * p
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = k
            scaleY = k
            val targetCapTop = SaverClockTop.toPx() + SaverClockStyle.fontSize.toPx() * CAP_TOP
            val targetLeft = if (AerialSettings.clockRight) rootW - SaverClockSide.toPx() - box.width * s else SaverClockSide.toPx()
            translationX = p * (targetLeft - box.left)
            translationY = p * (targetCapTop - s * capTop.toPx() - box.top)
        }
}

/**
 * The clock's line box reaches well past its digits (31dp above, 32dp below, measured on the
 * Xiaomi). Trimmed so the date sits 14dp over the digits and the weather 18dp under them.
 */
private val ClockTrimTop = 17.dp
private val ClockTrimBottom = 14.dp

/** Lays the content out [top] and [bottom] shorter than it measures, drawn where it was. */
private fun Modifier.trimmed(top: Dp, bottom: Dp) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val t = top.roundToPx()
    layout(p.width, (p.height - t - bottom.roundToPx()).coerceAtLeast(0)) { p.place(0, -t) }
}

private fun conditionName(kind: WeatherKind): Int = when (kind) {
    WeatherKind.Clear -> R.string.weather_clear
    WeatherKind.PartlyCloudy -> R.string.weather_partly_cloudy
    WeatherKind.Cloudy -> R.string.weather_cloudy
    WeatherKind.Fog -> R.string.weather_fog
    WeatherKind.Rain -> R.string.weather_rain
    WeatherKind.Snow -> R.string.weather_snow
    WeatherKind.Thunder -> R.string.weather_thunder
}

/**
 * The small clock: the time and the weather in one quiet line in the top left, level with the status
 * pill, so the wallpaper keeps the rest of the screen. A soft shadow keeps it readable on photos.
 */
@Composable
fun CornerClock(now: LocalDateTime, modifier: Modifier = Modifier, toSaver: () -> Float = { 0f }) {
    val dim = Color.White.copy(alpha = 0.92f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)
    Row(modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            timeText(now), style = Type.Title.copy(color = dim, shadow = shadow),
            modifier = Modifier.toSaverClock(toSaver, Type.Title.fontSize, Type.Title.fontSize.value.dp * CAP_TOP),
        )
        Weather.now?.let { w ->
            val aside = Modifier.graphicsLayer { alpha = 1f - (toSaver() * 2.5f).coerceIn(0f, 1f) }
            GlyphIcon(glyphFor(w), dim, 18.dp, aside.padding(start = 16.dp, end = 6.dp))
            val place = Weather.place?.takeIf { it.length <= 12 }
            BasicText(listOfNotNull(place, "${w.celsius}°").joinToString(" "), style = Type.Headline.copy(color = dim, shadow = shadow), modifier = aside)
        }
    }
}

private fun glyphFor(w: WeatherNow): Glyph = when (w.kind) {
    WeatherKind.Clear -> if (w.day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (w.day) Glyph.CloudSun else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog -> Glyph.CloudFog
    WeatherKind.Rain -> Glyph.CloudRain
    WeatherKind.Snow -> Glyph.CloudSnow
    WeatherKind.Thunder -> Glyph.CloudBolt
}

/**
 * Top right: network and, once the shelf clock has scrolled away, the time. Focusable: it is
 * where Up from the top row leads, and it opens Control Center.
 */
@Composable
fun StatusPill(
    now: LocalDateTime,
    network: NetworkStatus,
    showTime: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1.08f,
        modifier = modifier,
    ) { state ->
        LaunchedEffect(state.focused) { onFocused(state.focused) }
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(160, easing = Motion.EaseOut), label = "pill-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Color.White.copy(alpha = 0.9f)
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val glyph = when (network.kind) {
                NetKind.Wifi -> Glyph.Wifi
                NetKind.Ethernet -> Glyph.Ethernet
                NetKind.Offline -> Glyph.Offline
            }
            GlyphIcon(glyph, fg, 17.dp, level = network.level)
            if (showTime) {
                BasicText(timeText(now), style = Type.Headline.copy(color = fg))
            }
            GlyphIcon(Glyph.Switches, fg, 17.dp)
        }
    }
}
