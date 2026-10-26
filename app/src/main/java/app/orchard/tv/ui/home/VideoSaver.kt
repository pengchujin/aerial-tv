package app.orchard.tv.ui.home

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.ui.components.FilmSlot
import app.orchard.tv.system.DiagLog
import app.orchard.tv.wallpaper.ScreensaverVideo
import app.orchard.tv.wallpaper.ScreensaverVideos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

/** A local, silent playlist, shared by Home and AerialDream. An empty or broken set falls back. */
@Composable
internal fun VideoSaver(steps: SharedFlow<Int>, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val films by produceState<List<ScreensaverVideo>?>(null) {
        value = withContext(Dispatchers.IO) { ScreensaverVideos.all(context).shuffled() }
    }
    val loaded = films ?: return
    if (loaded.isEmpty()) {
        LaunchedEffect(Unit) { onUnavailable() }
        return
    }
    LocalFilms(loaded, steps, onUnavailable)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LocalFilms(films: List<ScreensaverVideo>, steps: SharedFlow<Int>, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setTargetBufferBytes(12 * 1024 * 1024)
                .setBufferDurationsMs(5_000, 15_000, 500, 1_000).build(),
        ).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            repeatMode = Player.REPEAT_MODE_ALL
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }
    var attempt by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }

    fun skip(step: Int) {
        if (player.mediaItemCount == 0) return
        if (step < 0) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
    fun failed() {
        playing = false
        if (player.mediaItemCount <= 1) { onUnavailable(); return }
        // Only this session's queue changes. Never delete a film because one decoder rejected it.
        player.removeMediaItem(player.currentMediaItemIndex)
        player.prepare()
        attempt++
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                playing = true
                DiagLog.i("local screensaver first frame: ${player.currentMediaItem?.mediaId}")
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) playing = false
                attempt++
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) playing = false
                else if (playbackState == Player.STATE_READY && player.isPlaying) playing = true
            }
            override fun onPlayerError(error: PlaybackException) {
                DiagLog.i("local screensaver failed: ${error.errorCodeName}")
                failed()
            }
        }
        player.addListener(listener)
        player.setMediaItems(films.map { MediaItem.Builder().setUri(it.file.toUri()).setMediaId(it.id).build() })
        player.prepare()
        player.playWhenReady = true
        onDispose { player.removeListener(listener); player.release() }
    }
    LaunchedEffect(steps) { steps.collect { skip(it) } }
    LaunchedEffect(attempt, playing) {
        if (!playing) { delay(8_000); failed() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.align(Alignment.Center).aspectRatio(aspect),
            factory = { ctx -> FilmSlot(ctx).also { player.setVideoSurfaceView(it.surface) } },
            onRelease = { player.clearVideoSurfaceView(it.surface) },
        )
    }
}
