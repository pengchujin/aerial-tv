package app.orchard.tv.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.ui.components.FilmSlot
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.DiagLog
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.ScreensaverVideos
import app.orchard.tv.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The owner's own videos behind Home (issues #410, #583), sent from the phone page as for the video
 * screensaver: silent, filling the screen, one after another or just the one chosen on a loop (#762). They move when the aerial wallpaper
 * would (LiveWallpaper): on a still shelf, or all the time when set to keep moving. Stopped while
 * Home shows plainly (Control Center, Personalize), the paused film stays; under a frosted panel,
 * after a quiet while, or let go into an app, the frame it stopped on becomes the wallpaper's
 * picture: read off the video plane where the TV allows it, else its key frame decoded in the app,
 * else, once the player has let go, from the file by the media server, else the film's poster.
 */
@Composable
fun VideoWallpaper(
    moving: Boolean,
    /** False once the screensaver is up or Home is behind an app: the decoder goes at once. */
    keep: Boolean,
    /** Nothing over Home that blurs it: the stopped film may stand in for its picture (as LiveWallpaper). */
    standIn: Boolean,
    lastInputAt: Long,
    cover: Animatable<Float, *>,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var showing by remember { mutableStateOf(false) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(lastInputAt, moving) {
        idle = false
        if (!moving) return@LaunchedEffect
        delay(START_AFTER_MS)
        idle = true
    }
    val play = moving && (idle || AerialSettings.wallpaperAlwaysMoves)
    val keepNow by rememberUpdatedState(keep)
    val standInNow by rememberUpdatedState(standIn)
    val lastInputNow by rememberUpdatedState(lastInputAt)
    val scope = rememberCoroutineScope()

    fun release() {
        player?.release()
        player = null
        showing = false
        Wallpapers.dropFilmFrost()
    }

    suspend fun open(): ExoPlayer? {
        val films = withContext(Dispatchers.IO) { Wallpapers.videoFilms() }
        if (films.isEmpty()) return null
        // Where the picture on screen came from, so moving again does not cut to another place.
        val spot = Wallpapers.videoSpot()
        val first = films.indexOfFirst { it.id == spot?.first }
        var rendered = false
        var failures = 0
        return ExoPlayer.Builder(context).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            repeatMode = Player.REPEAT_MODE_ALL
            // Filled to the edges whatever its shape: a wallpaper has no bars.
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            // Home is drawn over it: the display keeps its rate (see LiveWallpaper).
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true
                    failures = 0
                    showing = isPlaying
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showing = isPlaying && rendered
                }

                // Playing them in turn where the glass cannot be read off the video: the picture under
                // it stayed the first film's, and so did the glass of the plate and Control Center
                // through every film after it (TCL MT9655, issue #1158). Each film's poster now.
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId ?: return
                    if (copyGlassPossible || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
                    scope.launch {
                        val poster = withContext(Dispatchers.IO) { Wallpapers.videoPoster(id, Wallpapers.screenW, Wallpapers.screenH) } ?: return@launch
                        Wallpapers.useLiveFrame(poster, id, 0L)
                        // And the plate's glass while it moves, made once from the first film's picture
                        // (sampleGlass): it kept that film's colours until Control Center was opened
                        // (issue #1158 on 1.81, the reporter on MT9655).
                        Wallpapers.useStillFilmFrost()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("video wallpaper failed (${error.errorCodeName})")
                    // Behind an app or a screensaver the decoder is theirs: no retry.
                    if (!keepNow) return
                    // The picture rather than the black video plane until a frame comes again; the
                    // film comes back in over it (LaunchedEffect(play)). Black for seconds after each
                    // failure on Hisense VIDAA_TV MTK9652, until the next film played (issue #1251).
                    scope.launch { cover.snapTo(0f) }
                    // The same film again, from where it was: the decoder fails at times as the film
                    // moves again on that TV, and dropping the film for good on any error left the
                    // owner fewer of their videos each time. The next one only after three in a
                    // row (as LiveWallpaper); after six, the picture stays.
                    if (++failures >= 6) return
                    if (failures % 3 == 0 && mediaItemCount > 1) seekToNextMediaItem()
                    prepare()
                }
            })
            setMediaItems(
                films.map { MediaItem.Builder().setUri(it.file.toUri()).setMediaId(it.id).build() },
                first.coerceAtLeast(0),
                if (first >= 0) spot?.second ?: 0L else 0L,
            )
            surface?.let { setVideoSurfaceView(it) }
            prepare()
        }
    }

    /**
     * The frame [film] stopped on near [at], at the screen's size, and its moment: copied off the
     * video plane where PixelCopy reads it, else the key frame nearest decoded in the app, which
     * leaves the hardware decoder to the player. The media server's retriever only once the player
     * has let go ([held] false): it needs a hardware decoder too, and as the paused player moved
     * again beside it, it failed (Hisense VIDAA_TV MTK9652, issue #1251); nor where it jams the media
     * server (TCL's extractor, issue #1188). The film's poster last, so the picture is at least the
     * film that played (issue #1158).
     */
    suspend fun frameOf(film: String, at: Long, held: Boolean): Pair<Bitmap, Long>? {
        val w = surface?.width?.takeIf { it > 0 } ?: Wallpapers.screenW
        val h = surface?.height?.takeIf { it > 0 } ?: Wallpapers.screenH
        if (held) surface?.let { copyFrame(it) }?.let { return it to at }
        keyFrameOfVideo(context, film, at, w, h)?.let { return it }
        return withContext(Dispatchers.IO) {
            (if (held || fileFramesJam(context)) null else fileFrame(context, film, at, w, h))?.let { it to at }
                ?: Wallpapers.videoPoster(film, w, h)?.let { it to at }
        }
    }

    // The picture catching up behind an app (letGo); the film waits for it before it goes on.
    var catchUp by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    /**
     * Lets the player go with Home out of sight, and the picture catches up behind it to where the
     * film was: stopped and left standing in (Control Center, then an app from it), no frame had
     * been taken, and Home came back on an older picture with the film starting over from its spot.
     */
    fun letGo() {
        val p = player ?: return
        val film = p.currentMediaItem?.mediaId
        val at = p.currentPosition
        release()
        if (film == null) return
        val spot = Wallpapers.videoSpot()
        if (spot?.first == film && kotlin.math.abs(spot.second - at) < SAME_SPOT_MS) return
        catchUp = scope.launch {
            frameOf(film, at, held = false)?.let { (frame, moment) -> Wallpapers.useLiveFrame(frame, film, moment) }
        }
    }

    // The plate's glass over the moving video, where PixelCopy reads it. Not from the file: an
    // owner's video may be 4K HEVC, and a second decoder beside the player's may not be there.
    LaunchedEffect(play, showing) {
        if (!play || !showing) return@LaunchedEffect
        sampleGlass({ player }, canTake = { copyGlassPossible }) { surface?.let { copyGlass(it) } }
    }

    // Let go as Aerial stops, not at the next frame, which does not come then (rememberStops).
    val stops = rememberStops {
        if (player == null) return@rememberStops false
        DiagLog.i("video wallpaper lets go as Aerial stops")
        letGo()
        scope.launch { cover.snapTo(0f) }
        true
    }

    LaunchedEffect(play, stops) {
        if (play) {
            // A frame still read from the file behind an app (frameOf, the player let go) holds a
            // hardware decoder: the film waits for it a little rather than fail beside it.
            withTimeoutOrNull(RETRIEVER_WAIT_MS) { while (retrieving) delay(50) }
            catchUp?.let { withTimeoutOrNull(CATCH_UP_WAIT_MS) { it.join() } }
            val p = player ?: open()?.also { player = it } ?: return@LaunchedEffect
            DiagLog.i("video wallpaper starts (${p.currentMediaItem?.mediaId} at ${p.currentPosition} ms, idle $idle)")
            Wallpapers.dropFilmFrost()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.playWhenReady = true
            // In over the picture each time frames come: at the start, and again after a failure
            // took it down (onPlayerError).
            snapshotFlow { showing }.collect { if (it && cover.targetValue < 1f) cover.animateTo(1f, tween(1200, easing = Motion.EaseOut)) }
        } else {
            val p = player ?: return@LaunchedEffect
            if (!keepNow) {
                cover.snapTo(0f)
                letGo()
                return@LaunchedEffect
            }
            p.pause()
            DiagLog.i("video wallpaper stops at ${p.currentPosition} ms")
            if (cover.value > 0f) {
                val film = p.currentMediaItem?.mediaId
                val at = p.currentPosition
                // The paused film stays while Home shows it plainly, as the aerial film does: no frame
                // read as Control Center opens or its keys stop and start the film, which on Hisense
                // VIDAA_TV MTK9652 failed the decoder and left Home black (issue #1251). It gives way
                // under a frosted panel, into an app, or once no key has come for KEEP_STOPPED_MS.
                val quiet = if (standInNow && film != null) untilQuietOrLeft({ standInNow && keepNow }, { lastInputNow }) else false
                if (!keepNow) {
                    // Into an app: let go at once, the picture caught up behind it.
                    cover.snapTo(0f)
                    letGo()
                    return@LaunchedEffect
                }
                val frame = film?.let { frameOf(it, at, held = true) }
                if (frame != null) Wallpapers.useLiveFrame(frame.first, film, frame.second)
                if (quiet) DiagLog.i("video wallpaper picture takes over from the stopped film")
                cover.animateTo(0f, tween(if (frame != null) 320 else 600, easing = Motion.EaseOut))
                if (quiet) {
                    release()
                    return@LaunchedEffect
                }
            }
            delay(KEEP_STOPPED_MS)
            release()
        }
    }
    LaunchedEffect(keep) {
        if (!keep && !play && player != null && cover.value == 0f) letGo()
    }
    DisposableEffect(Unit) { onDispose { release() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> FilmSlot(ctx).also { surface = it.surface; player?.setVideoSurfaceView(it.surface) } },
        onRelease = { player?.clearVideoSurfaceView(it.surface); surface = null },
    )
}

/**
 * The key frame of the owner's video [id] nearest [atMs] at [w]×[h], with its own moment, decoded
 * in the app (AerialStream.keyFrameOfFile); null where it cannot be (not MP4, not H.264 or HEVC,
 * no software decoder, a green frame).
 */
private suspend fun keyFrameOfVideo(context: android.content.Context, id: String, atMs: Long, w: Int, h: Int): Pair<Bitmap, Long>? {
    val film = withContext(Dispatchers.IO) { ScreensaverVideos.all(context).firstOrNull { it.id == id } } ?: return null
    val started = android.os.SystemClock.elapsedRealtime()
    val frame = try {
        withTimeoutOrNull(VIDEO_KEY_FRAME_MS) { AerialStream.keyFrameOfFile(film.file, id, atMs, w, h) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        DiagLog.i("video wallpaper key frame in the app failed after ${android.os.SystemClock.elapsedRealtime() - started} ms: ${e.javaClass.simpleName} ${e.message}")
        return null
    } ?: run {
        DiagLog.i("video wallpaper key frame in the app took over $VIDEO_KEY_FRAME_MS ms")
        return null
    }
    DiagLog.i("video wallpaper key frame decoded in the app in ${android.os.SystemClock.elapsedRealtime() - started} ms (${frame.codec} ${frame.decodeMs} ms, ${frame.timeMs - atMs} ms off)")
    // Green as a lost colour plane reads (see LiveWallpaper's keyFrameDecodedInApp); a dark frame
    // of a night film is the film's own.
    if (unreadable(frame.bitmap) == "green") {
        DiagLog.i("video wallpaper decoded frame is green; not used")
        return null
    }
    return frame.bitmap to frame.timeMs
}

/** The longest a key frame of the owner's video may take to decode in the app (a 4K HEVC one on a slow TV): then the next way. */
private const val VIDEO_KEY_FRAME_MS = 4_000L

/** Frame [atMs] of the owner's video [id], screen-sized and drawable on, or null. */
private fun fileFrame(context: android.content.Context, id: String, atMs: Long, w: Int, h: Int): Bitmap? {
    val film = ScreensaverVideos.all(context).firstOrNull { it.id == id } ?: return null
    val mmr = MediaMetadataRetriever()
    retrieving = true
    return try {
        mmr.setDataSource(film.file.path)
        val frame = mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        app.orchard.tv.wallpaper.Images.cover(frame, w, h).let {
            if (it.isMutable && it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, true)
        }
    } catch (e: Exception) {
        DiagLog.i("video wallpaper frame not decoded: ${e.javaClass.simpleName}")
        null
    } finally {
        runCatching { mmr.release() }
        retrieving = false
    }
}

/** Whether [fileFrame] is reading a frame through the media server's retriever. */
@Volatile private var retrieving = false

private const val RETRIEVER_WAIT_MS = 3_000L

/** How long the film waits for the picture catching up behind an app (letGo) before it goes on anyway. */
private const val CATCH_UP_WAIT_MS = 1_500L

/** The picture's spot is near enough where the film was let go: no catching up. */
private const val SAME_SPOT_MS = 1_500L
