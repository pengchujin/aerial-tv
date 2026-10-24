package app.orchard.tv.ui.home

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.Aerials
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.WallpaperKind
import app.orchard.tv.wallpaper.Wallpapers
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Aerial's screensaver, shown on Home after a few idle minutes, before the firmware's
 * (ad-carrying) one would start. Four kinds, chosen in Personalize:
 *
 * - Aerial: Apple TV's aerial films, streamed from Apple, with the place named as the camera moves.
 * - Photos: the owner's own photos (the daily ones when there are none), slowly drifting, each
 *   with where it was taken.
 * - Clock: a large clock over the wallpaper, moving once a minute so nothing burns in.
 * - Videos: the owner's local films, uploaded from the phone page, playing silently offline.
 *
 * If the films cannot be reached, Aerial falls back to Photos. Left and right step to the previous
 * or next film or photo, as on Apple TV; see [ScreensaverSkips].
 *
 * Until one is chosen the kind follows the wallpaper (Wallpapers.screensaver): aerial films with the
 * aerial wallpaper, photos with a picture, starting from that picture.
 *
 * With [carry], Home's own wallpaper carries on as the screensaver: the aerial wallpaper's film
 * already moving behind Home, or its picture beginning to draw in there, before the photos move on
 * from it. Home fades away around it (HomeScreen, whose clock becomes the screensaver's).
 */
@Composable
fun Screensaver(visible: Boolean, apps: List<AppEntry>, now: LocalDateTime, skips: ScreensaverSkips, carry: SaverCarry? = null) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(1200, easing = Motion.EaseOut)),
        // Leaves quickly: the viewer pressed a key and wants Home back.
        exit = fadeOut(tween(220, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Follows Home while up (the film may fail and hand over to the screensaver's own), and
        // stays as it was through the fade out, so leaving does not start a player.
        val held = remember { mutableStateOf(carry) }
        if (visible) held.value = carry
        when (val carried = held.value) {
            is SaverCarry.Film -> { FilmCarry(carried.film, now, skips.steps); return@AnimatedVisibility }
            is SaverCarry.Still -> { PhotoSaver(now, skips.steps, carried.wallpaper, carried); return@AnimatedVisibility }
            null -> Unit
        }
        var aerialFailed by remember { mutableStateOf(false) }
        var videosFailed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (Wallpapers.screensaver) {
                ScreensaverMode.Aerial -> if (aerialFailed) PhotoSaver(now, skips.steps) else AerialSaver(
                    now, skips.steps, onUnavailable = { aerialFailed = true }, startAt = remember {
                        // A paused wallpaper film still holds the decoder (one 4K decoder, MiTV-MFTR0).
                        LiveFilm.onHome?.letDecoderGo()
                        AerialStart.wallpaper()
                    },
                )
                ScreensaverMode.Photos -> PhotoSaver(now, skips.steps, remember { Wallpapers.screensaverPicture() })
                ScreensaverMode.Clock, ScreensaverMode.System -> ClockSaver(apps, now)
                ScreensaverMode.Videos -> if (videosFailed) PhotoSaver(now, skips.steps) else VideoSaver(skips.steps, onUnavailable = { videosFailed = true })
            }
        }
    }
}

/**
 * Left / right on the remote while the screensaver is up. The host (MainActivity on Home,
 * AerialDream as the system screensaver) offers every key here first; keys not taken here wake.
 */
class ScreensaverSkips {
    private val flow = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val steps: SharedFlow<Int> get() = flow

    /** True if the screensaver took [event], both its down and its up. The clock has nothing to step through. */
    fun onKey(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return false
        }
        if (Wallpapers.screensaver == ScreensaverMode.Clock) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) flow.tryEmit(step)
        return true
    }
}

/** What of Home's wallpaper carries on as the screensaver. */
sealed interface SaverCarry {
    /** The aerial wallpaper's film, moving on behind Home. */
    class Film(val film: LiveFilm) : SaverCarry

    /** The wallpaper's picture, drawing in behind Home (HomeBackground) as the photos' first. */
    class Still(val wallpaper: app.orchard.tv.wallpaper.Wallpaper) : SaverCarry {
        /** The photos have moved on over it: Home's picture can stop. */
        var covered by mutableStateOf(false)
    }
}

/** What a wallpaper's picture is, for the screensaver: where it was taken, else its name. The
 * owner's own photos carry no information for the owner, so they stay unlabeled (issue #117). */
fun stillCaption(wp: app.orchard.tv.wallpaper.Wallpaper): String? = when (wp.kind) {
    app.orchard.tv.wallpaper.WallpaperKind.Photo -> null
    else -> wp.caption?.takeIf { it.isNotBlank() } ?: wp.title
}

/** How long one slow push in (or back out) of a still wallpaper takes. */
private const val DRAW_IN_MS = 60_000

/** Pushes in and back out, slowly, for as long as the screensaver is up; from 0, the picture as Home has it. */
suspend fun drawIn(push: Animatable<Float, *>) {
    while (true) {
        push.animateTo(1f, tween(DRAW_IN_MS, easing = LinearEasing))
        push.animateTo(0f, tween(DRAW_IN_MS, easing = LinearEasing))
    }
}

/** [image] over the whole screen, [push] (0 to 1) of the way into its slow draw-in: larger by 8%, drifting right. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDrawingIn(image: ImageBitmap, push: Float, alpha: Float = 1f) {
    val s = 1f + 0.08f * push
    val w = size.width * s
    val h = size.height * s
    val left = (size.width - w) / 2f + 24.dp.toPx() * push
    val top = (size.height - h) / 2f
    drawImage(
        image,
        dstOffset = androidx.compose.ui.unit.IntOffset(left.roundToInt(), top.roundToInt()),
        dstSize = IntSize(w.roundToInt(), h.roundToInt()),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

/** Where a screensaver of aerials starts when it cannot carry Home's player on (AerialDream). */
object AerialStart {
    /**
     * Where the aerial wallpaper is, for a screensaver no one started from Home: TCL's own timer
     * starting AerialDream over it. Null with any other wallpaper.
     */
    fun wallpaper(): Pair<String, Long>? =
        if (Wallpapers.selectedId == Wallpapers.LIVE) LiveFilm.onHome?.spot() ?: Wallpapers.liveSpot() else null
}

/**
 * The aerial wallpaper's film carried on as the screensaver, one shot from Home: the same player,
 * in the wallpaper's own format, through to the films after it. Not switched to Dolby Vision where
 * the screensaver could play it: these TVs have one 4K decoder, and the switch showed black for
 * 1–3.5 s (Xiaomi MiTV-MFTR0), which the owner found worse than the format.
 */
@Composable
private fun FilmCarry(film: LiveFilm, now: LocalDateTime, steps: SharedFlow<Int>) {
    var caption by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(film.film) {
        while (true) {
            caption = film.film?.captionAt((film.positionMs / 1000).toInt())
            delay(1000)
        }
    }
    LaunchedEffect(steps) { steps.collect { film.skip(it) } }
    Labels(caption, now, showTime = false)
}

/** Place name bottom-left, time top-right: how tvOS labels its screensaver. */
@Composable
private fun Labels(caption: String?, now: LocalDateTime, showTime: Boolean = true) {
    // Fades in where Home's own clock has moved to, as the photos cover it.
    val time by animateFloatAsState(if (showTime) 1f else 0f, tween(2000), label = "time")
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // Just enough shade under the text to read it over bright sky or snow.
            drawRect(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f)))
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.18f to Color.Transparent))
        }
        if (time > 0f) BasicText(
            timeText(now),
            style = SaverClockStyle,
            modifier = Modifier
                .align(if (AerialSettings.clockRight) Alignment.TopEnd else Alignment.TopStart)
                .padding(top = SaverClockTop, start = SaverClockSide, end = SaverClockSide)
                .graphicsLayer { alpha = time },
        )
        AnimatedContent(
            targetState = caption.orEmpty(),
            transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(600)) },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 44.dp),
            label = "caption",
        ) { text ->
            BasicText(
                text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = Type.Title.copy(color = Color.White.copy(alpha = 0.92f)),
                modifier = Modifier.widthIn(max = 620.dp),
            )
        }
    }
}

/**
 * The screensaver's time, top left or right as Home's clock is (Personalize): Home's clock moves
 * here when the screensaver carries on its film, and from the left it crossed the whole screen to
 * the right, a long slide for a moment that should be quiet (issue #357).
 */
internal val SaverClockStyle = Type.LargeTitle.copy(fontSize = Type.LargeTitle.fontSize * 1.3f, color = Color.White.copy(alpha = 0.92f))
internal val SaverClockTop = 40.dp
internal val SaverClockSide = 56.dp

/**
 * Before Android 7.0 network_security_config is ignored, so Apple Root CA is never trusted and
 * HTTPS to Apple's video host always fails, but only after the player has retried for about half a
 * minute (34 s on TCL 75S365C). Those versions start on HTTP. From 7.0 on this is set for the rest of
 * the process once a film fails over HTTPS.
 */
private var aerialsOverHttp by mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.N)

/**
 * The quality a decoder gave up on, for the rest of the process: the screensaver then starts one
 * step lower. A TV can list a decoder it cannot drive at that size or profile.
 */
private var aerialQualityCap: AerialQuality? = null

/**
 * [startAt]: the aerial wallpaper's film and moment to begin with, played in the wallpaper's format
 * (AerialFormats.forWallpaper) so it goes on as the wallpaper looked.
 */
@Composable
private fun AerialSaver(
    now: LocalDateTime,
    steps: SharedFlow<Int>,
    onUnavailable: () -> Unit,
    startAt: Pair<String, Long>? = null,
) {
    val context = LocalContext.current
    var quality by remember {
        val wanted = if (startAt != null) AerialFormats.forWallpaper(context) else AerialFormats.resolve(context, AerialSettings.aerialQuality)
        mutableStateOf(aerialQualityCap?.takeIf { it.ordinal < wanted.ordinal } ?: wanted)
    }
    val films = remember {
        val all = Aerials.all(context).shuffled()
        val first = startAt?.let { (id, _) -> all.firstOrNull { it.id == id } }
        if (first != null) listOf(first) + (all - first) else AerialStream.withOpenerFirst(context, all, quality)
    }
    val firstAtMs = remember { startAt?.takeIf { (id, _) -> films.first().id == id }?.second ?: 0L }
    val player = remember { AerialStream.player(context, quality) }
    var index by remember { mutableIntStateOf(0) }
    var failures by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf<String?>(null) }
    // Bumped whenever a film is (re)started, so the stall check below starts over.
    var attempt by remember { mutableIntStateOf(0) }
    var shownAny by remember { mutableStateOf(false) }
    var retried by remember { mutableStateOf(false) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val film = films[index.mod(films.size)]

    // Black veil that lifts once the film is actually moving, and drops when the viewer skips.
    val veil by animateFloatAsState(if (playing) 0f else 1f, tween(if (playing) 1500 else 400), label = "veil")

    fun load(at: Int, fromMs: Long = 0L) {
        player.setMediaItems(films.map { AerialStream.mediaItem(it, quality, aerialsOverHttp) }, at, fromMs)
        player.prepare()
        player.playWhenReady = true
    }

    fun skip(step: Int) {
        playing = false
        if (step < 0) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    DisposableEffect(player) {
        val opened = SystemClock.elapsedRealtime()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!playing) DiagLog.i("first frame of ${player.currentMediaItem?.mediaId} after ${SystemClock.elapsedRealtime() - opened} ms")
                playing = true
                shownAny = true
                failures = 0
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
                attempt++
                // A film that simply ended hands over to the next one, already loaded, without a gap.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) playing = false
            }

            override fun onPlayerError(error: PlaybackException) {
                DiagLog.i("aerial film failed (${error.errorCodeName}): ${player.currentMediaItem?.localConfiguration?.uri}")
                playing = false
                // Before anything has shown, the decoder may still be held by Home's wallpaper film,
                // which is let go as Home goes behind the dream (TCL MT5879): once more at this
                // quality, a moment later, before going lower for good.
                if (!shownAny && !retried && error.errorCode in DECODER_ERRORS) {
                    retried = true
                    DiagLog.i("trying ${quality.key} again in $RETRY_MS ms")
                    handler.postDelayed({ load(player.currentMediaItemIndex, if (player.currentMediaItemIndex == 0) firstAtMs else 0L) }, RETRY_MS)
                    attempt++
                    return
                }
                // The decoder could not play this quality: the same film one step lower.
                if (quality != AerialQuality.Hd && error.errorCode in DECODER_ERRORS) {
                    aerialQualityCap = AerialFormats.lower(quality)
                    quality = AerialFormats.lower(quality)
                    load(player.currentMediaItemIndex)
                    attempt++
                    return
                }
                // Apple's video host chains to Apple Root CA, which Android does not ship. From 7.0 on
                // network_security_config trusts it for this host; 5.0 and 6.0 ignore that file and
                // start on HTTP. The same files are served over plain HTTP; try that before giving up.
                if (!aerialsOverHttp) {
                    aerialsOverHttp = true
                    load(player.currentMediaItemIndex)
                } else {
                    failures++
                    skip(1)
                }
                attempt++
            }
        }
        player.addListener(listener)
        load(0, firstAtMs)
        onDispose {
            handler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(index) {
        caption = null
        while (true) {
            delay(1000)
            if (playing) caption = film.captionAt((player.currentPosition / 1000).toInt())
        }
    }
    LaunchedEffect(steps) {
        steps.collect { step ->
            if (films.size < 2) return@collect
            skip(step)
        }
    }
    // A film the CDN serves slowly is passed over rather than waited for.
    LaunchedEffect(attempt, playing) {
        if (playing) return@LaunchedEffect
        delay(STALL_MS)
        DiagLog.i("no picture from ${player.currentMediaItem?.mediaId} after $STALL_MS ms; next film")
        failures++
        // Nothing has played at all this time: Apple's CDN is out of reach. Hand over to the photo
        // screensaver after two films rather than three, so the screen is not black for long
        // (Sony A8G and X90J, TCL 65S515D, issue #20).
        if (!shownAny && failures >= 2) onUnavailable() else skip(1)
    }
    LaunchedEffect(failures) {
        if (failures >= 3) onUnavailable()
    }
    // While a film plays, get the next screensaver's first film ready on disk.
    LaunchedEffect(playing) {
        if (playing) AerialStream.prepareOpener(context, films, film, quality, aerialsOverHttp)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).also {
                it.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = DiagLog.i("film surface created")
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = DiagLog.i("film surface destroyed")
                })
                player.setVideoSurfaceView(it)
            }
        },
        onRelease = { player.clearVideoSurfaceView(it) },
    )
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color.Black))
    Labels(if (playing) caption ?: film.captionAt(((if (index == 0) firstAtMs else 0L) / 1000).toInt()) else null, now)
}

/** How long before a film whose decoder would not open is tried once more at the same quality. */
private const val RETRY_MS = 1_500L

/** How long a film may take to show its first frame before the next one is tried. */
private const val STALL_MS = 8_000L

private val DECODER_ERRORS = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

@Composable
private fun PhotoSaver(
    now: LocalDateTime,
    steps: SharedFlow<Int>,
    /** The wallpaper's picture, shown first. */
    first: app.orchard.tv.wallpaper.Wallpaper? = null,
    /** Home already shows [first], drawing in: the photos take over from the second. */
    carried: SaverCarry.Still? = null,
) {
    val reduced = LocalReducedMotion.current
    val pool = remember(Wallpapers.items) {
        // The owner's own photos when there are any, else the daily ones (issue #27).
        val pictures = Wallpapers.items.filter { it.kind == WallpaperKind.Photo }
            .ifEmpty { Wallpapers.items.filter { it.kind == WallpaperKind.Daily } }
        val rest = (pictures.ifEmpty { Wallpapers.items.filter { it.kind == WallpaperKind.Builtin } }).shuffled()
        if (first == null) rest else listOf(first) + rest.filter { it.id != first.id }
    }
    var slide by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }

    // Restarts on every slide, so a step from the remote also restarts the 18 s wait.
    LaunchedEffect(pool, slide) {
        if (pool.isEmpty()) return@LaunchedEffect
        if (carried == null || slide != 0) {
            val wp = pool[slide.mod(pool.size)]
            val bmp = withContext(Dispatchers.Default) { Wallpapers.picture(wp, Wallpapers.screenW, Wallpapers.screenH)?.asImageBitmap() }
            if (bmp != null) image = slide to bmp
        }
        delay(18_000)
        slide++
    }
    // Once a photo has crossfaded in over Home's picture, Home can stop drawing it.
    LaunchedEffect(image != null) {
        if (carried != null && image != null) {
            delay(2_100)
            carried.covered = true
        }
    }
    LaunchedEffect(steps) {
        steps.collect { slide += it }
    }

    Crossfade(targetState = image, animationSpec = tween(2000, easing = LinearEasing), label = "photo") { current ->
        if (current != null) {
            // Ken Burns: a slow push in, alternating direction slide to slide.
            val push = remember { Animatable(0f) }
            LaunchedEffect(Unit) { if (!reduced) push.animateTo(1f, tween(20_000, easing = LinearEasing)) }
            val dir = if (current.first.mod(2) == 0) 1f else -1f
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1.04f + 0.08f * push.value
                    scaleX = s; scaleY = s
                    translationX = dir * (push.value - 0.5f) * 40.dp.toPx()
                },
            ) {
                drawImage(current.second, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
            }
        }
    }
    val wp = pool.getOrNull((image?.first ?: 0).mod(pool.size.coerceAtLeast(1)))
    Labels(wp?.let(::stillCaption), now, showTime = carried == null || image != null)
}

/** A large clock that moves once a minute over the wallpaper or the apps' light. */
@Composable
private fun ClockSaver(apps: List<AppEntry>, now: LocalDateTime) {
    val minute = now.withSecond(0).withNano(0)
    val scene = remember(minute, apps.size, Wallpapers.backdrop) {
        val rnd = Random(minute.hashCode())
        Scene(
            light = Wallpapers.backdrop ?: apps.takeIf { it.isNotEmpty() }?.get(rnd.nextInt(apps.size))?.art?.backdrop,
            x = rnd.nextFloat(),
            y = rnd.nextFloat(),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Crossfade(targetState = scene, animationSpec = tween(2400, easing = Motion.EaseInOut), label = "clock-scene") { s ->
            Box(Modifier.fillMaxSize()) {
                s.light?.let { light ->
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(light, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = 0.75f)
                    }
                }
                Column(
                    Modifier.offset(
                        x = 64.dp + (w - 64.dp * 2 - 420.dp) * s.x,
                        y = 56.dp + (h - 56.dp * 2 - 170.dp) * s.y,
                    ),
                ) { ShelfClock(now = now) }
            }
        }
    }
}

private data class Scene(val light: ImageBitmap?, val x: Float, val y: Float)
