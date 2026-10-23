package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.Aerial
import app.orchard.tv.wallpaper.AerialFormats
import app.orchard.tv.wallpaper.AerialQuality
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.Aerials
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.wallpaper.Snoopy
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
private const val START_AFTER_MS = 10_000L

/** A stopped film keeps its player this long, so it moves on again without loading. */
private const val KEEP_STOPPED_MS = 2 * 60 * 1000L

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
    val wallpaper = remember { Wallpapers.selectedId }
    val snoopy = wallpaper == Wallpapers.SNOOPY
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
    val play = moving && idle || hold
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
    }

    fun open(): ExoPlayer {
        val quality = AerialFormats.resolve(context, AerialSettings.aerialQuality)
            .let { if (it == AerialQuality.DolbyVision) AerialQuality.Uhd else it }
        val overHttp = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        val all = (if (snoopy) Snoopy.films() else Aerials.all(context)).shuffled()
        // The film the picture on screen came from, at that moment; else one fetched ahead.
        val spot = Wallpapers.liveSpot()
        val resume = spot?.let { (id, _) -> all.firstOrNull { it.id == id } }
        val films = if (resume != null) listOf(resume) + (all - resume) else if (snoopy) all else AerialStream.withOpenerFirst(context, all, quality)
        var failures = 0
        var rendered = false
        return (if (snoopy) localVideoPlayer(context) else AerialStream.player(context, quality)).apply {
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
            setMediaItems(films.map {
                if (snoopy) MediaItem.Builder().setUri(it.url).setMediaId(it.id).build() else AerialStream.mediaItem(it, quality, overHttp)
            }, 0, if (resume != null) spot?.second ?: 0L else 0L)
            surface?.let { setVideoSurfaceView(it) }
            prepare()
            live.player = this
            live.film = films.first()
        }
    }

    // The film on screen, opened ahead for its frames (see FrameReader), and a small key frame
    // taken every few seconds while it moves: the glass for wherever it stops is then at hand.
    var glassSample by remember { mutableStateOf<GlassSample?>(null) }
    LaunchedEffect(live.film, play, showing) {
        val id = live.film?.id ?: return@LaunchedEffect
        // Where PixelCopy reads the film (TCL MT5879), its frame is at hand without the file.
        if (!play || pixelCopy == true) return@LaunchedEffect
        reader.open(id)
        // The first soon, for a film stopped moments after it moved.
        var wait = 800L
        while (showing) {
            delay(wait)
            wait = GLASS_EVERY_MS
            val at = player?.currentPosition ?: break
            reader.frame(id, at, 320, 180, exact = false, quiet = true)?.let { glassSample = GlassSample(id, at, it) }
        }
    }

    LaunchedEffect(play) {
        live.stopped = false
        if (play) {
            val p = player ?: open().also { player = it }
            DiagLog.i("wallpaper film starts (state ${p.playbackState}, ${p.currentMediaItem?.mediaId} at ${p.currentPosition} ms)")
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
                    Wallpapers.useLiveFrost(it.frame, wallpaper)
                    glassReady(live)
                }
                glassSample = null
                var frame = if (pixelCopy == false) null else surface?.let { capture(it) }
                if (frame == null && film != null) {
                    if (!live.stopped) reader.frame(film, at, 320, 180, exact = false)?.let {
                        Wallpapers.useLiveFrost(it, wallpaper)
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
                    Wallpapers.useLiveFrame(frame, film.orEmpty(), at, wallpaper)
                    if (!live.stopped) glassReady(live)
                    // The paused film stands in for its picture while Home shows it plainly, and
                    // moves on from there when Home is still again. The picture, decoded apart
                    // and drawn on another plane with other processing, showed a slight jolt
                    // when swapped in. It takes over under a panel, whose blur the film would
                    // not get, or after KEEP_STOPPED_MS.
                    withTimeoutOrNull(KEEP_STOPPED_MS) { snapshotFlow { standInNow && keepNow }.first { !it } }
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
        factory = { ctx -> SurfaceView(ctx).also { surface = it; player?.setVideoSurfaceView(it) } },
        onRelease = { player?.clearVideoSurfaceView(it); surface = null },
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
    return captureOnce(view).also { pixelCopy = it != null }
}

/**
 * The plate may come up over the stopped film: two frames on, once Home's plate has taken the
 * new glass while still out of sight, rather than fading it in over the last film's.
 */
private suspend fun glassReady(live: LiveFilm) {
    withFrameNanos { }
    withFrameNanos { }
    live.stopped = true
}

/** Whether PixelCopy read the film in this process; null until it has been tried. */
private var pixelCopy: Boolean? = null

/** How often a small key frame is taken for the glass while the film moves. */
private const val GLASS_EVERY_MS = 2_500L

/** A small key frame of [film] near [atMs], kept for the glass. */
private class GlassSample(val film: String, val atMs: Long, val frame: Bitmap)

private suspend fun captureOnce(view: SurfaceView): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || view.width <= 0 || !view.holder.surface.isValid) return null
    val bitmap = createBitmap(view.width, view.height)
    val result = suspendCancellableCoroutine { done ->
        runCatching {
            PixelCopy.request(view, bitmap, { done.resume(it) }, Handler(Looper.getMainLooper()))
        }.onFailure { done.resume(-1) }
    }
    val unread = if (result == PixelCopy.SUCCESS) unreadable(bitmap) else null
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
        val local = Snoopy.file(id)
        val film = if (local == null) Aerials.all(context).firstOrNull { it.id == id } ?: return null else null
        val started = android.os.SystemClock.elapsedRealtime()
        val mmr = MediaMetadataRetriever()
        return try {
            if (local != null) mmr.setDataSource(local.path)
            else mmr.setDataSource(AerialStream.url(film!!, AerialQuality.Hd, Build.VERSION.SDK_INT < Build.VERSION_CODES.N), emptyMap())
            DiagLog.i("wallpaper film file opened in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            retriever = mmr
            openFor = id
            mmr
        } catch (e: Exception) {
            DiagLog.i("wallpaper film file not opened: ${e.javaClass.simpleName}")
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
