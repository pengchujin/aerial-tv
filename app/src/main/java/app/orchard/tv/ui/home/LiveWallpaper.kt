package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.ui.components.FilmSlot
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Aerial
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.AerialSet
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.Aerials
import app.orchard.tv.wallpaper.Images
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** How long Home sits still before the owner's videos start moving, unless set to keep moving. */
internal const val START_AFTER_MS = 10_000L

/** A stopped film keeps its player this long, so it moves on again without loading. */
internal const val KEEP_STOPPED_MS = 2 * 60 * 1000L

/**
 * Runs [letGo] the moment Aerial stops (an app, a dream or standby in front) and returns how many
 * times it let something go, a key for the film's LaunchedEffect so it starts again on Home.
 * LaunchedEffect(play) lets the player go only at the next recomposition, and none comes once the
 * activity is stopped (Compose pauses its frame clock): on TCL MT5879, with no frame between
 * onPause and onStop, the 4K film went on decoding behind Quark's player, which lost its hardware
 * decoder to it and stuttered in software; the film's error retry then took a decoder again.
 */
@Composable
internal fun rememberStops(letGo: () -> Boolean): Int {
    var stops by remember { mutableIntStateOf(0) }
    val letGoNow by rememberUpdatedState(letGo)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && letGoNow()) stops++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return stops
}

/**
 * The film behind Home, for the screensaver that carries it on instead of starting its own
 * (HomeScreen): which film is showing, how far in, and stepping to the next or previous one.
 */
class LiveFilm {
    var film by mutableStateOf<Aerial?>(null); internal set
    internal var player: ExoPlayer? = null

    /**
     * Stopped on a frame that is not yet the wallpaper's picture, with the glass for it ready
     * (Wallpapers.useLiveFrost): the paused film is what shows, and the plate stands over it.
     */
    var stopped by mutableStateOf(false); internal set

    /** Playing on Home now, so in a few seconds it will be a few seconds on. */
    val moving: Boolean get() = player?.isPlaying == true

    /** The film has shown a frame since it was opened, so where it is now may be past the picture. */
    internal var moved = false
    val positionMs: Long get() = player?.currentPosition ?: 0L

    /** Adds a film after the one playing and goes to it; set by the player that has the film. */
    internal var insert: ((Aerial) -> Unit)? = null

    /**
     * The viewer's left and right in the screensaver carrying this film: right brings in a new
     * film (AerialSet.addNew), left goes back to one kept.
     */
    fun skip(context: android.content.Context, step: Int) {
        val p = player ?: return
        if (step < 0) p.seekToPreviousMediaItem() else insert?.invoke(AerialSet.addNew(context))
    }

    /** The film and the moment showing now, while a player has it. */
    fun spot(): Pair<String, Long>? = film?.id?.takeIf { player != null }?.let { it to positionMs }

    /**
     * Lets the decoder go, keeping the film and its place: these TVs have one 4K decoder, and a
     * screensaver of its own needs it (Xiaomi MiTV-MFTR0, TCL MT5879: a second film failed to open
     * beside the wallpaper's). The film plays again from here when Home asks for it.
     */
    fun letDecoderGo() {
        player?.run { pause(); stop() }
    }

    /** After [letDecoderGo], when nothing else came to need it: the film goes on. */
    fun goOn() {
        player?.run { if (playbackState == Player.STATE_IDLE) prepare(); play() }
    }

    companion object {
        /** Home's film, for MainActivity to hand the moment on to a screensaver it starts elsewhere. */
        var onHome: LiveFilm? = null
    }
}

/**
 * The aerial wallpaper (issue #216): Apple's film plays behind the tiles while Home is on the top
 * row (in two rows, either row: both are on the shelf, #810), keys and all; going down into the
 * grid stops it on the frame it was showing. That frame
 * becomes the wallpaper's picture (Wallpapers.useLiveFrame), so the glass plate, the grid's frosted
 * background and the next start all match what was on screen. The film starts again from that
 * frame (Wallpapers.liveSpot), even after its player has gone, so still and moving are one shot.
 *
 * The film sits in a SurfaceView under the window; [cover] is how far it shows through. While it
 * plays, HomeBackground draws neither the picture nor the plate, so Compose draws nothing full-screen
 * and the frame budget is the same as on a still Home. Kept to SDR even where the screensaver plays
 * Dolby Vision: a TV in Dolby Vision mode shows the tiles over it with Dolby's picture settings.
 */
@Composable
fun LiveWallpaper(
    moving: Boolean,
    /** False once the screensaver is up or Home is behind an app: the player's decoder goes at once. */
    keep: Boolean,
    cover: Animatable<Float, *>,
    /** The screensaver carries the film on: it keeps moving whatever the keys do. */
    hold: Boolean,
    /** Nothing over Home that blurs it: a stopped film may stand in for its picture. */
    standIn: Boolean,
    /** A panel is over Home (a menu, Control Center): what it opens on waits for it to be in. */
    panel: Boolean = false,
    /** A screensaver is up in another format than the film's (Dolby Vision chosen): the TV goes black between them. */
    otherFormatSaver: Boolean,
    /** When a key was last pressed (uptimeMillis): a slow copy of the stopped film waits for quiet. Read, not observed. */
    lastInputAt: () -> Long = { 0L },
    live: LiveFilm,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    // Frames are reaching the screen: the first one has been drawn and the film is not stopped.
    var showing by remember { mutableStateOf(false) }
    val reader = remember { FilmFrames.load(context); FrameReader(context) }
    // Plays as soon as Home shows it, and keys do not stop it (Wallpapers.keepsMoving).
    val play = moving || hold
    val inSaver by rememberUpdatedState(hold && !moving)
    val keepNow by rememberUpdatedState(keep)
    val standInNow by rememberUpdatedState(standIn)
    val panelNow by rememberUpdatedState(panel)
    val otherFormatNow by rememberUpdatedState(otherFormatSaver)
    val lastInputNow by rememberUpdatedState(lastInputAt)
    // Back from a screensaver in another format: the TV goes black as it changes mode whatever
    // shows. The film goes on from the screensaver's film and moment (owner, 2026-10-29), on black:
    // the picture, from before the screensaver, would show another shot first.
    var fresh by remember { mutableStateOf(false) }
    // The same for Home's black: read once Home is back, after the film's start has used [fresh].
    var blackForFilm by remember { mutableStateOf(false) }

    fun release() {
        reader.close()
        // Let go somewhere past its picture with no frame taken (into an app, or the screensaver's
        // own films): the film goes on from there next time, and the picture catches up then.
        player?.takeIf { live.moved }?.let { p -> p.currentMediaItem?.mediaId?.let { Wallpapers.leaveLiveFilm(it, p.currentPosition) } }
        live.moved = false
        player?.release()
        player = null
        live.player = null
        live.insert = null
        live.film = null
        live.stopped = false
        showing = false
        Wallpapers.dropFilmFrost()
    }

    fun open(): ExoPlayer {
        val quality = AerialFormats.forWallpaper(context)
        val overHttp = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        // Home reveals the saved picture before the decoder is ready. Start on that
        // frame, rather than crossfading to the later position saved at screen-off.
        // A different-format screensaver returns on black and keeps its own position.
        val spot = if (fresh) Wallpapers.liveSpot()
            else Wallpapers.livePictureSpot() ?: Wallpapers.liveSpot()
        fresh = false
        val films = AerialSet.playlist(context, spot?.first)
        val resume = films.first().takeIf { it.id == spot?.first }
        var failures = 0
        var rendered = false
        return AerialStream.player(context, quality).apply {
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true
                    live.moved = true
                    showing = isPlaying
                    failures = 0
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showing = isPlaying && rendered
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    live.film = Aerials.all(context).firstOrNull { it.id == mediaItem?.mediaId }
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) DiagLog.i("wallpaper film now ${mediaItem?.mediaId} (reason $reason)")
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("wallpaper film failed (${error.errorCodeName})")
                    // Behind an app or another screensaver the decoder is theirs: no retry.
                    if (!keepNow) return
                    // The same film again, from where it was: going on to the next one on any error
                    // left the screensaver on another film (TCL MT5879, DECODING_FAILED three times
                    // in a minute). The next one only after three; after six Apple's host is out
                    // of reach, and the picture stays as it is.
                    if (++failures >= 6) return
                    if (failures % 3 == 0) seekToNextMediaItem()
                    prepare()
                }
            })
            live.insert = { film ->
                addMediaItem(currentMediaItemIndex + 1, AerialStream.mediaItem(film, quality, overHttp))
                seekTo(currentMediaItemIndex + 1, 0L)
                if (playbackState == Player.STATE_IDLE) prepare()
            }
            setMediaItems(films.map { AerialStream.mediaItem(it, quality, overHttp) }, 0, if (resume != null) spot?.second ?: 0L else 0L)
            surface?.let { setVideoSurfaceView(it) }
            prepare()
            live.player = this
            live.film = films.first()
        }
    }

    /**
     * The film was let go at [left], past its picture (Wallpapers.leftLiveSpot): the key frame
     * there becomes the picture, so Home does not show the same one every time, and the picture is
     * again the frame the film starts from. Not once the film shows over it, unless [underFilm]:
     * the film playing from the picture's moment hides the picture changing beneath it.
     */
    suspend fun catchUp(left: Pair<String, Long>, underFilm: Boolean = false) {
        val frame = withTimeoutOrNull(CATCH_UP_MS) {
            reader.frame(left.first, left.second, Wallpapers.screenW, Wallpapers.screenH, exact = false, nearest = true)
        } ?: return
        if ((cover.value > 0f && !underFilm) || Wallpapers.leftLiveSpot() != left) return
        DiagLog.i("wallpaper picture catches up with where the film was let go")
        Wallpapers.useLiveFrame(frame, left.first, left.second)
    }

    /**
     * Stops [p] on its frame, and makes that frame the picture before the film is let go: copied
     * where PixelCopy reads the film (TCL MT5879, under 150 ms), else the key frame nearest to it
     * from the open file (Xiaomi MiTV-MFTR0, 1-1.5 s while an app opens). No frame in time: let go as before.
     */
    suspend fun leaveOnPicture(p: ExoPlayer) {
        p.pause()
        val film = p.currentMediaItem?.mediaId ?: return
        val at = p.currentPosition
        val started = android.os.SystemClock.elapsedRealtime()
        val frame = withTimeoutOrNull(LEAVE_FRAME_MS) {
            (if (pixelCopy == false) null else surface?.let { capture(it, p.videoSize) })
                ?: reader.frame(film, at, Wallpapers.screenW, Wallpapers.screenH, exact = false, nearest = true)
        }
        if (frame == null) {
            // The picture as it was, of an earlier moment, rather than black; it catches up behind
            // the app (LaunchedEffect(keep)).
            DiagLog.i("wallpaper film: no frame in time to leave on")
            cover.snapTo(0f)
            return
        }
        // Shown, then the decoder goes; kept and frosted after (1.4 s on MiTV-MFTR0).
        Wallpapers.showLiveFrame(frame)
        cover.snapTo(0f)
        p.stop()
        DiagLog.i("wallpaper left on its frame in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        Wallpapers.useLiveFrame(frame, film, at)
    }

    // A small frame taken every few seconds while the film moves: the plate's glass over the
    // moving film (Wallpapers.filmFrost), and the glass for wherever it stops, at hand. Copied off
    // the video plane where PixelCopy reads it (TCL MT5879); else a key frame from the film's file,
    // opened ahead (see FrameReader; Xiaomi MiTV-MFTR0).
    var glassSample by remember { mutableStateOf<GlassSample?>(null) }
    LaunchedEffect(live.film, play, showing) {
        val id = live.film?.id ?: return@LaunchedEffect
        if (!play || !showing) return@LaunchedEffect
        if (pixelCopy == false && glassFrames) reader.open(id)
        // Under the screensaver no plate shows: a frame only so the glass is at hand where it stops.
        sampleGlass({ player }, every = { if (inSaver) SAVER_GLASS_EVERY_MS else GLASS_EVERY_MS }) { at ->
            val frame = surface?.let { copyGlass(it) }
                ?: if (pixelCopy == true) null else reader.frame(id, at, 320, 180, exact = false, quiet = true)
            frame?.also {
                glassSample = GlassSample(id, at, it)
                Wallpapers.keepClipGlass(id, it) { live.film?.id }
            }
        }
    }

    // With a screensaver of aerials in another format (Dolby Vision chosen), the index of the film
    // showing is fetched in that format: the screensaver goes on from this film and moment.
    LaunchedEffect(live.film, showing) {
        val film = live.film ?: return@LaunchedEffect
        if (!showing || Wallpapers.screensaver != ScreensaverMode.Aerial) return@LaunchedEffect
        val saver = AerialFormats.forScreensaver(context, overWallpaper = true)
        if (saver != AerialFormats.forWallpaper(context)) {
            AerialStream.prepareSeek(context, film, saver, Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
        }
    }

    val scope = rememberCoroutineScope()
    val stops = rememberStops {
        if (player == null) return@rememberStops false
        DiagLog.i("wallpaper film lets go as Aerial stops")
        release()
        scope.launch { cover.snapTo(0f) }
        true
    }

    LaunchedEffect(play, stops) {
        live.stopped = false
        if (play) {
            // Begin on the picture Home is showing. If it is behind the last stop, catch it
            // up under the moving film rather than changing the visible picture before playback.
            // Catching up first held Home still for about 3 s (Xiaomi MiTV-MFTR0).
            val left = if (player == null) Wallpapers.leftLiveSpot() else null
            val p = player ?: open().also { player = it }
            DiagLog.i("wallpaper film starts (state ${p.playbackState}, ${p.currentMediaItem?.mediaId} at ${p.currentPosition} ms)")
            // It moves on from the frame the picture is: that picture's glass until the first sample.
            Wallpapers.dropFilmFrost()
            // Its decoder was let go for the screensaver (LiveFilm.letDecoderGo).
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.playWhenReady = true
            snapshotFlow { showing }.first { it }
            DiagLog.i("wallpaper film moving")
            cover.animateTo(1f, tween(1200, easing = Motion.EaseOut))
            if (left != null) {
                delay(CATCH_UP_UNDER_FILM_MS)
                catchUp(left, underFilm = true)
            }
        } else {
            val p = player ?: return@LaunchedEffect
            // Into the screensaver or an app: the decoder is needed elsewhere.
            if (!keepNow) {
                // Into an app, Home shows on until it is drawn (Xiaomi's settings slide in over it;
                // Bilibili covered it 3.1 s after Home paused, MiTV-MFTR0). Let go at once, the film
                // left black there until the picture came back, an older one. Stopped on its frame,
                // that frame becomes the picture first, within LEAVE_FRAME_MS: the decoder is held,
                // paused, that much longer. Not into a screensaver of its own, which needs it at once.
                if (cover.value > 0f && !otherFormatNow && !AerialStart.running) leaveOnPicture(p)
                release()
                return@LaunchedEffect
            }
            p.pause()
            if (cover.value > 0f) {
                // The stopped frame becomes the picture; once Home has it, the film can go. Where
                // the video plane cannot be read, the same moment is decoded from the 1080p file:
                // first the key frame before it, small, which is all the glass needs, so the plate
                // is back with the apps; then the exact frame, which looks just like the paused film.
                val film = p.currentMediaItem?.mediaId
                val at = p.currentPosition
                val w = surface?.width ?: Wallpapers.screenW
                val h = surface?.height ?: Wallpapers.screenH
                p.videoDecoderCounters?.let { DiagLog.i("wallpaper film dropped ${it.droppedBufferCount} of ${it.renderedOutputBufferCount + it.droppedBufferCount} frames") }
                val before = Wallpapers.frosted
                // Any frame of the same shot will do: an aerial moves slowly, and frosted, seconds
                // apart look alike, while the glass of another shot showed in the grid until this
                // one was decoded, 0.5-3 s (Xiaomi MiTV-MFTR0, issue #727).
                glassSample?.takeIf { it.film == film && abs(it.atMs - at) <= SAME_SHOT_MS }?.let {
                    Wallpapers.useLiveFrost(it.frame)
                    glassReady(live)
                }
                glassSample = null
                var frame = if (pixelCopy == false) null else surface?.let { capture(it, p.videoSize) }
                if (frame == null && film != null) {
                    // Stopped by a panel opening: decoded once the panel is in. Read from Apple and
                    // decrypted in this process (the media server's reads come through its binder
                    // threads), it took a third of the app's CPU in the menu's first 1.5 s (Xiaomi
                    // MiTV-MFTR0). An open panel keeps the glass it opened with (liquidGlass), so
                    // nothing waits on it there; the grid's glass does, and the grid has no wait.
                    if (panelNow) delay(PANEL_IN_MS)
                    if (!live.stopped) reader.frame(film, at, 320, 180, exact = false)?.let {
                        Wallpapers.useLiveFrost(it)
                        glassReady(live)
                    }
                    // The exact frame is decoded from the key frame before it, reading that whole
                    // stretch of file while the paused player still fills its buffer; on MFTR0 that
                    // timed out at times. The nearest key frame alone is a second or so off at most.
                    frame = reader.frame(film, at, w, h, exact = true)
                        ?: reader.frame(film, at, w, h, exact = false, nearest = true)
                    if (frame == null && live.stopped) Wallpapers.restoreFrost(before)
                }
                if (frame != null) {
                    Wallpapers.useLiveFrame(frame, film.orEmpty(), at)
                    if (!live.stopped) glassReady(live)
                    // The paused film stands in for its picture while Home shows it plainly, and
                    // moves on from there when Home is still again. The picture, decoded apart
                    // and drawn on another plane with other processing, showed a slight jolt
                    // when swapped in. It takes over under a panel, whose blur the film would
                    // not get, or after KEEP_STOPPED_MS. Not where the copy of the plane came back
                    // green: the paused film shows green there too once Home redraws over it
                    // (Hisense T963, Xiaomi MiTV-MTEQ0 and MFTP0: green until the film moved
                    // again, issues #256, #412).
                    if (!copyLostColour) {
                        withTimeoutOrNull(KEEP_STOPPED_MS) { snapshotFlow { standInNow && keepNow }.first { !it } }
                    }
                    if (!keepNow) {
                        live.stopped = false
                        // The picture is this frame: it shows rather than black while Home is still
                        // seen, an app coming in over it (see leaveOnPicture).
                        if (!otherFormatNow && !AerialStart.running) cover.snapTo(0f)
                        release()
                        return@LaunchedEffect
                    }
                    DiagLog.i("wallpaper picture takes over from the stopped film")
                    cover.animateTo(0f, tween(320, easing = Motion.EaseOut))
                } else if (copySlow && film != null) {
                    // PixelCopy reads the film but holds Home 0.6-1.7 s, and the film's file jams
                    // the media server (TCL MT5879, FrameReader). The paused film stays while Home
                    // shows it plainly; once no key has come for KEEP_STOPPED_MS, the copy is made,
                    // unseen, and becomes the picture. Left first: the picture stays as it was.
                    val idle = untilQuietOrLeft({ standInNow && keepNow }, lastInputNow)
                    val copy = if (idle) surface?.let { captureOnce(it, videoSize = p.videoSize) } else null
                    if (copy != null) Wallpapers.useLiveFrame(copy, film, at)
                    if (!keepNow) {
                        live.stopped = false
                        if (!otherFormatNow && !AerialStart.running) cover.snapTo(0f)
                        release()
                        return@LaunchedEffect
                    }
                    DiagLog.i(if (copy != null) "wallpaper picture copied from the film left quiet" else "wallpaper film: no frame; back to the last picture")
                    cover.animateTo(0f, tween(if (copy != null) 320 else 600, easing = Motion.EaseOut))
                } else {
                    DiagLog.i("wallpaper film: no frame copied; back to the last picture")
                    cover.animateTo(0f, tween(600, easing = Motion.EaseOut))
                }
                live.stopped = false
            }
            delay(KEEP_STOPPED_MS)
            release()
        }
    }
    // A stopped player still holds its decoder, and some TVs have only one video path: on Xiaomi
    // MiTV-MFTR0 the screensaver's decoder lost its resources and its films failed while it was held.
    LaunchedEffect(keep) {
        if (!keep && !play && player != null && cover.value == 0f) release()
        if (!keep && otherFormatNow) {
            fresh = true
            blackForFilm = true
        }
        val black = keep && blackForFilm && play && cover.value > 0f
        if (keep) blackForFilm = false
        if (black) {
            // Black until the film's first frame; the picture only if it does not come.
            if (withTimeoutOrNull(FRESH_WAIT_MS) { snapshotFlow { showing }.first { it } } == null) cover.snapTo(0f)
        } else if (keep) {
            // Let go while it showed: the picture comes back before the film does.
            if (player == null) cover.snapTo(0f)
            // Back on Home off the shelf, with the film let go past its picture: the picture
            // catches up now. On the shelf the film comes in over the picture as it is (above).
            if (!play) Wallpapers.leftLiveSpot()?.let { catchUp(it) }
        } else {
            if (cover.value > 0f && !otherFormatNow) {
                // Let go into a screensaver of its own: the picture, of an earlier moment and maybe
                // another film, showed through as the screensaver faded in over it (Xiaomi MiTV-MFTR0,
                // Dolby Vision screensaver over the aerial wallpaper). It comes back once covered.
                delay(SAVER_COVERS_MS)
                if (player == null) cover.snapTo(0f)
            }
            // Behind an app: the picture catches up to where the film was let go, out of sight, so
            // Home comes back on the shot it left with the film going on from it. Once the app is
            // in; not under a screensaver of aerials, which moves the spot on every second.
            delay(BEHIND_CATCH_UP_MS)
            val left = Wallpapers.leftLiveSpot()
            if (!keepNow && !otherFormatNow && !AerialStart.running && player == null && left != null) {
                catchUp(left, underFilm = true)
                if (player == null) reader.close()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { release() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> FilmSlot(ctx).also { surface = it.surface; player?.setVideoSurfaceView(it.surface) } },
        onRelease = { player?.clearVideoSurfaceView(it.surface); surface = null },
    )
}

/**
 * The film's current frame, at the screen's size, or null: PixelCopy needs Android 7.0, and some
 * TVs put video on a plane apps cannot read, which comes back black, or green where the copy holds
 * brightness without colour (Hisense VIDAA_TV T963, Xiaomi MiTV-MTEQ0: the wallpaper turned green
 * at the first key press, issue #256).
 */
private suspend fun capture(view: SurfaceView, videoSize: VideoSize): Bitmap? {
    // The same TV gives the same answer: where it fails, later stops go straight to the film's
    // file; where it works, the file is not needed at all.
    return captureOnce(view, videoSize = videoSize).also { pixelCopy = it != null && !copySlow }
}

/** A moving wallpaper's current frame where this TV lets PixelCopy read its video plane; else null. */
internal suspend fun copyFrame(view: SurfaceView, videoSize: VideoSize): Bitmap? = if (pixelCopy == false) null else capture(view, videoSize)

/**
 * A small copy of the moving film for the plate's glass, where PixelCopy reads it; else null. Three
 * unreadable copies in a row settle that it does not, not one: a night film can read as black.
 */
internal suspend fun copyGlass(view: SurfaceView): Bitmap? {
    if (pixelCopy == false) return null
    val frame = captureOnce(view, 320, 180)
    if (frame != null) pixelCopy = !copySlow
    else if (++glassMisses >= 3 && pixelCopy == null) pixelCopy = false
    if (frame != null) glassMisses = 0
    return frame
}

private var glassMisses = 0

/** Whether [copyGlass] may still read the film here: not where PixelCopy has been found not to. */
internal val copyGlassPossible: Boolean get() = pixelCopy != false

/** Whether PixelCopy read the film green here: the video plane loses its colour when composited. */
private var copyLostColour = false

/**
 * The plate may come up over the stopped film: two frames on, once Home's plate has taken the
 * new glass while still out of sight, rather than fading it in over the last film's.
 */
/**
 * Waits until no key has come for [KEEP_STOPPED_MS] ([lastInputAt], uptimeMillis), true, or until
 * [showing] turns false first (an app or a frosting panel over Home), false.
 */
private suspend fun untilQuietOrLeft(showing: () -> Boolean, lastInputAt: () -> Long): Boolean = kotlinx.coroutines.coroutineScope {
    val left = async { snapshotFlow { showing() }.first { !it }; false }
    val quiet = async {
        while (true) {
            val since = android.os.SystemClock.uptimeMillis() - lastInputAt()
            if (since >= KEEP_STOPPED_MS) break
            delay(KEEP_STOPPED_MS - since)
        }
        true
    }
    kotlinx.coroutines.selects.select<Boolean> {
        left.onAwait { it }
        quiet.onAwait { it }
    }.also {
        left.cancel()
        quiet.cancel()
    }
}

private suspend fun glassReady(live: LiveFilm) {
    withFrameNanos { }
    withFrameNanos { }
    live.stopped = true
}

/** Where PixelCopy calls back, off the main thread as the copy itself is. */
private val copyCallbacks: Handler by lazy { Handler(android.os.HandlerThread("film-copy").apply { start() }.looper) }

/** Whether a copy of the film has taken longer than [COPY_SLOW_MS] here: PixelCopy is then not used again. */
private var copySlow = false

private const val COPY_SLOW_MS = 150L

/** The longest a panel takes to come in (Personalize's sheet, 380 ms), and a little more. */
private const val PANEL_IN_MS = 450L

/** Whether PixelCopy read the film in this process; null until it has been tried. */
private var pixelCopy: Boolean? = null

/**
 * How often a small frame is taken for the glass while the film moves on Home. Each is a key frame
 * decoded from the film's file on Xiaomi MiTV-MFTR0, 0.4-0.6 s of a core: every 2.5 s (until
 * issue #727) was more than the soft glass needs.
 */
internal const val GLASS_EVERY_MS = 4_000L

/** The same under the screensaver, where only the glass for wherever it stops is wanted. */
private const val SAVER_GLASS_EVERY_MS = 10_000L

/**
 * How far from where the film stopped a frame of it still makes its glass: past one taken under the
 * screensaver, while the next is being decoded, which can take seconds there (Xiaomi MiTV-MFTR0:
 * the last one 14.5 s back, the next not done; the glass then took 6 s more).
 */
private const val SAME_SHOT_MS = 30_000L

/** How long the film waits for its picture to catch up before it shows anyway (MFTR0: 1.4-3.5 s). */
private const val CATCH_UP_MS = 5_000L

/** How long the film has moved before its picture catches up beneath it: its own start is done. */
private const val CATCH_UP_UNDER_FILM_MS = 5_000L

/** How long Home stays black for the film after a screensaver in another format before showing the picture. */
private const val FRESH_WAIT_MS = 4_000L

/** The screensaver's fade in (Screensaver), and a little: Home is covered by then. */
private const val SAVER_COVERS_MS = 1_400L

/**
 * The longest the film holds its decoder past Home's pause, stopped on its frame, for that frame
 * to become the picture: the key frame took 0.96-1.44 s with Xiaomi's settings opening (MiTV-MFTR0).
 */
private const val LEAVE_FRAME_MS = 2_500L

/** How long behind an app before the picture catches up: its launch has the CPU first. */
private const val BEHIND_CATCH_UP_MS = 3_000L

/**
 * Whether frames are taken for the glass while a film moves. Not on a TV set to Lite effects, and
 * not once taking one has cost the film frames of its own: on TCL MT5879 each copy of a 4K film
 * (PixelCopy), and each key frame decoded from its file beside it, held the film up 0.5-0.6 s
 * every time, 16 frames dropped each (frames of 4K reaching the screen: 24 a second, 30 with none
 * taken; 1080p films were not held up). Remembered for the process, as [pixelCopy] is.
 */
internal val glassFrames: Boolean get() = !glassCostly && !AerialSettings.lite

private var glassCostly = false

/**
 * While the film moves, a frame from [take] every [GLASS_EVERY_MS] becomes the plate's glass
 * (Wallpapers.filmFrost). Where none can be taken, or taking them would hold the film up
 * ([glassFrames]), the plate keeps a still glass of the frame the film started from, blurred down
 * to its broad colours so that what has moved on does not show twice through it.
 */
internal suspend fun sampleGlass(
    player: () -> ExoPlayer?,
    canTake: () -> Boolean = { true },
    every: () -> Long = { GLASS_EVERY_MS },
    take: suspend (atMs: Long) -> Bitmap?,
) {
    // The first soon, for a film stopped moments after it moved.
    var wait = 800L
    var costly = 0
    while (glassFrames && canTake()) {
        // In steps, so a shorter [every] (out of the screensaver) is kept to at once.
        var waited = 0L
        while (waited < minOf(wait, every())) {
            delay(250)
            waited += 250
        }
        wait = Long.MAX_VALUE
        val p = player() ?: return
        val before = droppedFrames(p)
        val frame = take(p.currentPosition)
        // The frames the film dropped while one was taken, and just after: the copy or the decode held it up.
        delay(GLASS_SETTLE_MS)
        // Twice in a row, as the film can drop a few once for something else (Xiaomi MiTV-MFTR0:
        // 26 once, as Home came back from its screensaver; none in minutes either side).
        val cost = droppedFrames(p) - before
        costly = if (cost >= GLASS_COST_FRAMES) costly + 1 else 0
        if (costly >= 2) {
            DiagLog.i("wallpaper glass: the film dropped $cost frames while one was taken, twice running; a still glass from now on")
            glassCostly = true
            break
        }
        frame?.let { Wallpapers.useFilmFrost(it) }
    }
    if (wait == 800L) delay(wait)
    Wallpapers.useStillFilmFrost()
}

private fun droppedFrames(p: ExoPlayer): Int = p.videoDecoderCounters?.let { it.ensureUpdated(); it.droppedBufferCount } ?: 0

private const val GLASS_SETTLE_MS = 600L

/** Frames dropped around one frame taken that make it too costly; with none taken, 0 here (TCL MT5879, Xiaomi MiTV-MFTR0). */
private const val GLASS_COST_FRAMES = 5

/** A small key frame of [film] near [atMs], kept for the glass. */
private class GlassSample(val film: String, val atMs: Long, val frame: Bitmap)

private suspend fun captureOnce(view: SurfaceView, w: Int = view.width, h: Int = view.height, videoSize: VideoSize? = null): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || view.width <= 0 || !view.holder.surface.isValid) return null
    val surface = view.holder.surface
    // PixelCopy scales the entire decoder buffer, not the SurfaceView's displayed crop.
    // Preserve the video's aspect while copying, then crop exactly as the player does.
    val copyWidth = if (videoSize != null && videoSize.width > 0 && videoSize.height > 0)
        (h * videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height).roundToInt().coerceAtLeast(1)
        else w
    val bitmap = createBitmap(copyWidth, h)
    // PixelCopy copies on the thread that asks, before it calls back. Asked on the main thread, a
    // 4K film held it 0.5-1 s (TCL MT5879), and the keys pressed meanwhile all landed at once:
    // a Down that stopped the film moved focus down several rows.
    val started = android.os.SystemClock.elapsedRealtime()
    val result = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { done ->
            runCatching {
                PixelCopy.request(surface, bitmap, { done.resume(it) }, copyCallbacks)
            }.onFailure { done.resume(-1) }
        }
    }
    // The copy is made on the render thread, which the main thread waits on to draw: asked from
    // another thread, a 4K copy still held keys up 0.6 s (TCL MT5879). A copy this slow is the
    // last, kept for the TV's next starts too (FilmFrames): the film's file is not read there
    // either (FrameReader), and the glass goes without the film's frames (Wallpapers.filmGlassless).
    val took = android.os.SystemClock.elapsedRealtime() - started
    if (result == PixelCopy.SUCCESS && took > COPY_SLOW_MS) {
        DiagLog.i("wallpaper frame copy took $took ms; no frames of the film from now on but a quiet one")
        FilmFrames.copyWasSlow()
    }
    val unread = if (result == PixelCopy.SUCCESS) unreadable(bitmap) else null
    if (unread == "green") copyLostColour = true
    if (result != PixelCopy.SUCCESS || unread != null) DiagLog.i("wallpaper frame copy: result $result, $unread")
    if (result != PixelCopy.SUCCESS || unread != null) { bitmap.recycle(); return null }
    return Images.cover(bitmap, w, h).also {
        if (it !== bitmap) bitmap.recycle()
    }
}

/**
 * Frames of the film on screen, from its 1080p H.264 file, for TVs whose video plane PixelCopy
 * cannot read (Xiaomi MiTV-MFTR0, MStar: the copy comes back black) or reads too slowly (TCL MT5879).
 * One MediaMetadataRetriever is opened on the film while it plays: opening is most of the cost (a
 * connection to Apple, then the index at the end of the file), 1.4 to 6.6 s on MFTR0 when it was
 * done only once the film stopped. Open, a frame is the stretch of file it needs and a decode. One
 * thread, as the retriever is not safe to share; each call fetches only the index and the stretch it needs.
 *
 * A call that does not return in [DECODE_TIMEOUT_MS] is left behind with its thread and retriever,
 * and the next call gets a new pair: on TCL MT5879 a frame never came back, every call after it
 * waited behind it, and the picture and the glass stayed on a frame minutes old while the paused
 * film showed another (2026-10-30). A timeout around the call alone could not end that wait: the
 * call blocks its thread and cannot be cancelled.
 *
 * One left behind and the file is not read again until the TV restarts ([FileFramesStuck]). The
 * retriever runs in the media server, whose retrievers all wait on one lock: on TCL MT5879 a frame
 * stuck inside TCL's own MP4 extractor (TclMPEG4Extractor, waiting on a feed that never came) held
 * it, every later frame queued behind it, and once a new reader was tried after each one (the first
 * version of this, 2026-10-30), 17 of the server's binder threads were stuck. Then MediaCodecList,
 * which asks the media server, held Home's main thread at its start: black Home, ANRs, until the TV
 * restarted. Stock 1.69 had stuck there once as well (one call, Apple's host slow). Opening the
 * file still worked; every frame stuck, playing or paused.
 */
private class FrameReader(private val context: android.content.Context) {
    /** A thread and the retriever opened on it. Its thread goes after a while idle. */
    private class Worker {
        val executor = java.util.concurrent.ThreadPoolExecutor(
            0, 1, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
        ) { Thread(it, "film-frames").apply { isDaemon = true } }
        var retriever: MediaMetadataRetriever? = null
        var openFor: String? = null
        @Volatile var abandoned = false

        fun close() {
            retriever?.let { runCatching { it.release() } }
            retriever = null
            openFor = null
        }
    }

    @Volatile private var worker = Worker()

    /** Whether the retriever has refused an address here, so files go [RangedHttpSource]'s way. */
    @Volatile private var urlRefused = false

    /**
     * [block] on the worker's thread, or null after [DECODE_TIMEOUT_MS], when the worker is left
     * behind. The caller cancelled returns at once, and the block finishes unseen.
     */
    private suspend fun <T : Any> onWorker(what: String, block: (Worker) -> T?): T? {
        // Where PixelCopy reads the film, if slowly, the file is not used: on TCL MT5879 its frames
        // jammed the media server (above). Its frame waits for quiet instead (LiveWallpaper).
        if (copySlow || FileFramesStuck.now(context)) return null
        val w = worker
        val result = kotlinx.coroutines.CompletableDeferred<T?>()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            w.executor.execute { result.complete(if (w.abandoned) null else block(w)) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return null
        }
        val value = withTimeoutOrNull(DECODE_TIMEOUT_MS) { result.await() }
        if (value == null && !result.isCompleted) abandon(w, what, started)
        return value
    }

    private fun abandon(w: Worker, what: String, started: Long) {
        synchronized(this) {
            if (worker !== w) return
            worker = Worker()
        }
        w.abandoned = true
        FileFramesStuck.mark(context)
        FilmFrames.update()
        DiagLog.i("wallpaper film file stuck ($what, ${android.os.SystemClock.elapsedRealtime() - started} ms); not read again until the TV restarts")
        // Its retriever goes once the call stuck on it returns, if it ever does; what queued behind
        // it returns null at once.
        runCatching {
            w.executor.execute { w.close() }
            w.executor.shutdown()
        }
    }

    /** Opens [id] ahead, unless it is open already. */
    suspend fun open(id: String) {
        onWorker("open") { retrieverFor(it, id) }
    }

    /**
     * Frame [atMs] of film [id] at [w]×[h]: [exact]ly that frame, or a key frame, which needs no
     * decoding beyond itself: the [nearest] one, or else the one at or before it. Null after [DECODE_TIMEOUT_MS] or on failure.
     */
    suspend fun frame(id: String, atMs: Long, w: Int, h: Int, exact: Boolean, quiet: Boolean = false, nearest: Boolean = false): Bitmap? {
        // Not read here at all (onWorker): no line for each frame not had.
        if (copySlow || FileFramesStuck.now(context)) return null
        val started = android.os.SystemClock.elapsedRealtime()
        val frame = onWorker("frame") { wk ->
            val mmr = retrieverFor(wk, id) ?: return@onWorker null
            try {
                val us = atMs * 1000
                val option = when {
                    exact -> MediaMetadataRetriever.OPTION_CLOSEST
                    nearest -> MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    else -> MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
                }
                if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(us, option, w, h)
                else mmr.getFrameAtTime(us, option)
            } catch (e: Exception) {
                DiagLog.i("wallpaper frame decode failed: ${e.javaClass.simpleName}")
                wk.close()
                null
            }
        }
        if (!quiet || frame == null) DiagLog.i("wallpaper ${if (exact) "frame" else if (nearest) "key frame" else "glass frame"} decoded: ${frame != null} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        val sized = frame?.let {
            // Match the player's center crop, including screens that are not 16:9.
            val sized = Images.cover(it, w, h)
            if (sized.isMutable && sized.config == Bitmap.Config.ARGB_8888) sized else sized.copy(Bitmap.Config.ARGB_8888, true)
        }
        // The decoder can lose the colour too: on Xiaomi MiTV-MFTP0 (Android 9) the wallpaper and
        // the glass still turned green in 1.36, which reads the film's file wherever the copy of
        // the screen came back green (issue #284). Only green: a dark film's frame may well be.
        if (sized != null && unreadable(sized) == "green") {
            DiagLog.i("wallpaper decoded frame is green; not used")
            return null
        }
        return sized
    }

    /** Lets the file go; the next frame opens it again. */
    fun close() {
        val w = worker
        runCatching { w.executor.execute { w.close() } }
    }

    private fun retrieverFor(w: Worker, id: String): MediaMetadataRetriever? {
        if (w.openFor == id) w.retriever?.let { return it }
        w.close()
        val film = Aerials.all(context).firstOrNull { it.id == id } ?: return null
        val url = AerialStream.url(film, AerialQuality.Hd, Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
        val started = android.os.SystemClock.elapsedRealtime()
        val mmr = open(url, ranged = urlRefused) ?: run {
            // TCL x32a0 (Android 9): the retriever fails at once on Apple's https address that
            // ExoPlayer plays from (issue #567); fetch the file in the app and hand it over.
            if (urlRefused || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            urlRefused = true
            open(url, ranged = true) ?: return null
        }
        DiagLog.i("wallpaper film file opened${if (urlRefused) " through the app" else ""} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        // Left behind while it opened: this one goes too, as nothing will use it.
        if (w.abandoned) {
            runCatching { mmr.release() }
            return null
        }
        w.retriever = mmr
        w.openFor = id
        return mmr
    }

    private fun open(url: String, ranged: Boolean): MediaMetadataRetriever? {
        val mmr = MediaMetadataRetriever()
        return try {
            if (ranged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) mmr.setDataSource(RangedHttpSource(url))
            else mmr.setDataSource(url, emptyMap())
            mmr
        } catch (e: Exception) {
            DiagLog.i("wallpaper film file not opened${if (ranged) " through the app" else ""}: ${e.javaClass.simpleName}")
            runCatching { mmr.release() }
            null
        }
    }
}

private const val DECODE_TIMEOUT_MS = 10_000L

/**
 * What this TV allows for the film's frames, kept across starts: a PixelCopy found slow stays slow
 * on the same firmware, so the next start neither tries it (another 0.6-1.7 s held, TCL MT5879) nor
 * shows the frosted glass of an old frame meanwhile. Sets [Wallpapers.filmGlassless].
 */
private object FilmFrames {
    @Volatile private var app: android.content.Context? = null

    fun load(context: android.content.Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        if (prefs(c).getString(SLOW_KEY, null) == Build.FINGERPRINT) {
            copySlow = true
            pixelCopy = false
        }
        update()
    }

    fun copyWasSlow() {
        copySlow = true
        app?.let { prefs(it).edit().putString(SLOW_KEY, Build.FINGERPRINT).apply() }
        update()
    }

    fun update() {
        val c = app ?: return
        Wallpapers.filmGlassless = copySlow || FileFramesStuck.now(c)
    }

    private fun prefs(context: android.content.Context) = context.getSharedPreferences("wallpaper_frames", android.content.Context.MODE_PRIVATE)
    private const val SLOW_KEY = "copy_slow"
}

/**
 * Whether a frame of the film's file has stuck since the TV started (see FrameReader): the media
 * server stays stuck until then, and every frame asked of it adds a thread to the stuck ones. Kept
 * on disk, so Home started again in the same boot does not ask.
 */
private object FileFramesStuck {
    @Volatile private var stuck: Boolean? = null

    fun now(context: android.content.Context): Boolean =
        stuck ?: (prefs(context).getLong(KEY, Long.MIN_VALUE) == bootId(context)).also { stuck = it }

    fun mark(context: android.content.Context) {
        stuck = true
        prefs(context).edit().putLong(KEY, bootId(context)).apply()
    }

    /** The boot count where the TV keeps one (Android 7.0+), else the minute the TV started. */
    private fun bootId(context: android.content.Context): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val count = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
            if (count >= 0) return count.toLong()
        }
        return -(System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000L
    }

    private fun prefs(context: android.content.Context) = context.getSharedPreferences("wallpaper_frames", android.content.Context.MODE_PRIVATE)
    private const val KEY = "stuck_boot"
}

/**
 * A film file read over HTTP by the app, for a retriever that will not open the address itself:
 * fixed blocks fetched with Range requests, the last few kept, as the retriever goes back and forth
 * between the index and the stretch it decodes.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
private class RangedHttpSource(private val url: String) : android.media.MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }
    private val length: Long = fetch(0).let { (first, total) -> blocks[0] = first; total }

    override fun getSize(): Long = length

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        var done = 0
        while (done < size && position + done < length) {
            val at = position + done
            val index = at / BLOCK
            val block = blocks[index] ?: fetch(index).first.also { blocks[index] = it }
            val from = (at - index * BLOCK).toInt()
            if (from >= block.size) break
            val n = minOf(size - done, block.size - from)
            System.arraycopy(block, from, buffer, offset + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    @Synchronized
    override fun close() = blocks.clear()

    /** Block [index], and the file's whole length from the Content-Range header. */
    private fun fetch(index: Long): Pair<ByteArray, Long> {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("Range", "bytes=${index * BLOCK}-${(index + 1) * BLOCK - 1}")
            if (c.responseCode != 206) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: throw java.io.IOException("no length")
            return c.inputStream.use { it.readBytes() } to total
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val BLOCK = 512 * 1024L
        const val MAX_BLOCKS = 12
    }
}

/**
 * Why a copied frame is not the film, or null if it looks like one: black; one flat colour; or
 * green, which is what video with its colour planes left at zero reads as: red and blue clip to 0,
 * except that red comes back in the brightest parts (Y above 180), which a test for red under 12
 * missed on bright films. Leaves and grass stay well short of 60 between green and the others.
 */
private fun unreadable(b: Bitmap): String? {
    var lit = 0
    var green = 0
    var lo = 255
    var hi = 0
    for (y in 1..8) for (x in 1..8) {
        val c = b.getPixel(b.width * x / 9, b.height * y / 9)
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val bl = c and 0xFF
        if (r + g + bl > 24) lit++
        if (r < 48 && bl < 24 && g > r + 60 && g > bl + 60) green++
        val luma = (r * 3 + g * 6 + bl) / 10
        lo = minOf(lo, luma)
        hi = maxOf(hi, luma)
    }
    return when {
        lit < 3 -> "black"
        green > 32 -> "green"
        hi - lo < 6 -> "flat"
        else -> null
    }
}
