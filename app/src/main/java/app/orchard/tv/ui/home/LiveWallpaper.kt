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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.graphics.createBitmap
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.ui.components.FilmSlot
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Aerial
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.Aerials
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** How long Home sits still before the aerial wallpaper starts moving. */
internal const val START_AFTER_MS = 10_000L

/** A stopped film keeps its player this long, so it moves on again without loading. */
internal const val KEEP_STOPPED_MS = 2 * 60 * 1000L

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
    val positionMs: Long get() = player?.currentPosition ?: 0L

    fun skip(step: Int) {
        val p = player ?: return
        if (step < 0) p.seekToPreviousMediaItem() else p.seekToNextMediaItem()
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
 * The aerial wallpaper (issue #216): once Home has sat still on the top row for [START_AFTER_MS],
 * Apple's film plays behind the tiles, and any key stops it on the frame it was showing. That frame
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
    lastInputAt: Long,
    cover: Animatable<Float, *>,
    /** The screensaver carries the film on: it keeps moving whatever the keys do. */
    hold: Boolean,
    /** Nothing over Home that blurs it: a stopped film may stand in for its picture. */
    standIn: Boolean,
    live: LiveFilm,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    // Frames are reaching the screen: the first one has been drawn and the film is not stopped.
    var showing by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    val reader = remember { FrameReader(context) }
    LaunchedEffect(lastInputAt, moving) {
        idle = false
        if (!moving) return@LaunchedEffect
        delay(START_AFTER_MS)
        idle = true
    }
    // Set to keep moving, it plays as soon as Home shows it and keys do not stop it (issue #360).
    val play = moving && (idle || AerialSettings.wallpaperAlwaysMoves) || hold
    val keepNow by rememberUpdatedState(keep)
    val standInNow by rememberUpdatedState(standIn)

    fun release() {
        reader.close()
        player?.release()
        player = null
        live.player = null
        live.film = null
        live.stopped = false
        showing = false
        Wallpapers.dropFilmFrost()
    }

    fun open(): ExoPlayer {
        val quality = AerialFormats.forWallpaper(context)
        val overHttp = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        val all = Aerials.all(context).shuffled()
        // The film the picture on screen came from, at that moment; else one fetched ahead.
        val spot = Wallpapers.liveSpot()
        val resume = spot?.let { (id, _) -> all.firstOrNull { it.id == id } }
        val films = if (resume != null) listOf(resume) + (all - resume) else AerialStream.withOpenerFirst(context, all, quality)
        var failures = 0
        var rendered = false
        return AerialStream.player(context, quality).apply {
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true
                    showing = isPlaying
                    failures = 0
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showing = isPlaying && rendered
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    live.film = films.firstOrNull { it.id == mediaItem?.mediaId }
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("wallpaper film failed (${error.errorCodeName})")
                    // Three in a row: Apple's host is out of reach; the picture stays as it is.
                    if (++failures >= 3) return
                    seekToNextMediaItem()
                    prepare()
                }
            })
            setMediaItems(films.map { AerialStream.mediaItem(it, quality, overHttp) }, 0, if (resume != null) spot?.second ?: 0L else 0L)
            surface?.let { setVideoSurfaceView(it) }
            prepare()
            live.player = this
            live.film = films.first()
        }
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
        sampleGlass({ player }) { at ->
            val frame = surface?.let { copyGlass(it) }
                ?: if (pixelCopy == true) null else reader.frame(id, at, 320, 180, exact = false, quiet = true)
            frame?.also { glassSample = GlassSample(id, at, it) }
        }
    }

    LaunchedEffect(play) {
        live.stopped = false
        if (play) {
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
        } else {
            val p = player ?: return@LaunchedEffect
            // Into the screensaver or an app: no frame to take, and the decoder is needed elsewhere.
            if (!keepNow) {
                cover.snapTo(0f)
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
                glassSample?.takeIf { it.film == film && abs(it.atMs - at) <= GLASS_EVERY_MS * 2 }?.let {
                    Wallpapers.useLiveFrost(it.frame)
                    glassReady(live)
                }
                glassSample = null
                var frame = if (pixelCopy == false) null else surface?.let { capture(it) }
                if (frame == null && film != null) {
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
                        cover.snapTo(0f)
                        live.stopped = false
                        release()
                        return@LaunchedEffect
                    }
                    DiagLog.i("wallpaper picture takes over from the stopped film")
                    cover.animateTo(0f, tween(320, easing = Motion.EaseOut))
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
private suspend fun capture(view: SurfaceView): Bitmap? {
    // The same TV gives the same answer: where it fails, later stops go straight to the film's
    // file; where it works, the file is not needed at all.
    return captureOnce(view).also { pixelCopy = it != null && !copySlow }
}

/** A moving wallpaper's current frame where this TV lets PixelCopy read its video plane; else null. */
internal suspend fun copyFrame(view: SurfaceView): Bitmap? = if (pixelCopy == false) null else capture(view)

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

/** Whether PixelCopy read the film in this process; null until it has been tried. */
private var pixelCopy: Boolean? = null

/** How often a small frame is taken for the glass while the film moves. */
internal const val GLASS_EVERY_MS = 2_500L

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
internal suspend fun sampleGlass(player: () -> ExoPlayer?, canTake: () -> Boolean = { true }, take: suspend (atMs: Long) -> Bitmap?) {
    // The first soon, for a film stopped moments after it moved.
    var wait = 800L
    var costly = 0
    while (glassFrames && canTake()) {
        delay(wait)
        wait = GLASS_EVERY_MS
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

private suspend fun captureOnce(view: SurfaceView, w: Int = view.width, h: Int = view.height): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || view.width <= 0 || !view.holder.surface.isValid) return null
    val surface = view.holder.surface
    val bitmap = createBitmap(w, h)
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
    // last: the film's file serves from then on, decoded off the render thread, as where the
    // copy comes back black. The film is paused when that matters, so decoding beside it is free.
    val took = android.os.SystemClock.elapsedRealtime() - started
    if (result == PixelCopy.SUCCESS && took > COPY_SLOW_MS) {
        DiagLog.i("wallpaper frame copy took $took ms; the film's file from now on")
        copySlow = true
    }
    val unread = if (result == PixelCopy.SUCCESS) unreadable(bitmap) else null
    if (unread == "green") copyLostColour = true
    if (result != PixelCopy.SUCCESS || unread != null) DiagLog.i("wallpaper frame copy: result $result, $unread")
    return bitmap.takeIf { result == PixelCopy.SUCCESS && unread == null }
}

/**
 * Frames of the film on screen, from its 1080p H.264 file, for TVs whose video plane PixelCopy
 * cannot read (Xiaomi MiTV-MFTR0, MStar: the copy comes back black). One MediaMetadataRetriever is
 * opened on the film while it plays: opening is most of the cost (a connection to Apple, then the
 * index at the end of the file), 1.4 to 6.6 s on MFTR0 when it was done only once the film stopped.
 * Open, a frame is the stretch of file it needs and a decode. One thread, as the retriever is not
 * safe to share; each call fetches only the index and the stretch it needs.
 */
private class FrameReader(private val context: android.content.Context) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val thread = Dispatchers.IO.limitedParallelism(1)
    private var retriever: MediaMetadataRetriever? = null
    private var openFor: String? = null

    /** Opens [id] ahead, unless it is open already. */
    suspend fun open(id: String) {
        withContext(thread) { retrieverFor(id) }
    }

    /**
     * Frame [atMs] of film [id] at [w]×[h]: [exact]ly that frame, or a key frame, which needs no
     * decoding beyond itself: the [nearest] one, or else the one at or before it. Null after [DECODE_TIMEOUT_MS] or on failure.
     */
    suspend fun frame(id: String, atMs: Long, w: Int, h: Int, exact: Boolean, quiet: Boolean = false, nearest: Boolean = false): Bitmap? {
        val started = android.os.SystemClock.elapsedRealtime()
        val frame = withTimeoutOrNull(DECODE_TIMEOUT_MS) {
            withContext(thread) {
                val mmr = retrieverFor(id) ?: return@withContext null
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
                    close0()
                    null
                }
            }
        }
        if (!quiet || frame == null) DiagLog.i("wallpaper ${if (exact) "frame" else if (nearest) "key frame" else "glass frame"} decoded: ${frame != null} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        val sized = frame?.let {
            val sized = if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true)
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
        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
        kotlinx.coroutines.GlobalScope.launch(thread) { close0() }
    }

    private fun retrieverFor(id: String): MediaMetadataRetriever? {
        if (openFor == id) retriever?.let { return it }
        close0()
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
        retriever = mmr
        openFor = id
        return mmr
    }

    /** Whether the retriever has refused an address here, so files go [RangedHttpSource]'s way. */
    private var urlRefused = false

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

    private fun close0() {
        retriever?.let { runCatching { it.release() } }
        retriever = null
        openFor = null
    }
}

private const val DECODE_TIMEOUT_MS = 10_000L

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
