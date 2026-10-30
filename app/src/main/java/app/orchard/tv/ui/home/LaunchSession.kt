package app.orchard.tv.ui.home

import androidx.compose.ui.geometry.Rect

/** The grid anchor; folder contents retain their own scroll and focus while Home is alive. */
data class HomeLaunchOrigin(
    val folderId: String?,
    val homeIndex: Int,
    val scrollDp: Float,
    val touchScrolled: Boolean,
)

/**
 * Only Aerial's own visual state: a request is never treated as an external app's first frame.
 * Opening: Aerial's zoom runs and the Activity intent is not sent yet (zoom sessions only).
 */
internal enum class LaunchPhase { Opening, Requested, Background, Returning }
internal enum class LaunchEntryKind { App, Shortcut, Source, StockHome }

internal data class LaunchSource(
    val key: String,
    val component: String,
    val kind: LaunchEntryKind,
    val boundsInWindow: Rect?,
    val folderId: String?,
    val homeIndex: Int,
    val scrollDp: Float,
    val touchScrolled: Boolean,
    val windowWidth: Int,
    val windowHeight: Int,
    val requestedAt: Long,
)

internal data class LaunchSession(
    val id: Long,
    val source: LaunchSource,
    val phase: LaunchPhase,
    val leftHome: Boolean = false,
    val returnMotion: Boolean = true,
    val restoreFocus: Boolean = true,
    /** Aerial draws the tile's artwork growing to full screen and back (AppLaunchZoom). */
    val zoom: Boolean = false,
    /** Aerial's window stopped behind the app: the full-screen frame was last seen whole. */
    val covered: Boolean = false,
    /** Never covered in time (a translucent or dialog Activity over Aerial): the zoom went back into the tile. */
    val retracted: Boolean = false,
    /** Paused this long without being stopped, the zoom goes back into the tile (see [LaunchSessionMachine.coverTimeout]). */
    val coverTimeoutMs: Long = LaunchSessionMachine.ZOOM_COVER_TIMEOUT_MS,
)

/**
 * What Home's own composition needs of a session. The phases a zoom passes through while Aerial
 * is leaving (Opening, Requested, Background, covered or not) look the same here, so that the
 * intent going out or Aerial pausing does not recompose all of Home under the moving zoom; the
 * zoom itself and the key handling read the live session.
 */
internal fun LaunchSession.forHome(): LaunchSession =
    if (!zoom || phase == LaunchPhase.Returning) this
    else copy(phase = LaunchPhase.Requested, leftHome = false, covered = false, retracted = false)

/** Coming to the front now would shrink the zoom into its tile: hold the work of coming back until it has. */
internal val LaunchSession?.zoomReturnAhead: Boolean
    get() = this != null && zoom && returnMotion && !retracted &&
        (phase == LaunchPhase.Background || phase == LaunchPhase.Returning)

/**
 * Aerial has paused while its zoom is still growing (the app was asked for, the window is not yet
 * covered, the grow has not settled): what Home does about being paused (the wallpaper film letting
 * go, the recomposition that follows `resumed = false`) waits until the grow has ended, so it
 * does not run in the last, visible frames of the zoom. On MiTV-AZFP0 (logs of 2026-10-30) the
 * last counted frame of the grow came 320-470 ms after the click, with the zoom at 95-99%, and the
 * grow ended 340-690 ms later, right after Aerial's pause and the window losing focus.
 * [homeHidden]: the grow has ended and the overlay covers the window, Home is not drawn.
 * Pure, so that every way out of the hold is tested.
 */
internal fun holdsPauseForGrow(session: LaunchSession?, homeHidden: Boolean): Boolean =
    session != null && session.zoomTrack == ZoomTrack.Grow && session.phase == LaunchPhase.Background &&
        !session.covered && !homeHidden

/** The longest Home's reaction to the pause waits for the grow to end; it runs then whatever the grow did. */
internal const val PAUSE_HOLD_MAX_MS = 1_200L

/**
 * The wallpaper's film is held on its frame (the player paused, its decoder kept) from the click
 * for as long as the zoom is on its grow track: the zoom covers more of the window with every frame,
 * and a 4K decode beside the grow, the app's process start and the glass sampler (a PixelCopy or a
 * key frame every few seconds) all share one small CPU and memory bus. The hold is a pure function
 * of the session, so every way out of the grow ends it by itself: a failed launch or timeout
 * (Returning), a retract, a cancel, a newer launch. Where Aerial really leaves, the film's own
 * code lets the decoder go (LiveWallpaper); the hold only pauses, it never releases.
 */
internal fun holdsFilmForGrow(session: LaunchSession?): Boolean = session.zoomTrack == ZoomTrack.Grow

/**
 * The held film plays again when the hold ends: only if Aerial is still in front ([resumed]; where
 * it left, the film is let go by LiveWallpaper and a new player starts on the way back) and Home
 * still wants it moving ([wanted]; a key or a panel may have stopped it meanwhile).
 */
internal fun thawsFilm(resumed: Boolean, wanted: Boolean): Boolean = resumed && wanted

/**
 * Home is recorded but not drawn into the window: the overlay holds still over all of it, and the
 * only frames still to come are the ones that record Home as it will be seen on the return (the
 * film taken out of it, see [AppLaunchTransitions.homeStill]), whose drawing nobody would see. The
 * recording is the picture's source, so the layer is kept up to date; the window skips the draw
 * that would cost a full-screen fill, the tiles and the text for nothing. [covers]: the zoom
 * holds fully grown over the whole window, as [AppLaunchTransitions.zoomCovers] says.
 * Ends the moment the grow track does (a retract or a failed launch draws Home again at once).
 */
internal fun homeUnseenUnderGrownZoom(session: LaunchSession?, covers: Boolean): Boolean =
    covers && session.zoomTrack == ZoomTrack.Grow

/** Whether the window draws Home's layer this frame (it is recorded either way unless [hidden]). */
internal fun drawsHomeLayer(hidden: Boolean, unseen: Boolean): Boolean = !hidden && !unseen

/**
 * The return's still frame has had its first, heavy frames (Home re-recorded after the stop, the
 * window's surface back) and Aerial has the focus: the shrink can start. [frames] seen so far,
 * [lastFrameMs] the interval between the last two.
 */
internal fun returnSettled(frames: Int, lastFrameMs: Long, focused: Boolean): Boolean =
    focused && frames >= RETURN_SETTLE_MIN_FRAMES && lastFrameMs <= RETURN_SETTLED_FRAME_MS

/** Frames of the still full-screen frame at least, before the shrink starts. */
internal const val RETURN_SETTLE_MIN_FRAMES = 3

/** A frame this short means the main thread kept up with the display again (60 Hz: 16.7 ms). */
internal const val RETURN_SETTLED_FRAME_MS = 24L

/** The longest the shrink waits for [returnSettled]; it starts anyway after this. */
internal const val RETURN_SETTLE_MAX_MS = 260L

/**
 * Over the picture of Home ([keepsHomeSnapshot]) the shrink does not wait for Home: only for the
 * focus (the closing app's window may be over Aerial until then) and for one frame of the window
 * after the stop, whose surface and buffers are made again. It does not wait for a short frame:
 * the first frames after the stop are the slow ones (103 and 116 ms on MiTV-AZFP0), and the
 * spring takes a clamped step in them ([shrinkStepSeconds]), so they cannot make it jump. The
 * frame the wait ends in is the spring's start time, so no frame is spent twice.
 * [lastFrameMs] is kept for the shared wait loop (returnSettled reads it).
 */
@Suppress("UNUSED_PARAMETER")
internal fun snapshotSettled(frames: Int, lastFrameMs: Long, focused: Boolean): Boolean =
    focused && frames >= SNAPSHOT_SETTLE_MIN_FRAMES

internal const val SNAPSHOT_SETTLE_MIN_FRAMES = 1

/** The longest the shrink over the picture waits for [snapshotSettled]. */
internal const val SNAPSHOT_SETTLE_MAX_MS = 150L

// ---- The shrink's spring ----

/** Stiffness of the return's critically damped spring (about 420 ms to within 3 px). */
internal const val SHRINK_STIFFNESS = 380f

/**
 * The spring takes at most this long a step per frame while it is young ([SHRINK_CLAMP_WINDOW_MS]):
 * the first frames after the window comes back take 100 ms and more, and a spring that took their
 * whole time would jump 60% of the way in one frame (at 116 ms) instead of moving on at the pace
 * of two ordinary frames. Two vsyncs at 60 Hz.
 */
internal const val SHRINK_MAX_STEP_MS = 32L

/** After this long since the spring started, steps are the frames' real intervals again. */
internal const val SHRINK_CLAMP_WINDOW_MS = 300L

/** No step is longer than this whatever the clock did (a frame clock that paused, the screen off). */
internal const val SHRINK_ABSOLUTE_STEP_MS = 200L

/**
 * The time, in seconds, the shrink's spring advances for a frame [gapMs] after the last one, when
 * it has run for [sinceStartMs]. Young, a slow frame counts as [SHRINK_MAX_STEP_MS] (the spring
 * stretches over those frames instead of jumping); once the window is over, the real interval,
 * so a TV that stays slow still lands in bounded time (RETURN_QUIET_MAX_MS holds).
 */
internal fun shrinkStepSeconds(gapMs: Long, sinceStartMs: Long): Float {
    val gap = gapMs.coerceAtLeast(0L)
    val limit = if (sinceStartMs < SHRINK_CLAMP_WINDOW_MS) SHRINK_MAX_STEP_MS else SHRINK_ABSOLUTE_STEP_MS
    return gap.coerceAtMost(limit) / 1000f
}

/**
 * A critically damped spring on its way to [target], stepped by the closed form
 * x(t) = (x0 + (v0 + w x0) t) e^(-w t), so any split of a time into steps gives the same place
 * (no overshoot, whatever the step), and the frame driver may choose its step. The equation of
 * Compose's spring(dampingRatio = 1f) with unit mass. [settled] is modelled on its rest test
 * (value within the visibility threshold, velocity within a multiple of it; the factor 62.5 is
 * from memory of Compose's source, not checked against it): the tests pin the settling time to
 * about 400 ms, which is what the old spring took.
 */
internal class CriticalSpring(var value: Float, var velocity: Float, private val target: Float = 0f) {
    fun step(seconds: Float, stiffness: Float) {
        if (seconds <= 0f) return
        val w = kotlin.math.sqrt(stiffness.toDouble())
        val x0 = (value - target).toDouble()
        val v0 = velocity.toDouble()
        val c = v0 + w * x0
        val decay = kotlin.math.exp(-w * seconds)
        value = (target + (x0 + c * seconds) * decay).toFloat()
        velocity = ((v0 - w * c * seconds) * decay).toFloat()
    }

    /** Within [threshold] of the target and slow enough (Compose's velocity threshold, 62.5 times it). */
    fun settled(threshold: Float): Boolean =
        kotlin.math.abs(value - target) < threshold && kotlin.math.abs(velocity) < threshold * 62.5f
}

// ---- The picture of Home taken late ----

/**
 * The picture of Home is taken once the zoom has grown (not at the click: the capture blocked the
 * main thread for 57 ms there, right where the grow starts, and the RenderThread worked on it
 * through the first frames), while Aerial's window is still whole: the app was asked for (the
 * session is no longer Opening: its dispatch was cancelled otherwise) and Aerial has not stopped
 * behind it ([LaunchSession.covered]; its hardware layers may be let go from then on) nor gone back
 * into its tile. The overlay holds still over the whole window at that moment, so a stall costs
 * no visible frame. Otherwise no picture is taken and the return takes the path without one.
 */
internal fun wantsLateSnapshot(session: LaunchSession?): Boolean =
    session != null && keepsHomeSnapshot(session) && !session.covered && !session.retracted &&
        (session.phase == LaunchPhase.Requested || session.phase == LaunchPhase.Background)

/**
 * Once the zoom has landed, live Home is drawn under the still picture; the picture fades out once
 * Home's frames are short again ([returnSettled]: Home's first frames after the stop are its
 * slowest, 60-130 ms on MiTV-AZFP0), or after this at the latest.
 */
internal const val HANDOVER_MAX_MS = 320L

/** The picture of Home fading into live Home. */
internal const val SNAPSHOT_FADE_MS = 220

/**
 * Upper bound of a return's quiet time, from Aerial's start: the settle wait, the shrink spring
 * (about 420 ms of wall time whatever the frames do) and, over the picture of Home, the hand-over
 * and the fade, with room. What was held runs then at the latest.
 */
internal const val RETURN_QUIET_MAX_MS = 1_400L

/**
 * Back in front and focused this long, an overlay still up is left over (MainActivity drops it):
 * past the longest return, quiet time included.
 */
internal const val ZOOM_LEFTOVER_MS = 2_000L

// ---- The picture of Home a zoom keeps for its return ----

/**
 * The picture of Home a zoom takes (late: [wantsLateSnapshot]) is wanted while that zoom may still
 * come back into its tile from full screen with Aerial away meanwhile: from the click until the
 * return has handed over to live Home (a session that has not taken one yet holds nothing, and
 * ending it lets any older one go). Not for a launch that failed or timed out before Aerial left (Home
 * stayed live), a return without motion (a key, another window size, a zoom that went back into
 * its tile under a panel), or once the session is over (Home on Home, the screen off, the
 * screensaver, another launch, [AppLaunchTransitions.dropIfStale]).
 */
internal fun keepsHomeSnapshot(session: LaunchSession?): Boolean {
    if (session == null || !session.zoom || !session.returnMotion) return false
    return when (session.phase) {
        LaunchPhase.Opening, LaunchPhase.Requested, LaunchPhase.Background -> true
        LaunchPhase.Returning -> session.leftHome && !session.retracted
    }
}

/**
 * The picture's pixel size for a window [w]×[h]: two thirds a side (1920×1080 → 1280×720, 3.7 MB
 * in graphics memory against 8.3 MB whole). Rendered small rather than scaled down, so its text
 * and edges stay clean; it is only seen while the zoom shrinks over it and fades into live Home.
 */
internal fun snapshotSize(w: Int, h: Int): Pair<Int, Int> =
    Pair((w * SNAPSHOT_SCALE).toInt().coerceAtLeast(1), (h * SNAPSHOT_SCALE).toInt().coerceAtLeast(1))

internal const val SNAPSHOT_SCALE = 2f / 3f

/**
 * How long the picture is kept while Aerial is away. Back after longer, Home is drawn again under
 * the full-screen frame before the shrink, as without a picture (and the picture's clock would be
 * minutes old by then).
 */
internal const val SNAPSHOT_AWAY_MAX_MS = 3 * 60 * 1000L

/** A picture [ageMs] old is past [SNAPSHOT_AWAY_MAX_MS]. */
internal fun snapshotStale(ageMs: Long): Boolean = ageMs >= SNAPSHOT_AWAY_MAX_MS

/**
 * Stopped behind an app this long, Aerial lets go of what it can make again cheaply and holds
 * only for animation or the picker (MainActivity.releaseWhileAway): not while the app is still
 * opening, whose cold start has the CPU and the memory bus.
 */
internal const val AWAY_RELEASE_AFTER_MS = 4_000L

/**
 * The system's memory signals at which Aerial lets go of what it can make again (MainActivity's
 * onTrimMemory), the picture of Home among them: memory running low, or Aerial in the background
 * on the list to kill. Not UI_HIDDEN, sent every time an app opens over Aerial. Values of
 * ComponentCallbacks2.
 */
internal fun trimLetsGo(level: Int): Boolean = level >= TRIM_RUNNING_LOW && level != TRIM_UI_HIDDEN

private const val TRIM_RUNNING_LOW = 10
private const val TRIM_UI_HIDDEN = 20

// ---- The overlay's last frame ----

/**
 * The overlay's last frame is the tile's own artwork at full screen, dimmed by [FULL_SCREEN_DIM]'s
 * share, never a flat colour: it is the last thing Aerial draws before an app covers it, and Android
 * keeps that frame as the task snapshot it shows when Aerial comes back (a snapshot starting
 * window). A flat bright frame there would stay over Aerial's real window on the return. The most
 * light the frame has left, for the brightest artwork, at full screen.
 */
internal fun fullScreenLight(progress: Float): Float = 1f - FULL_SCREEN_DIM * progress.coerceIn(0f, 1f)

/** How much of the artwork's light is taken away at full screen (0.55 leaves 45%). */
internal const val FULL_SCREEN_DIM = 0.55f

/**
 * A key press while the zoom holds at full screen waiting for the app to cover Aerial ends the
 * hold: the app was asked for ([LaunchPhase.Requested]), the grow has landed ([grown]), Aerial is
 * not yet paused. The zoom then goes back into its tile as for a failed launch. Before the app was
 * asked for (Opening) keys are still swallowed by Home, and once Aerial is paused it gets no keys.
 */
internal fun keyEndsHold(session: LaunchSession?, grown: Boolean): Boolean =
    grown && session != null && session.zoomTrack == ZoomTrack.Grow && session.phase == LaunchPhase.Requested

/**
 * Work of coming to the front (onStart, onResume) put off while the zoom shrinks back. Each kind
 * is held once (a second hold of the same key replaces the first and keeps its place); [release]
 * runs them in the order they were first held. Pure, so the guarantee is tested: whatever is held
 * is run by the next [release] or dropped by [drop], never both, never twice.
 */
internal class HeldWork {
    private val held = LinkedHashMap<String, () -> Unit>()
    val isEmpty: Boolean get() = held.isEmpty()
    val keys: List<String> get() = held.keys.toList()

    fun hold(key: String, work: () -> Unit) { held[key] = work }

    /** Runs and forgets everything held, oldest kind first. */
    fun release() {
        if (held.isEmpty()) return
        val run = held.values.toList()
        held.clear()
        run.forEach { it() }
    }

    fun drop() = held.clear()
}

/** What Aerial's own zoom overlay does for a session; see [LaunchSession.zoomTrack]. */
internal enum class ZoomTrack {
    /** No overlay. */
    Off,
    /** Tile to full screen, and held there while the app opens over it. */
    Grow,
    /** Back into the tile without ending the session (the app never covered Aerial). */
    Retract,
    /** Full screen back into the tile, then the session ends. */
    Shrink,
    /** No motion: the overlay goes at once and the session ends. */
    Drop,
}

internal val LaunchSession?.zoomTrack: ZoomTrack
    get() = when {
        this == null || !zoom -> ZoomTrack.Off
        phase == LaunchPhase.Returning -> if (returnMotion && !retracted) ZoomTrack.Shrink else ZoomTrack.Drop
        retracted -> ZoomTrack.Retract
        else -> ZoomTrack.Grow
    }

/** Main-thread reducer kept separate from Compose and Android for bounded lifecycle handling. */
internal class LaunchSessionMachine {
    var session: LaunchSession? = null
        private set
    private var nextId = 0L

    /**
     * [zoom]: the session starts in [LaunchPhase.Opening]; the caller sends the intent at [dispatched].
     * [coverTimeoutMs]: see [LaunchSession.coverTimeoutMs] (shorter for a settings app, which may be a panel).
     */
    fun begin(source: LaunchSource, zoom: Boolean = false, coverTimeoutMs: Long = ZOOM_COVER_TIMEOUT_MS): LaunchSession? {
        // A second OK for this still-unresolved request must not send another Activity intent.
        val current = session
        if ((current?.phase == LaunchPhase.Requested || current?.phase == LaunchPhase.Opening) &&
            current.source.key == source.key &&
            source.requestedAt - current.source.requestedAt in 0 until REQUEST_TIMEOUT_MS
        ) return null
        val phase = if (zoom) LaunchPhase.Opening else LaunchPhase.Requested
        return LaunchSession(++nextId, source, phase, zoom = zoom, coverTimeoutMs = coverTimeoutMs).also { session = it }
    }

    /** True once for [id]: send the Activity intent now. False for a superseded or cancelled zoom. */
    fun dispatched(id: Long): Boolean {
        val current = session ?: return false
        if (current.id != id || current.phase != LaunchPhase.Opening) return false
        session = current.copy(phase = LaunchPhase.Requested)
        return true
    }

    fun paused() {
        // Opening is resolved by the caller first (the zoom reversed); a return in progress ends.
        session = session?.takeIf { it.phase == LaunchPhase.Requested }?.copy(
            phase = LaunchPhase.Background, leftHome = true,
        ) ?: session?.takeIf { it.phase == LaunchPhase.Background || it.phase == LaunchPhase.Opening }
    }

    /** Aerial's window stopped: the app covers it. A retracted zoom is wanted again for the return. */
    fun stopped() {
        val current = session ?: return
        if (current.phase == LaunchPhase.Background) session = current.copy(covered = true, retracted = false)
    }

    /**
     * Paused but never stopped for [id] within [ZOOM_COVER_TIMEOUT_MS]: something translucent is
     * over Aerial. True when the zoom should go back into its tile.
     */
    fun coverTimeout(id: Long): Boolean {
        val current = session ?: return false
        if (current.id != id || !current.zoom || current.phase != LaunchPhase.Background || current.covered) return false
        session = current.copy(retracted = true)
        return true
    }

    fun resumed(): Boolean {
        val current = session ?: return false
        if (current.phase != LaunchPhase.Background || !current.leftHome) return false
        session = current.copy(phase = LaunchPhase.Returning)
        return true
    }

    /** The request failed or timed out before Aerial left the front. */
    fun restore(id: Long) {
        val current = session ?: return
        if (current.id == id && (current.phase == LaunchPhase.Requested || current.phase == LaunchPhase.Opening)) {
            session = current.copy(phase = LaunchPhase.Returning)
        }
    }

    fun finish(id: Long) {
        if (session?.id == id && session?.phase == LaunchPhase.Returning) session = null
    }

    fun suppressMotion(keepFocus: Boolean = false) {
        session = session?.let { it.copy(returnMotion = false, restoreFocus = it.restoreFocus && keepFocus) }
    }
    fun cancel() { session = null }

    companion object {
        // An intent that does not leave Home gets no success claim; release the visual/request lock.
        const val REQUEST_TIMEOUT_MS = 1_200L

        /** Paused this long without being stopped: the zoom stops standing in for the app's window. */
        const val ZOOM_COVER_TIMEOUT_MS = 5_000L

        /**
         * The same for a settings app: its main page can be a panel over Aerial even with an opaque
         * theme (a window made translucent in code), and the artwork must not stand behind it long.
         * A settings page that does cover Aerial stops it well within this on MiTV-AZFP0 class TVs;
         * one that stops it later still gets its return (stopped() brings the zoom back).
         */
        const val SETTINGS_COVER_TIMEOUT_MS = 1_500L
    }
}

/** Where the zoom overlay is drawn: [rect] in root pixels with corner [radius] px. */
internal data class ZoomFrame(val rect: Rect, val radius: Float)

/**
 * The overlay at [progress] (0 tile, 1 full screen): [tile] grown uniformly until it covers
 * [window] (centred, cropped as the screen's aspect needs), corners going to square.
 */
internal fun zoomFrame(tile: Rect, window: Rect, progress: Float, radius: Float): ZoomFrame {
    val p = progress.coerceIn(0f, 1f)
    if (tile.width <= 0f || tile.height <= 0f) return ZoomFrame(window, 0f)
    val cover = maxOf(window.width / tile.width, window.height / tile.height)
    val w = tile.width * cover
    val h = tile.height * cover
    val end = Rect(window.center.x - w / 2f, window.center.y - h / 2f, window.center.x + w / 2f, window.center.y + h / 2f)
    fun lerp(a: Float, b: Float) = a + (b - a) * p
    return ZoomFrame(
        Rect(lerp(tile.left, end.left), lerp(tile.top, end.top), lerp(tile.right, end.right), lerp(tile.bottom, end.bottom)),
        radius * (1f - p),
    )
}
