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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
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
import app.orchard.tv.ui.components.Dissolve
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.frozen
import app.orchard.tv.ui.components.glassEdge
import app.orchard.tv.ui.components.rememberFrostSwap
import app.orchard.tv.ui.components.smoothMask
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.LocalReducedMotion
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
    /** Shade the film while it moves, as its picture would be shaded. */
    shadeMoving: Boolean = false,
    /**
     * How far the film is stopped (0 to 1) with its glass ready ([frosted]) but its frame not yet
     * the picture, which takes a moment more where the frame is decoded again (Xiaomi
     * MiTV-MFTR0). Meanwhile the plate stands over the paused film, so the top row is back on
     * its glass with the rest of Home, the same plate it keeps once the picture is in.
     */
    filmStopped: () -> Float = { 0f },
    /** How far Home has given way to the screensaver carrying its wallpaper on: the plate goes. */
    toSaver: () -> Float = { 0f },
    /**
     * The glass for a moving film (Wallpapers.filmFrost, or [frosted] until it has one): the plate
     * stays over the film on it. Null where the film has none (an input's picture): the plate is
     * the smoke and the lit edge alone.
     */
    filmGlass: ImageBitmap? = null,
    /** The wallpaper's picture unshaded, shown [saverReveal] of the way and [saverPush] into its draw-in. */
    saverImage: ImageBitmap? = null,
    saverReveal: () -> Float = { 0f },
    saverPush: () -> Float = { 0f },
) {
    Box(Modifier.fillMaxSize()) {
        // Over a paused film, under the pictures: the shading its picture will have. The picture
        // fades in over it as the film gives way, so the screen keeps the same light throughout.
        Canvas(Modifier.fillMaxSize()) {
            val stopped = maxOf(filmStopped(), if (shadeMoving) cover() else 0f)
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
        Plate(frosted ?: backdrop, filmGlass, plate, scroll, collapse, cover, filmStopped, toSaver)
    }
}

/**
 * The sharp picture and the grid's frosted one. A new wallpaper fades in over the old, which stays
 * whole beneath it: crossfaded, the screen dimmed half-way through.
 */
@Composable
private fun Pictures(backdrop: ImageBitmap?, frosted: ImageBitmap?, scroll: () -> Float, collapse: Float, cover: () -> Float) {
    Dissolve(
        targetState = backdrop to frosted,
        durationMs = 450,
        easing = Motion.EaseOut,
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
 *
 * Under it, while a film moves, the film's plate ([film]): a film under the window cannot be
 * blurred, so a small key frame of it, frosted, a few seconds old (LiveWallpaper), lies
 * [FILM_FROST] of the way over the film, and a smoke tint and the lit edge over both. The film
 * shows through moving, so the frame's lag reads as the glass's softness. With no [film] (an
 * input's picture) only the smoke and the edge. Drawn only as the plate
 * moves or a new frame fades in, every [GLASS_EVERY_MS]: the film plays with Compose all but still.
 */
@Composable
private fun Plate(glass: ImageBitmap?, film: ImageBitmap?, plate: Rect?, scroll: () -> Float, collapse: Float, cover: () -> Float, filmStopped: () -> Float, toSaver: () -> Float) {
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

    // Each new frame is baked off the main thread: baked while drawing, as the still plate's glass
    // is, it held up a frame every few seconds (Xiaomi MiTV-MFTR0: frames of 50-70 ms).
    val density = LocalDensity.current
    var screen by remember { mutableStateOf(Size.Zero) }
    var filmTop by remember { mutableStateOf<ImageBitmap?>(null) }
    var filmBottom by remember { mutableStateOf<ImageBitmap?>(null) }
    var smoke by remember { mutableStateOf<ImageBitmap?>(null) }
    val filmSwap = remember { Animatable(1f) }
    LaunchedEffect(plate, screen) {
        if (plate == null || screen == Size.Zero) return@LaunchedEffect
        smoke = withContext(Dispatchers.Default) { bakePlate(smokeGlass(), screen, plate, with(density) { PlateCorner.toPx() }, with(density) { 1.2.dp.toPx() }) }
    }
    LaunchedEffect(film, plate, screen) {
        if (film == null || plate == null || screen == Size.Zero) {
            filmTop = null
            filmBottom = null
            return@LaunchedEffect
        }
        val baked = withContext(Dispatchers.Default) { bakePlate(plateTinted(film), screen, plate, with(density) { PlateCorner.toPx() }, 0f, edge = false) }
        // Not on screen: the new frame simply takes over.
        if (filmTop == null || cover() < 0.01f) {
            filmTop = baked
            filmBottom = null
            filmSwap.snapTo(1f)
            return@LaunchedEffect
        }
        filmBottom = filmTop.takeIf { filmSwap.value > 0.5f } ?: filmBottom
        filmTop = baked
        filmSwap.snapTo(0f)
        filmSwap.animateTo(1f, tween(FILM_SWAP_MS, easing = Motion.EaseOut))
        filmBottom = null
    }
    val frostPaint = remember { androidx.compose.ui.graphics.Paint() }

    Canvas(Modifier.fillMaxSize().onSizeChanged { screen = Size(it.width.toFloat(), it.height.toFloat()) }) {
        if (plate == null) return@Canvas
        val alpha = visibility()
        // The film's plate, while the film shows and the plate over a picture does not hide it.
        // With no frame to frost (an input's picture, which cannot be copied) it is the smoke and
        // the lit edge alone, so the top row keeps a plate over anything.
        val base = (1f - smooth(((scroll() / collapse).coerceIn(0f, 1f) / 0.55f).coerceIn(0f, 1f))) * (1f - toSaver())
        val frame = filmTop
        val edge = smoke
        if (edge != null && cover() > 0.01f && alpha < 0.99f && base > 0.01f) {
            val p = (scroll() / collapse).coerceIn(0f, 1f)
            val r = plate.translate(0f, -scroll()).inflate(PlateSwell.toPx() * smooth((p / 0.55f).coerceIn(0f, 1f)))
            val t = filmSwap.value
            val under = filmBottom
            // The old frame whole under the new, both in one layer laid over the film at FILM_FROST.
            if (frame != null && t < 1f && under != null) {
                frostPaint.alpha = FILM_FROST * base
                drawContext.canvas.saveLayer(r, frostPaint)
                drawBaked(under, r, 1f)
                drawBaked(frame, r, t)
                drawContext.canvas.restore()
            } else if (frame != null) {
                drawBaked(frame, r, FILM_FROST * base)
            }
            drawBaked(edge, r, base)
        }

        // The plate rides with its row and dissolves into the frosted background: it fades
        // over the first half of the scroll, swelling a little as if lifting off, and settles
        // back the same way when focus returns to the top row. Kept small on purpose: the
        // whole screen shrinking back into the plate read as too much.
        val p = (scroll() / collapse).coerceIn(0f, 1f)
        val rest = 1f - smooth((p / 0.55f).coerceIn(0f, 1f))
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
        drawBaked(image, at, alpha)
    }
}

/** A plate baked at its resting place, drawn stretched to [at]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBaked(image: ImageBitmap, at: Rect, alpha: Float) = drawImage(
    image,
    dstOffset = IntOffset(at.left.roundToInt(), at.top.roundToInt()),
    dstSize = IntSize(at.width.roundToInt(), at.height.roundToInt()),
    alpha = alpha,
    filterQuality = FilterQuality.Low,
)

private fun smooth(x: Float) = x * x * (3f - 2f * x)

/** The film plate's tint over the film and its frosted frame, as one pixel the plate stretches. */
private fun smokeGlass(): ImageBitmap =
    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        .apply { eraseColor(FILM_SMOKE) }.asImageBitmap()

/** How much of the film plate is its frosted key frame; the rest is the film itself, moving. */
private const val FILM_FROST = 0.55f

/** The film plate's smoke: a little black, so the tiles keep their contrast over a bright film. */
private val FILM_SMOKE = android.graphics.Color.argb(46, 0, 0, 0)

/** A new key frame fades in over the last: slow, as the glass is soft, and seldom (GLASS_EVERY_MS). */
private const val FILM_SWAP_MS = 700

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
private fun bakePlate(glass: ImageBitmap, screen: Size, plate: Rect, radius: Float, rim: Float, edge: Boolean = true): ImageBitmap {
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
    if (edge) canvas.drawBitmap(glassEdge(w, h, radius, rim).asAndroidBitmap(), 0f, 0f, null)
    return out.frozen().asImageBitmap()
}

/**
 * What Home shows behind panels. Folders and Hidden Apps open on Home frosted, as on tvOS: the
 * grid's frosted background fading over it [frost] of the way, one full-screen draw of a small
 * picture (in "Follow App" mode, which has none, a dark veil). Control Center keeps Home sharp
 * under a dark [veil], its own panel the glass (1.41 frosted all of Home behind it too, which
 * owners took for everything gone blurry).
 */
@Composable
fun PanelBackdrop(frosted: ImageBitmap?, frost: () -> Float, veil: () -> Float) {
    val (shown, under, swap) = rememberFrostSwap(frosted)
    val dark = remember(shown) { shown?.let { darkened(it, GRID_SHADE) } }
    val darkUnder = remember(under) { under?.let { darkened(it, GRID_SHADE) } }
    Canvas(Modifier.fillMaxSize()) {
        val f = frost()
        if (f >= 0.01f) {
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val t = swap()
            if (dark == null) drawRect(Color.Black, alpha = 0.55f * f)
            else if (darkUnder != null && t < 1f) {
                drawImage(darkUnder, dstSize = full, filterQuality = FilterQuality.Low, alpha = f)
                drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = f * t)
            } else drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = f)
        }
        val v = veil()
        if (v >= 0.01f) drawRect(Color.Black, alpha = 0.45f * v)
    }
}

/** A small picture darkened ahead of time, so the grid's background costs no tint per frame. */
private fun darkened(src: ImageBitmap, alpha: Float): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0))
    return out.asImageBitmap()
}

/**
 * The large clock, as on the lock screen: the date (in Chinese, with the lunar one) over the time
 * in heavy digits, and under it the weather, the city it is for (issue #59) and today's high and
 * low. Left or right, the three lines share one edge, the tiles' edge. Each line's box is its
 * capitals' height ([capBox]), so the gaps between them are the gaps a reader sees. A soft shadow
 * keeps them readable on bright photos. The clock screensaver shows the same.
 */
@Composable
fun ShelfClock(
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    /** How far the time has moved to the screensaver's place ([toSaverClock]); the date and weather go. */
    toSaver: () -> Float = { 0f },
    /** The screensaver is up over Home, carrying this clock: it wanders as the screensaver's does. */
    saving: Boolean = false,
    /** The screensaver is carrying Home's wallpaper on: the date and weather go before the time moves. */
    away: Boolean = false,
) {
    val drift = heldDrift(saving, now)
    val shadow = Shadow(Color.Black.copy(alpha = 0.28f), Offset(0f, 2f), blurRadius = 18f)
    val dim = Color.White.copy(alpha = 0.72f)
    // Gone in the first 400 ms, while the time has barely set off (its 2 s ease-in-out). Fading
    // along its progress (1.52), they were still there as the time rose through the date, and over
    // a moving film, at 8-10 frames a second on Xiaomi MiTV-MFTR0, they read as cut away in steps.
    // Back on Home they return once the time is nearly in place (CLOCK_BACK_MS), as they went.
    val reduced = LocalReducedMotion.current
    val asideGone by animateFloatAsState(
        if (away) 1f else 0f,
        if (reduced) snap() else if (away) tween(400, easing = Motion.EaseOut) else tween(400, delayMillis = CLOCK_BACK_MS * 7 / 10, easing = Motion.EaseOut),
        label = "clock-aside",
    )
    val aside = Modifier.graphicsLayer {
        alpha = 1f - asideGone
        // No offscreen buffer: it would be the line's capBox, and while the line faded the Chinese,
        // the icon and the shadow above and below it were cut off, the whole line springing open
        // as the fade ended (the owner, Xiaomi MiTV-MFTR0).
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        // On a TV set to Chinese, the Chinese calendar's date follows, as on the iPhone's lock screen.
        val lunar = if (Locale.getDefault().language == "zh") Lunar.text(now.toLocalDate()) else null
        BasicText(
            buildAnnotatedString {
                append(dateText(now))
                if (lunar != null) withStyle(SpanStyle(color = dim, fontWeight = FontWeight.W500)) { append("  $lunar") }
            },
            style = Type.ClockDate.copy(shadow = shadow),
            modifier = aside.capBox(Type.ClockDate.fontSize),
        )
        BasicText(
            timeText(now),
            style = Type.Clock.copy(shadow = shadow),
            modifier = Modifier.padding(top = 16.dp)
                .toSaverClock(toSaver, Type.Clock.fontSize, 0.dp, drift)
                .capBox(Type.Clock.fontSize),
        )
        Weather.now?.takeIf { AerialSettings.showWeather }?.let { w ->
            val place = Weather.place?.takeIf { it.length <= 12 }
            val range = if (w.high != null && w.low != null) stringResource(R.string.weather_high_low, w.high, w.low) else null
            val detail = listOfNotNull(place, stringResource(conditionName(w.kind)), range).joinToString(" · ")
            Row(aside.padding(top = 18.dp).capBox(Type.ClockDetail.fontSize), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(glyphFor(w), Color.White, 18.dp, Modifier.padding(end = 7.dp))
                BasicText(
                    buildAnnotatedString {
                        append("${w.celsius}°")
                        withStyle(SpanStyle(color = dim, fontWeight = FontWeight.W500)) { append("  $detail") }
                    },
                    style = Type.ClockDetail.copy(shadow = shadow),
                )
            }
        }
    }
}

/**
 * How far the clock has wandered with the screensaver ([saverDrift]), held once it ends: the time
 * goes back from where it was, the wander shrinking with the rest of the way ([toSaverClock]).
 * Dropped at once on waking, the time jumped up to 24 dp in the first frame of its way back.
 */
@Composable
private fun heldDrift(saving: Boolean, now: LocalDateTime): DpOffset {
    var held by remember { mutableStateOf(DpOffset.Zero) }
    if (saving) held = saverDrift(now)
    return held
}

/** Inter's cap top below the top of a line of natural height, as a share of the font size. */
private const val CAP_TOP = 0.242f

/** Inter's cap height (and its digits' height), as a share of the font size. */
private const val CAP_HEIGHT = 0.727f

/**
 * Carries Home's time to where the screensaver shows it, top right and at its size, as [progress]
 * goes from 0 to 1: when the screensaver carries on the aerial wallpaper's film, Home's clock
 * becomes the screensaver's rather than one fading out and another in. [fontSize] is the time's
 * size on Home and [capTop] where its digits' cap top lies in this element's box.
 */
private fun Modifier.toSaverClock(progress: () -> Float, fontSize: TextUnit, capTop: Dp, drift: DpOffset = DpOffset.Zero): Modifier = composed {
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
            translationX = p * (targetLeft - box.left + drift.x.toPx())
            translationY = p * (targetCapTop - s * capTop.toPx() - box.top + drift.y.toPx())
            // The screensaver shows no time: Home's goes as the screensaver comes.
            if (!AerialSettings.screensaverClock) alpha = 1f - p
        }
}

/**
 * Lays a line of text out as just its capitals' height, from their top to the baseline, drawn where
 * it was: so a line's box is the ink a reader sees, and lines line up by their tops and baselines.
 * Descenders and accents hang outside the box.
 */
private fun Modifier.capBox(fontSize: TextUnit) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val baseline = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
    val top = (baseline - fontSize.toPx() * CAP_HEIGHT).roundToInt().coerceAtLeast(0)
    layout(p.width, baseline - top) { p.place(0, -top) }
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
fun CornerClock(now: LocalDateTime, modifier: Modifier = Modifier, toSaver: () -> Float = { 0f }, saving: Boolean = false) {
    val drift = heldDrift(saving, now)
    val dim = Color.White.copy(alpha = 0.92f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)
    Row(modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            timeText(now), style = Type.SmallClock.copy(fontSize = Type.Title.fontSize, color = dim, shadow = shadow),
            modifier = Modifier.toSaverClock(toSaver, Type.Title.fontSize, Type.Title.fontSize.value.dp * CAP_TOP, drift),
        )
        Weather.now?.takeIf { AerialSettings.showWeather }?.let { w ->
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
