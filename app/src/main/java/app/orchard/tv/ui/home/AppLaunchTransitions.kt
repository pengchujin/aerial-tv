package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModel
import app.orchard.tv.apps.TileArt
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How long a released picture of Home waits before its pixels are freed (a frame may still draw it). */
private const val SNAPSHOT_RECYCLE_DELAY_MS = 500L

/**
 * Frames Home is drawn without the film (homeStill) before its picture is taken: the first flips
 * the draw-phase state and records the layer again, the second leaves room for the render
 * thread to have it. The overlay covers the window and holds still then, so they show nothing.
 */
private const val STILL_FRAMES = 2

/**
 * While those frames record Home for the picture, the window does not draw it (the overlay holds
 * fully grown over all of it): LaunchZoomHome records the layer and skips drawLayer. The picture
 * reads the recorded layer, not the window's draw of it. Not checked on a TV yet: if the picture of
 * Home comes out blank on the return, set this to false (the frames then draw Home as before).
 */
private const val SKIP_UNSEEN_HOME_DRAW = true

/** The tile a zoom grows from: its artwork and its face as drawn at the click (focus zoom included). */
internal class ZoomSource(
    val key: String,
    val art: TileArt,
    /** Root pixels, unclipped. */
    val rect: Rect,
    /** The face's corner in root pixels at [rect]'s size. */
    val radius: Float,
    val at: Long,
)

/** Retained only for this launcher process/configuration, never persisted as stale window bounds. */
class AppLaunchTransitions : ViewModel() {
    private val machine = LaunchSessionMachine()
    internal val retreat = Animatable(0f)
    internal var session by mutableStateOf<LaunchSession?>(null)
        private set

    /** What the zoom overlay draws; null when there is none. */
    internal var zoom by mutableStateOf<ZoomSource?>(null)
        private set
    /** 0: the tile; 1: full screen. Written by [AppLaunchZoom]'s driver and by [stopped]. */
    internal var zoomProgress by mutableFloatStateOf(0f)
        private set
    private var zoomVelocity = 0f
    /** Bumped when the progress jumps outside the driver, so a run it overtook writes nothing more. */
    private var zoomGeneration = 0
    /** The source tile's focused face, published by the live tile while the zoom returns into it. */
    internal var returnTarget by mutableStateOf<Rect?>(null)
    /** The overlay's window in root pixels, measured by [AppLaunchZoom]. */
    internal var zoomWindow by mutableStateOf<Rect?>(null)

    /**
     * Home is not drawn: the overlay covers the whole window and stays there (the grow has ended,
     * or Aerial stopped behind the app). Written only when it changes, at those moments and when
     * the return or a retract starts, so Home's layer is never re-recorded for the zoom's frames.
     */
    internal var homeHidden by mutableStateOf(false)
        private set

    /**
     * The zoom is shrinking back (or about to): the work of coming to the front is held until it
     * has (MainActivity), and the wallpaper's film waits too (HomeScreen). Set by [holdForReturn],
     * cleared once the session no longer returns with motion, or by [endQuiet] (its timeout).
     */
    internal var returnQuiet by mutableStateOf(false)
        private set
    private var quietSince = 0L

    /** Told once each time [returnQuiet] ends, with why; MainActivity runs what it held. */
    internal var onQuietEnd: ((String) -> Unit)? = null

    /**
     * Aerial is paused but Home is told so only when the zoom has grown ([holdsPauseForGrow]): while
     * true, HomeScreen reads "in front" (nothing recomposes for `resumed = false`, and the film is
     * not released). The film itself stands on its frame from the click ([holdsFilmForGrow],
     * HomeScreen), so this hold costs the grow no decoding. Set by [holdPauseForGrow] in onPause, cleared by [releasePause] on every way out of
     * it: the grow ending ([settleCover]), the stop, any change of the session that ends the grow
     * ([publish]: resume, restore, cancel, finish, retract, a newer launch), the view model going,
     * or [PAUSE_HOLD_MAX_MS] after it began. Released once, whatever came first.
     */
    internal var pauseHeld by mutableStateOf(false)
        private set
    private var pauseHeldSince = 0L
    private val pauseFailsafe = Runnable { releasePause("timeout") }

    /**
     * The picture of Home is being taken with the wallpaper's film out of it: Home's background draws
     * the still picture and plate as it does once the film has been let go (read by HomeScreen's
     * `cover`, in the draw phase only). True for the two frames before [captureHomeLate] takes it.
     */
    internal var homeStill by mutableStateOf(false)
        private set

    /**
     * Set with [homeStill] when the zoom already holds fully grown over the window: Home is recorded
     * into [homeLayer] for the picture but not drawn into the window those frames ([drawsHomeLayer],
     * [homeUnseenUnderGrownZoom]). Cleared with [homeStill], and by [showHome], which every way
     * of drawing Home again goes through, and by [publish] once the session leaves the grow track.
     */
    internal var homeUnseen by mutableStateOf(false)
        private set

    private var noted: ZoomSource? = null

    /** The tile records itself just before its click reaches the launcher; read once by the launch. */
    internal fun noteZoomSource(source: ZoomSource) { noted = source }
    internal fun takeZoomSource(key: String): ZoomSource? = noted?.also { noted = null }
        ?.takeIf { it.key == key && SystemClock.uptimeMillis() - it.at < 1_000 }

    // ---- The picture of Home for the return (keepsHomeSnapshot) ----

    /** All of Home as last drawn, recorded by [LaunchZoomHome]; what the picture is taken from. */
    internal var homeLayer: GraphicsLayer? = null

    /** Set by HomeScreen: the wallpaper's film shows through Home now (its SurfaceView, not in the picture). */
    internal var filmShowing: () -> Boolean = { false }

    /** Set by HomeScreen: the still picture Home shows where the film was once Aerial is back. */
    internal var stillBackdrop: () -> ImageBitmap? = { null }

    /** The one picture of Home; null when there is none. */
    internal var snapshot by mutableStateOf<ImageBitmap?>(null)
        private set
    /** The film showed through Home as the picture was taken: its still goes under the picture. */
    private var snapshotOverFilm = false
    private var snapshotAt = 0L
    /** 1 while the zoom shrinks over the picture; down to 0 as it fades into live Home. */
    internal var snapshotAlpha by mutableFloatStateOf(1f)
        private set
    /** The zoom has landed and the picture fades into live Home: the face stays drawn at the tile. */
    internal var handingOver by mutableStateOf(false)
        private set

    /**
     * Takes the picture of Home once the zoom has grown and holds still over the window, called by
     * [AppLaunchZoom]'s grow before [settleCover] hides Home: as a hardware bitmap, so it is never
     * uploaded again on the return (Aerial's GPU caches are let go while it is stopped). Rendered
     * by the RenderThread at [snapshotSize] on the main thread's call, which waits for it (57 ms on
     * MiTV-AZFP0). Taken at the click it stalled the main thread right where the grow starts
     * (the first frame's vsync came 50 ms late, then frames of 85-230 ms); here the overlay no longer
     * moves, so a stall costs no visible frame. Home is as it stands then: the other tiles fallen
     * back with the zoom, as it will look at the start of the return.
     *
     * Not taken when [wantsLateSnapshot] says no (Aerial already stopped behind the app, the zoom
     * went back into its tile, the session is over); the return then takes the path without a
     * picture. Android 9 and later only (Bitmap.createBitmap of a Picture); before it, no picture.
     * [id] is the session the grow belongs to; a newer one takes nothing. Suspends for [STILL_FRAMES]
     * frames when the wallpaper's film shows through Home (see [homeStill]); [homeStill] is
     * cleared on every way out, cancellation included.
     */
    internal suspend fun captureHomeLate(id: Long) {
        val s = session
        if (s == null || s.id != id) return
        if (snapshot != null) return
        if (!wantsLateSnapshot(s)) {
            DiagLog.i("app zoom: no picture of Home (phase ${s.phase}, covered ${s.covered}, retracted ${s.retracted}); the return waits for Home")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (homeLayer?.takeIf { !it.isReleased } == null) return
        // With the wallpaper's film showing through Home (held in front by the pause hold), Home is
        // drawn for the picture as it is once the film has been let go, the still picture and the
        // plate: the film is not in the picture anyway (its own plane), and waiting for the film's
        // release to do that put the picture after the app had covered Aerial in one run of six
        // (the release is a blocking call on the main thread). Two frames for the layer to be recorded again.
        val still = runCatching { filmShowing() }.getOrDefault(false) && runCatching { stillBackdrop() }.getOrNull() != null
        try {
            if (still) {
                // The zoom holds over the whole window here, so these frames record Home as it will
                // be seen on the return without drawing it: no full-screen fill, tiles or text for
                // frames nobody sees. Written with homeStill in one go, so one frame sees both.
                homeUnseen = SKIP_UNSEEN_HOME_DRAW && homeUnseenUnderGrownZoom(session, zoomCovers())
                homeStill = true
                repeat(STILL_FRAMES) { withFrameNanos { } }
            }
            // The wait may have been long (a slow frame, the activity stopped): the same question again.
            val now = session
            if (now == null || now.id != id || snapshot != null || !wantsLateSnapshot(now)) {
                if (still) DiagLog.i("app zoom: no picture of Home (the session moved on while the film was taken out of it)")
                return
            }
            takeSnapshot(now, hidFilm = still)
        } finally {
            // Also when cancelled in the wait (the track changed): Home is drawn as it was.
            homeStill = false
            homeUnseen = false
        }
    }

    private fun takeSnapshot(s: LaunchSession, hidFilm: Boolean) {
        val layer = homeLayer?.takeIf { !it.isReleased } ?: return
        val size = layer.size
        if (size.width <= 0 || size.height <= 0) return
        val started = SystemClock.uptimeMillis()
        val (w, h) = snapshotSize(size.width, size.height)
        val bitmap = runCatching {
            Bitmap.createBitmap(LayerPicture(layer), w, h, Bitmap.Config.HARDWARE)
        }.onFailure { DiagLog.i("app zoom: no picture of Home (${it.javaClass.simpleName})") }.getOrNull() ?: return
        // Taken with the film out of it: the still picture is in the picture already, nothing goes under it.
        snapshotOverFilm = !hidFilm && runCatching { filmShowing() }.getOrDefault(false)
        snapshot = bitmap.asImageBitmap()
        snapshotAt = SystemClock.uptimeMillis()
        snapshotAlpha = 1f
        // The time limit while away: one timer for the one picture, taken off when it is let go.
        mainHandler.removeCallbacks(expire)
        mainHandler.postDelayed(expire, SNAPSHOT_AWAY_MAX_MS)
        DiagLog.i("app zoom: picture of Home ${w}x$h (${w * h * 4 / 1024} KB) in ${snapshotAt - started} ms, " +
            "${snapshotAt - s.source.requestedAt} ms after the click, phase ${s.phase}" +
            (if (hidFilm) ", film taken out of it" else if (snapshotOverFilm) ", over the film" else "") +
            (if (pauseHeld) ", pause held" else ""))
    }

    /**
     * Lets the picture go ([reason] for the log). No longer drawn from this call on; its pixels are
     * freed SNAPSHOT_RECYCLE_DELAY_MS later, never inside a draw or a frame that may still use it.
     * Ending in the middle of a return, Home is drawn again at once under whatever still moves.
     */
    internal fun releaseSnapshot(reason: String) {
        val shot = snapshot ?: return
        snapshot = null
        handingOver = false
        snapshotAlpha = 1f
        DiagLog.i("app zoom: picture of Home let go after ${(SystemClock.uptimeMillis() - snapshotAt) / 1000} s ($reason)")
        mainHandler.removeCallbacks(expire)
        if (session?.phase == LaunchPhase.Returning) showHome()
        val bitmap = shot.asAndroidBitmap()
        // Not at once: the RenderThread may still be drawing the frame before this one with the
        // hardware bitmap. By then the next frame has replaced that display list.
        mainHandler.postDelayed({ if (!bitmap.isRecycled) bitmap.recycle() }, SNAPSHOT_RECYCLE_DELAY_MS)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val expire = Runnable { snapshotExpired() }

    /** Kept longer than [SNAPSHOT_AWAY_MAX_MS] (the timer set as it was taken): let go. */
    internal fun snapshotExpired() {
        if (snapshot != null && snapshotStale(SystemClock.uptimeMillis() - snapshotAt)) releaseSnapshot("away too long")
    }

    /** Bytes the picture holds now, for the log; 0 without one. */
    internal fun snapshotBytes(): Int = snapshot?.let { it.width * it.height * 4 } ?: 0

    /**
     * The picture to draw under the zoom now: only on a return from away ([keepsHomeSnapshot] keeps
     * it from the click), never while the zoom grows (Home is live then) or covers the window.
     */
    internal fun snapshotToDraw(): ImageBitmap? {
        val shot = snapshot ?: return null
        val s = session
        if (s?.zoomTrack != ZoomTrack.Shrink || !s.leftHome) return null
        if (shot.asAndroidBitmap().isRecycled) return null
        return shot
    }
    internal val stillUnderSnapshot: Boolean get() = snapshotOverFilm && homeHidden

    // ---- The hold at full screen ----

    /**
     * A key while the grown zoom waits for the app to cover Aerial ([keyEndsHold]): the zoom goes back
     * into its tile as for a failed launch, so a key never meets an overlay it cannot reach. True if
     * the hold ended (the caller swallows that key's press). The hold also ends, by itself, when Aerial
     * stops (the full-screen frame stays for the return), at the request's timeout, the cover timeout,
     * on a cancel (screen off, screensaver, Home on Home), and by [dropIfStale].
     */
    internal fun endHoldOnKey(): Boolean {
        val s = session
        if (!keyEndsHold(s, zoomProgress >= 0.999f) || s == null) return false
        DiagLog.i("app zoom: key while the grown zoom waits for the app; back into the tile (${describe()})")
        restore(s.id)
        return true
    }

    internal suspend fun fadeSnapshot() {
        animate(1f, 0f, 0f, tween(SNAPSHOT_FADE_MS, easing = Motion.EaseOut)) { value, _ ->
            if (snapshot != null) snapshotAlpha = value
        }
    }

    internal fun startHandOver() {
        handingOver = true
        snapshotAlpha = 1f
        showHome()
    }

    override fun onCleared() {
        releasePause("launcher closed")
        homeStill = false
        homeUnseen = false
        releaseSnapshot("launcher closed")
        homeLayer = null
    }

    internal fun begin(
        source: LaunchSource,
        zoomFrom: ZoomSource? = null,
        coverTimeoutMs: Long = LaunchSessionMachine.ZOOM_COVER_TIMEOUT_MS,
    ): LaunchSession? =
        machine.begin(source, zoom = zoomFrom != null, coverTimeoutMs = coverTimeoutMs).also {
            if (it != null) {
                zoom = zoomFrom
                jumpZoom(0f)
                showHome()
                returnTarget = null
                // The picture of an earlier launch goes now; this zoom takes its own once it has
                // grown (captureHomeLate), never inside the click.
                releaseSnapshot(if (zoomFrom != null) "replaced" else "plain launch")
                if (zoomFrom != null) {
                    DiagLog.i("app zoom: ${source.key} grows (${describe()})")
                }
            }
            publish()
        }
    internal fun dispatched(id: Long): Boolean = machine.dispatched(id).also { publish() }
    internal fun paused() { machine.paused(); publish() }

    /** Aerial's window is hidden: the next thing seen of it, on return, is the full-screen frame. */
    internal fun stopped() {
        machine.stopped()
        publish()
        if (zoom != null && session?.phase == LaunchPhase.Background) {
            // Stopped behind the app: the dimmed full-screen artwork is what the return starts from.
            jumpZoom(1f)
            settleCover()
        }
    }
    internal fun coverTimeout(id: Long) {
        if (machine.coverTimeout(id)) DiagLog.i("app zoom: not covered in time; back into the tile (${describe()})")
        publish()
    }
    internal fun resumed() { resumedAt = SystemClock.uptimeMillis(); machine.resumed(); publish() }

    /** When Aerial last resumed (onResume), for the log of how long the shrink took to begin. */
    private var resumedAt = 0L
    internal fun sinceResumedMs(): Long = SystemClock.uptimeMillis() - resumedAt
    internal fun restore(id: Long) { machine.restore(id); publish() }
    internal fun finish(id: Long) { machine.finish(id); publish() }
    internal fun suppressMotion(keepFocus: Boolean = false) { machine.suppressMotion(keepFocus); publish() }
    internal fun cancel() { machine.cancel(); publish() }
    internal fun remainingRequestMs(): Long = session?.let {
        (LaunchSessionMachine.REQUEST_TIMEOUT_MS - (SystemClock.uptimeMillis() - it.source.requestedAt)).coerceAtLeast(0)
    } ?: 0

    /**
     * Coming to the front (onStart, or onResume without one) with a zoom return ahead: from now
     * until the shrink is over, the heavy work of coming back waits. True if quiet now.
     */
    internal fun holdForReturn(): Boolean {
        if (returnQuiet) return true
        if (!session.zoomReturnAhead) return false
        returnQuiet = true
        quietSince = SystemClock.uptimeMillis()
        return true
    }

    /**
     * Aerial has just paused (onPause, after [paused]): if its zoom is still growing, Home's reaction
     * to the pause waits for the grow's end ([pauseHeld], [holdsPauseForGrow]). True if held.
     * Called before `vm.resumed = false`, so HomeScreen never sees the pause.
     */
    internal fun holdPauseForGrow(): Boolean {
        if (pauseHeld) return true
        if (!holdsPauseForGrow(session, homeHidden)) return false
        pauseHeld = true
        pauseHeldSince = SystemClock.uptimeMillis()
        mainHandler.removeCallbacks(pauseFailsafe)
        mainHandler.postDelayed(pauseFailsafe, PAUSE_HOLD_MAX_MS)
        return true
    }

    /**
     * Lets Home see the pause (once; a second call finds nothing held). [reason] for the log.
     * Safe from any state: it only clears what [holdPauseForGrow] set.
     */
    internal fun releasePause(reason: String) {
        if (!pauseHeld) return
        pauseHeld = false
        mainHandler.removeCallbacks(pauseFailsafe)
        DiagLog.i("app zoom: pause held for ${SystemClock.uptimeMillis() - pauseHeldSince} ms, released ($reason)")
    }

    /** Ends the quiet time, whatever the zoom does; see [onQuietEnd]. */
    internal fun endQuiet(reason: String) {
        if (!returnQuiet) return
        returnQuiet = false
        DiagLog.i("app zoom: return quiet ends after ${SystemClock.uptimeMillis() - quietSince} ms ($reason)")
        onQuietEnd?.invoke(reason)
    }

    private fun publish() {
        session = machine.session
        if (snapshot != null && !keepsHomeSnapshot(session)) {
            releaseSnapshot(session?.let { "${it.phase}, track ${it.zoomTrack}" } ?: "session over")
        }
        if (session?.zoom != true && zoom != null) {
            zoom = null
            jumpZoom(0f)
            handingOver = false
        }
        if (zoom == null) showHome()
        // Home is unseen only under a zoom that holds grown: a retract, a failed launch, a return
        // or a newer session draws it again in the same frame this is written.
        if (homeUnseen && session.zoomTrack != ZoomTrack.Grow) homeUnseen = false
        if (returnQuiet && !session.zoomReturnAhead) {
            endQuiet(session?.let { "${it.phase}, track ${it.zoomTrack}" } ?: "session over")
        }
        // Whatever ended the grow (the stop, the resume, a failed launch, a retract, a cancel, a
        // newer launch) lets Home see the pause: nothing held outlives the state it was held for.
        if (pauseHeld && !holdsPauseForGrow(session, homeHidden)) {
            releasePause(session?.let { "${it.phase}, track ${it.zoomTrack}, covered ${it.covered}" } ?: "session over")
        }
    }

    private fun jumpZoom(to: Float) {
        zoomGeneration++
        zoomProgress = to
        zoomVelocity = 0f
    }

    internal fun showHome() {
        if (homeHidden) homeHidden = false
        if (homeUnseen) homeUnseen = false
    }

    /** The overlay holds still over the whole window: Home under it is left undrawn. */
    internal fun settleCover() {
        val covers = zoom != null && zoomCovers()
        if (homeHidden != covers) homeHidden = covers
        // The grow has ended (or the stop settled it): Home is not drawn under the overlay now, or
        // is left as it is; either way the pause it was spared can be seen. Not before the picture
        // of Home was taken (the grow takes it first).
        releasePause("grow ended")
    }

    internal suspend fun animateZoom(to: Float, spec: AnimationSpec<Float>) {
        val generation = ++zoomGeneration
        animate(zoomProgress, to, zoomVelocity, spec) { value, velocity ->
            if (generation == zoomGeneration) {
                zoomProgress = value
                zoomVelocity = velocity
            }
        }
        if (generation == zoomGeneration) {
            zoomProgress = to
            zoomVelocity = 0f
        }
    }

    /**
     * The return's spring to 0 (critically damped, [SHRINK_STIFFNESS], from the progress and
     * velocity it has), stepped by hand so that a slow frame cannot make it jump: each frame
     * advances it by [shrinkStepSeconds], at most two vsyncs while it is young. Compose's own
     * spring takes a frame's whole interval, and the first frames after the window comes back are
     * the slowest (103 and 116 ms on MiTV-AZFP0): that is why the shrink waited for short frames
     * before it started. [startNanos]: the frame time the wait ended in (that frame is the
     * spring's start), or null to start at the next frame. Ends like [animateZoom]: overtaken by a
     * jump or a newer run it writes nothing more; cancelled it stays where it is.
     */
    internal suspend fun animateShrink(startNanos: Long?) {
        val generation = ++zoomGeneration
        val spring = CriticalSpring(zoomProgress, zoomVelocity)
        var first = startNanos ?: -1L
        var last = startNanos ?: -1L
        while (true) {
            val now = withFrameNanos { it }
            if (generation != zoomGeneration) return
            if (last < 0L) {
                first = now
                last = now
                continue
            }
            val gapMs = (now - last) / 1_000_000L
            val sinceMs = (now - first) / 1_000_000L
            last = now
            spring.step(shrinkStepSeconds(gapMs, sinceMs), SHRINK_STIFFNESS)
            if (spring.settled(SHRINK_SETTLE)) {
                zoomProgress = 0f
                zoomVelocity = 0f
                return
            }
            zoomProgress = spring.value
            zoomVelocity = spring.velocity
        }
    }

    /** Clears the overlay at once for a return without motion (Drop). */
    internal fun dropZoom() {
        zoom = null
        jumpZoom(0f)
        handingOver = false
        releaseSnapshot("dropped")
        showHome()
        releasePause("dropped")
    }

    /** One line of the zoom's state, for the log. */
    internal fun describe(): String {
        val s = session
        return "phase ${s?.phase}, covered ${s?.covered}, retracted ${s?.retracted}, track ${s.zoomTrack}, " +
            "progress ${"%.2f".format(zoomProgress)}, zoom ${zoom != null}, home hidden $homeHidden, unseen $homeUnseen, quiet $returnQuiet, pause held $pauseHeld, " +
            "picture ${snapshot?.let { "${it.width}x${it.height}" } ?: "none"}"
    }

    /**
     * Aerial is resumed and focused: an overlay still up now is left over, unless the zoom is
     * opening an app (it holds the screen until the request resolves). Seen on MiTV-AZFP0
     * (2026-10-30): the full-screen artwork of YouTube stayed over Home after a return, and Home
     * presses did not clear it. Logs the state it found, so the missed transition can be traced.
     */
    internal fun dropIfStale(reason: String): Boolean {
        if (zoom == null && zoomProgress == 0f && !homeHidden) return false
        val s = session
        if (s != null && (s.phase == LaunchPhase.Opening || s.phase == LaunchPhase.Requested) &&
            SystemClock.uptimeMillis() - s.source.requestedAt < LaunchSessionMachine.REQUEST_TIMEOUT_MS + 800
        ) return false
        DiagLog.i("app zoom: dropped a left-over overlay ($reason; ${describe()})")
        machine.cancel()
        publish()
        dropZoom()
        returnTarget = null
        return true
    }

    /** Where the overlay is now, in root pixels; null while nothing is drawn. */
    internal fun currentZoomFrame(): ZoomFrame? {
        val from = zoom ?: return null
        val window = zoomWindow ?: return null
        val track = session.zoomTrack
        val p = zoomProgress
        if (track == ZoomTrack.Off || track == ZoomTrack.Drop) return null
        // Landed: kept on the tile while the picture of Home fades, so the live tile shows through it.
        if (p <= 0.001f && track != ZoomTrack.Grow && !handingOver) return null
        // Back into the tile as it is now (scrolled, focused), not where it was at the click.
        val tile = if (track == ZoomTrack.Grow) from.rect else returnTarget ?: from.rect
        val radius = from.radius * tile.width / from.rect.width
        return zoomFrame(tile, window, p, radius)
    }

    /** The overlay hides the whole window: nothing under it needs drawing. */
    internal fun zoomCovers(): Boolean {
        val window = zoomWindow ?: return false
        if (zoomProgress < 0.999f) return false
        val frame = currentZoomFrame() ?: return false
        return frame.rect.left <= window.left + 0.5f && frame.rect.top <= window.top + 0.5f &&
            frame.rect.right >= window.right - 0.5f && frame.rect.bottom >= window.bottom - 0.5f
    }
}

internal val LocalAppLaunchTransitions = staticCompositionLocalOf<AppLaunchTransitions?> { null }

/**
 * Open: the zoom grows on Motion.Drawer, which starts less abruptly than EaseOut (a frame dropped
 * in the first 50 ms is a 1.5x step of the face's size instead of 2x) and is 97% of the way at
 * about 230 ms. The app is asked for at [ZOOM_DISPATCH_MS], on a timer whatever the frames do.
 */
private const val ZOOM_OPEN_MS = 400

/**
 * Past 97% of the grow's way (2.5% of its size left): the start request (a binder call on the main
 * thread), Aerial's pause about 110 ms later (MiTV-AZFP0) and the app's cold start then fall on the
 * end of the grow, where a late frame no longer shows, instead of in its visible part.
 */
internal const val ZOOM_DISPATCH_MS = 240L

/**
 * Return: critically damped (the face lands on the tile without a wobble, SHRINK_STIFFNESS in
 * LaunchSession.kt), about 420 ms of wall time to within 3 px (this threshold: 0.2% of the way).
 * It starts from rest, so its first frames move least: those are the ones still most likely to
 * be slow just after the window comes back, and its steps are clamped there (shrinkStepSeconds).
 */
private const val SHRINK_SETTLE = 0.002f

/**
 * What lies under the zoom, in a layer of its own: while the zoom grows or shrinks, Home's layer is
 * drawn as it is, from its display list, with no clip that would change it (and re-record it, or
 * put a coverage test into every one of its draws on the GPU) every frame. Not drawn at all while
 * the zoom holds still over the whole window, or shrinks back over the picture of Home
 * ([AppLaunchTransitions.homeHidden]). Only Aerial's own content: the video wallpaper's SurfaceView
 * is its own plane. Home is recorded into [AppLaunchTransitions.homeLayer], which the picture of
 * Home is taken from once the zoom has grown ([AppLaunchTransitions.captureHomeLate]); recording
 * into it is what drawing Home was before, so it costs nothing more a frame.
 */
@Composable
internal fun LaunchZoomHome(transitions: AppLaunchTransitions, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    DisposableEffect(transitions, layer) {
        transitions.homeLayer = layer
        onDispose { if (transitions.homeLayer === layer) transitions.homeLayer = null }
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawWithContent {
                if (!transitions.homeHidden) {
                    layer.record { this@drawWithContent.drawContent() }
                    // Recorded for the picture, not drawn: the overlay hides all of it (homeUnseen).
                    if (drawsHomeLayer(hidden = false, unseen = transitions.homeUnseen)) drawLayer(layer)
                }
            }
            .graphicsLayer(),
    ) { content() }
}

/** [layer] as a Picture, for Bitmap.createBitmap: drawn scaled to the bitmap's size by the platform. */
private class LayerPicture(private val layer: GraphicsLayer) : Picture() {
    override fun beginRecording(width: Int, height: Int): Canvas = Canvas()
    override fun endRecording() {}
    override fun getWidth(): Int = layer.size.width
    override fun getHeight(): Int = layer.size.height
    override fun requiresHardwareAcceleration(): Boolean = true
    override fun draw(canvas: Canvas) {
        CanvasDrawScope().draw(
            Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas),
            Size(layer.size.width.toFloat(), layer.size.height.toFloat()),
        ) { drawLayer(layer) }
    }
}

/**
 * Aerial's half of an app's opening and closing, as tvOS draws it: the tile's own artwork grows from
 * the tile to full screen, the app is started on the way, and on return it shrinks from full screen
 * back into the tile, which then settles at its focused size. The app's real window cannot be
 * captured or moved by an ordinary app (that needs the system's remote animation permission,
 * signature|privileged), so the return starts from Aerial's own full-screen frame of the artwork,
 * never from a picture of the other app. One image draw a frame; nothing runs while idle.
 */
@Composable
internal fun AppLaunchZoom(transitions: AppLaunchTransitions, modifier: Modifier = Modifier) {
    val windowInfo = LocalWindowInfo.current
    // Only what the driver is keyed on: the session's other changes (the intent going out, the
    // pause, the stop) do not recompose this.
    val key by remember(transitions) { derivedStateOf { transitions.session?.let { it.id to it.zoomTrack } } }
    val id = key?.first
    val track = key?.second ?: ZoomTrack.Off
    LaunchedEffect(id, track) {
        id ?: return@LaunchedEffect
        when (track) {
            ZoomTrack.Off -> Unit
            ZoomTrack.Grow -> grow(transitions, id)
            // Kept at 0 (drawn as nothing): a later stop brings the full-screen frame back for the return.
            ZoomTrack.Retract -> {
                transitions.showHome()
                transitions.animateZoom(0f, spring(dampingRatio = 1f, stiffness = 420f))
            }
            ZoomTrack.Shrink -> {
                shrink(transitions, windowInfo, settle = transitions.session?.leftHome == true)
                if (transitions.snapshotToDraw() != null) handOver(transitions)
                transitions.finish(id)
            }
            ZoomTrack.Drop -> {
                DiagLog.i("app zoom: back without motion (retracted, navigated or window changed)")
                transitions.dropZoom()
                // Let the focus restoration see Returning before the session ends.
                repeat(3) { withFrameNanos { } }
                transitions.finish(id)
            }
        }
    }
    // Always in place, so its window is measured before a click; the drawing layer only while a
    // zoom is up, so nothing of a finished zoom can be left in a display list.
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val at = it.positionInRoot()
                val window = Rect(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
                if (transitions.zoomWindow != window) transitions.zoomWindow = window
            },
    ) {
        val zoom = transitions.zoom
        if (zoom != null) ZoomLayer(transitions, zoom)
    }
}

/**
 * Tile to full screen, then held there (Home undrawn under it) while the app opens. The last frame
 * is the tile's own artwork, dimmed ([fullScreenLight]), never a flat colour: it becomes the system's
 * snapshot of Aerial. The hold ends when Aerial stops (the return starts from this frame), on a key
 * ([AppLaunchTransitions.endHoldOnKey]), at the request or cover timeout, or on any cancel.
 */
private suspend fun grow(transitions: AppLaunchTransitions, id: Long) {
    if (transitions.zoomProgress < 0.999f) {
        val tally = FrameTally(transitions.session?.source?.requestedAt)
        coroutineScope {
            val counting = launch { tally.run() }
            transitions.animateZoom(1f, tween(ZOOM_OPEN_MS, easing = Motion.Drawer))
            counting.cancel()
            // The frame that ends the animation resumes this before the tally's own wait for it does
            // (it asked for the frame first), so it was never counted: the gap before it is added.
            tally.end()
        }
        DiagLog.i("app zoom: grown in ${tally.summary()}")
        // The overlay holds still over the window now: the one moment a stall shows no frame.
        // Before settleCover, which stops Home being drawn (its layer stays as last recorded) and
        // lets Home see the pause it was spared (pauseHeld).
        transitions.captureHomeLate(id)
    }
    transitions.settleCover()
}

/** What [settleFrames] saw: [log] for the log, [lastFrameNanos] the frame clock's time of the last frame (null: none). */
private class Settled(val log: String, val lastFrameNanos: Long?)

/**
 * Frames until [settled] (frames so far, the last interval, focus) or [maxMs]; a line for the log.
 * The frame it ends in is returned: the shrink's spring starts there.
 */
private suspend fun settleFrames(focused: () -> Boolean, maxMs: Long, settled: (Int, Long, Boolean) -> Boolean): Settled {
    val since = SystemClock.uptimeMillis()
    var frames = 0
    var last = 0L
    var lastNanos: Long? = null
    var interval = Long.MAX_VALUE
    var longest = 0L
    // When the window's focus was first seen, ms into the wait (0: it had it already), for the log.
    var focusAt = if (focused()) 0L else -1L
    while (SystemClock.uptimeMillis() - since < maxMs) {
        val nanos = withFrameNanos { it }
        val now = nanos / 1_000_000L
        if (frames > 0) {
            interval = now - last
            if (interval > longest) longest = interval
        }
        last = now
        lastNanos = nanos
        frames++
        val focus = focused()
        if (focusAt < 0L && focus) focusAt = SystemClock.uptimeMillis() - since
        if (settled(frames, interval, focus)) break
    }
    return Settled(
        "$frames frames in ${SystemClock.uptimeMillis() - since} ms (longest $longest ms, focus ${focused()}" +
            (if (focusAt >= 0L) ", seen at $focusAt ms" else "") + ")", lastNanos,
    )
}

/**
 * Full screen back into the tile. [settle]: Aerial was away. With the picture of Home taken as the
 * zoom had grown, the shrink starts over it as soon as Aerial has the focus and the window has
 * drawn a frame ([snapshotSettled]): Home is not drawn until the zoom has landed, and its slow
 * first frames after the stop (60-130 ms on MiTV-AZFP0) are not waited for: the spring takes a
 * clamped step in them ([shrinkStepSeconds]) and starts in the frame the wait ended in. Without
 * a picture (Android 8 and older, taken too late or not at all, kept too long, let go for
 * memory), Home is drawn again under the still full-screen frame first and the shrink starts once
 * its frames are short again ([returnSettled]), or after RETURN_SETTLE_MAX_MS. A failed launch
 * (never away) turns back at once, taking over the grow's velocity.
 */
private suspend fun shrink(transitions: AppLaunchTransitions, windowInfo: WindowInfo, settle: Boolean) {
    val overPicture = transitions.snapshotToDraw() != null
    if (!overPicture) transitions.showHome()
    var still = ""
    var startNanos: Long? = null
    if (settle) {
        // How long after onResume this driver began (the first composition frame after the stop is
        // the slow one: the window's surface is made again), then the wait itself, with when the
        // focus was seen: whether the focus or the frames set the shrink's start.
        val began = transitions.sinceResumedMs()
        val focused = { windowInfo.isWindowFocused }
        val waited = if (overPicture) settleFrames(focused, SNAPSHOT_SETTLE_MAX_MS, ::snapshotSettled)
        else settleFrames(focused, RETURN_SETTLE_MAX_MS, ::returnSettled)
        startNanos = waited.lastFrameNanos
        still = "began $began ms after onResume, " +
            (if (overPicture) "over the picture of Home after " else "after still ") + waited.log + ", "
    }
    val tally = FrameTally(null)
    coroutineScope {
        val counting = launch { tally.run() }
        transitions.animateShrink(startNanos)
        counting.cancel()
        tally.end()
    }
    DiagLog.i("app zoom: shrunk ${still}in ${tally.summary()}")
}

/**
 * The zoom has landed on its tile over the picture of Home: live Home is drawn again under the
 * picture, which stays whole while Home's first, slow frames pass (nothing moves), then fades into
 * it with the face on the tile, so the live tile shows through both. The picture is let
 * go as the session ends (finish). Two full-screen draws a frame only for these few hundred ms.
 */
private suspend fun handOver(transitions: AppLaunchTransitions) {
    transitions.startHandOver()
    val wait = settleFrames({ true }, HANDOVER_MAX_MS, ::returnSettled).log
    val tally = FrameTally(null)
    coroutineScope {
        val counting = launch { tally.run() }
        transitions.fadeSnapshot()
        counting.cancel()
        tally.end()
    }
    DiagLog.i("app zoom: live Home under the picture after $wait; faded in ${tally.summary()}")
}

/** Frames seen while a zoom moves, for the log (the frame clock's own times, not gfxinfo's). */
private class FrameTally(
    /** SystemClock.uptimeMillis of the click, when the tally is of the grow: the frame clock shares its clock. */
    private val originMs: Long?,
) {
    private var frames = 0
    private var first = 0L
    private var last = 0L
    private var longest = 0L
    private var over20 = 0

    /** Ms from the last counted frame to [end], or -1 before it. */
    private var endGap = -1L

    /** Frames that came 40 ms or more after the one before, "at ms after the click/first frame: gap ms". */
    private val late = ArrayList<String>()

    suspend fun run() {
        while (true) {
            val now = withFrameNanos { it }
            if (frames > 0) {
                val gap = (now - last) / 1_000_000L
                if (gap > longest) longest = gap
                if (gap > 20) over20++
                if (gap >= 40 && late.size < 8) late.add("${(now / 1_000_000L) - (originMs ?: (first / 1_000_000L))}: $gap")
            } else first = now
            last = now
            frames++
        }
    }

    /**
     * Called as the animation under measure returns. The frame that ends it is resumed ahead of
     * [run]'s wait for the same frame (the animation asked for it first), so [run] is cancelled
     * before it sees it: the time since the last frame it did see is the animation's last gap,
     * which the counts above leave out. The frame clock and uptimeMillis share a clock.
     */
    fun end() {
        if (frames > 0) endGap = SystemClock.uptimeMillis() - last / 1_000_000L
    }

    fun summary(): String {
        val start = originMs?.let { ", first frame ${first / 1_000_000L - it} ms after the click" } ?: ""
        val slow = if (late.isEmpty()) "" else ", late frames (at ms: gap ms) ${late.joinToString("; ")}"
        val ended = if (endGap < 0) "" else ", ended ${endGap} ms after the last counted frame" +
            (originMs?.let { " (${last / 1_000_000L - it + endGap} ms after the click)" } ?: "")
        return "${(last - first) / 1_000_000L} ms, $frames frames, longest gap $longest ms, $over20 over 20 ms$start$slow$ended"
    }
}

/**
 * The zoom's own layer: each of its frames re-records these draws and nothing else. On a return from
 * away, the picture of Home first (with the film's still under it where the film showed through as
 * it was taken), then the face: one full-screen draw a frame while Home itself is not drawn.
 */
@Composable
private fun ZoomLayer(transitions: AppLaunchTransitions, zoom: ZoomSource) {
    // Flattened once for this zoom (a tile-sized bitmap, never a full-screen one).
    val art = remember(zoom) { ZoomArt.of(zoom.art) }
    val fallbackClip = remember { Path() }
    Spacer(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawBehind {
                val covers = transitions.zoomCovers()
                transitions.snapshotToDraw()?.takeIf { !covers }?.let { shot ->
                    val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
                    if (transitions.stillUnderSnapshot) {
                        transitions.stillBackdrop()?.takeIf { !it.asAndroidBitmap().isRecycled }
                            ?.let { drawImage(it, dstSize = full, filterQuality = FilterQuality.Low) }
                    }
                    drawImage(
                        shot, srcSize = IntSize(shot.width, shot.height), dstSize = full,
                        alpha = transitions.snapshotAlpha, filterQuality = FilterQuality.Low,
                    )
                }
                val frame = transitions.currentZoomFrame() ?: return@drawBehind
                val origin = transitions.zoomWindow?.topLeft ?: Offset.Zero
                val r = frame.rect.translate(-origin.x, -origin.y)
                val p = transitions.zoomProgress.coerceIn(0f, 1f)
                val light = fullScreenLight(p)
                val alpha = if (transitions.handingOver) transitions.snapshotAlpha else 1f
                if (art != null) art.draw(this, r, frame.radius, light, alpha)
                else drawLayered(zoom.art, r, frame.radius, light, alpha, fallbackClip)
            },
    )
}

/**
 * The tile's artwork as one bitmap, drawn as one rounded rect filled by a bitmap shader: analytic
 * anti-aliased corners in a single pass, with no clip (a clip path is a stencil or a coverage mask
 * on HWUI). Per frame only the shader's matrix changes; the dimming is one lighting filter
 * (out = art × light) picked from a few dozen steps: nothing full-screen is allocated, and a filter
 * only when its step changes. The artwork is always what is drawn, never a flat colour.
 */
private class ZoomArt(private val bitmap: Bitmap) {
    private val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = this@ZoomArt.shader }
    private var step = -1

    fun draw(scope: DrawScope, r: Rect, radius: Float, light: Float, alpha: Float) {
        bounds.set(r.left, r.top, r.right, r.bottom)
        val a = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        matrix.setScale(r.width / bitmap.width, r.height / bitmap.height)
        matrix.postTranslate(r.left, r.top)
        shader.setLocalMatrix(matrix)
        val s = (light.coerceIn(0f, 1f) * LIGHT_STEPS).roundToInt()
        if (s != step) {
            step = s
            paint.colorFilter = if (s >= LIGHT_STEPS) null else {
                val c = (255 * s.toFloat() / LIGHT_STEPS).roundToInt().coerceIn(0, 255)
                LightingColorFilter(android.graphics.Color.rgb(c, c, c), 0)
            }
        }
        paint.alpha = a
        scope.drawIntoCanvas { canvas ->
            if (radius > 0.5f) canvas.nativeCanvas.drawRoundRect(bounds, radius, radius, paint)
            else canvas.nativeCanvas.drawRect(bounds, paint)
        }
    }

    companion object {
        /** 64 steps: under one 8-bit level of the artwork's light between neighbours. */
        private const val LIGHT_STEPS = 64

        /** Null where the artwork cannot be read back (a hardware bitmap): the layered draw then. */
        fun of(art: TileArt): ZoomArt? = runCatching {
            val back = art.back.asAndroidBitmap()
            if (android.os.Build.VERSION.SDK_INT >= 26 && back.config == Bitmap.Config.HARDWARE) return@runCatching null
            val fore = art.fore?.asAndroidBitmap()
                ?: return@runCatching ZoomArt(back)
            if (android.os.Build.VERSION.SDK_INT >= 26 && fore.config == Bitmap.Config.HARDWARE) return@runCatching null
            val flat = Bitmap.createBitmap(back.width, back.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(flat)
            val filter = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(back, null, android.graphics.Rect(0, 0, back.width, back.height), filter)
            val at = art.foreAt
            val w = back.width
            val h = back.height
            canvas.drawBitmap(
                fore, null,
                android.graphics.Rect(
                    (at.left * w).roundToInt(), (at.top * h).roundToInt(),
                    (at.left * w).roundToInt() + (at.width * w).roundToInt(),
                    (at.top * h).roundToInt() + (at.height * h).roundToInt(),
                ),
                filter,
            )
            ZoomArt(flat)
        }.getOrNull()
    }
}

/** The earlier draw, for artwork [ZoomArt] cannot flatten: clipped, the two images scaled as a whole. */
private fun DrawScope.drawLayered(art: TileArt, r: Rect, radius: Float, light: Float, alpha: Float, clip: Path) {
    val dim = if (light < 0.999f) ColorFilter.tint(Color(light, light, light), BlendMode.Modulate) else null
    clip.reset()
    clip.addRoundRect(RoundRect(r, CornerRadius(radius)))
    clipPath(clip) {
        translate(r.left, r.top) {
            scale(r.width / art.back.width, r.height / art.back.height, pivot = Offset.Zero) {
                drawImage(art.back, dstSize = IntSize(art.back.width, art.back.height), alpha = alpha, filterQuality = FilterQuality.Low, colorFilter = dim)
                art.fore?.let { fore ->
                    val at = art.foreAt
                    val w = art.back.width
                    val h = art.back.height
                    drawImage(
                        fore,
                        dstOffset = IntOffset((at.left * w).roundToInt(), (at.top * h).roundToInt()),
                        dstSize = IntSize((at.width * w).roundToInt(), (at.height * h).roundToInt()),
                        alpha = alpha,
                        filterQuality = FilterQuality.Low,
                        colorFilter = dim,
                    )
                }
            }
        }
    }
}
