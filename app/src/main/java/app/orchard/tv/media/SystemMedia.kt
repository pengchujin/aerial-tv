package app.orchard.tv.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orchard.tv.guard.RevivalListener

internal class SystemMedia(context: Context) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listener = ComponentName(context, RevivalListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val controllers = mutableMapOf<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var ordered = emptyList<MediaSession.Token>()
    private var selected: MediaController? = null
    private val activeListener = MediaSessionManager.OnActiveSessionsChangedListener { update(it.orEmpty()) }

    var track by mutableStateOf<Track?>(null)
        private set
    var available by mutableStateOf(false)
        private set

    data class Track(
        val title: String?,
        val subtitle: String,
        val artwork: Bitmap?,
        val artworkUri: Uri?,
        val playing: Boolean,
        val actions: Long,
    ) {
        fun supports(action: Long) = actions and action != 0L
    }

    fun start() {
        try {
            manager.addOnActiveSessionsChangedListener(activeListener, listener, handler)
            available = true
            update(manager.getActiveSessions(listener))
        } catch (_: SecurityException) {
            stop()
        }
    }

    fun stop() {
        manager.removeOnActiveSessionsChangedListener(activeListener)
        controllers.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        controllers.clear()
        ordered = emptyList()
        selected = null
        track = null
        available = false
    }

    private fun update(sessions: List<MediaController>) {
        if (!available) return
        ordered = sessions.map { it.sessionToken }
        for (token in controllers.keys.toList() - ordered.toSet()) remove(token)
        for (controller in sessions) {
            val token = controller.sessionToken
            if (token in controllers) continue
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
                override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
                override fun onSessionDestroyed() {
                    remove(token)
                    ordered = ordered - token
                    refresh()
                }
            }
            controllers[token] = controller to callback
            controller.registerCallback(callback, handler)
        }
        refresh()
    }

    private fun remove(token: MediaSession.Token) {
        controllers.remove(token)?.let { (controller, callback) -> controller.unregisterCallback(callback) }
    }

    private fun refresh() {
        val candidates = ordered.mapNotNull { controllers[it]?.first }
        fun MediaController.playing() = playbackState?.state == PlaybackState.STATE_PLAYING
        val controller = candidates.firstOrNull { it.playing() }
            ?: candidates.firstOrNull { it.sessionToken == selected?.sessionToken &&
                it.playbackState?.state in transitioningStates }
            ?: candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
        selected = controller
        track = controller?.let {
            val metadata = it.metadata
            Track(
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: metadata?.description?.title?.toString(),
                subtitle = listOfNotNull(
                    metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                ).filter(String::isNotBlank).distinct().joinToString(" · "),
                artwork = metadata?.description?.iconBitmap?.takeUnless { bitmap -> bitmap.isRecycled },
                artworkUri = sessionArtworkUri(it.packageName, metadata?.description?.iconUri),
                playing = it.playing(),
                actions = it.playbackState?.actions ?: 0L,
            )
        }
    }

    private val transitioningStates = setOf(
        PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    fun previous() = control(PlaybackState.ACTION_SKIP_TO_PREVIOUS) { skipToPrevious() }
    fun next() = control(PlaybackState.ACTION_SKIP_TO_NEXT) { skipToNext() }

    fun toggle() {
        val current = track ?: return
        val action = if (current.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        control(action or PlaybackState.ACTION_PLAY_PAUSE) {
            if (current.playing) pause() else play()
        }
    }

    private fun control(action: Long, command: MediaController.TransportControls.() -> Unit) {
        if (track?.supports(action) == true) selected?.transportControls?.command()
    }
}
