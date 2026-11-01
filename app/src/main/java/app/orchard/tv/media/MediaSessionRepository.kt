package app.orchard.tv.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import app.orchard.tv.guard.RevivalListener

/** Observes media only while Home is visible. All callbacks and state writes use the main thread. */
class MediaSessionRepository(
    context: Context,
    private val onState: (NowPlayingState?) -> Unit,
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = ComponentName(app, RevivalListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var selected: MediaController? = null
    private var previousPackage: String? = null
    private var audioCallback: AudioManager.AudioPlaybackCallback? = null
    private var controlGeneration = 0
    private val accessObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = querySessions()
    }
    private val observed = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        handler.post { if (started) choose(sessions.orEmpty()) }
    }
    fun start() {
        if (started) {
            querySessions()
            return
        }
        started = true
        try {
            app.contentResolver.registerContentObserver(Settings.Secure.getUriFor("enabled_notification_listeners"), false, accessObserver)
            manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
            if (Build.VERSION.SDK_INT >= 26) {
                audioCallback = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: List<android.media.AudioPlaybackConfiguration>) {
                        if (started) querySessions()
                    }
                }.also { audio.registerAudioPlaybackCallback(it, handler) }
            }
            querySessions()
        } catch (_: SecurityException) {
            stop()
        } catch (_: RuntimeException) {
            stop()
        }
    }

    fun stop() {
        started = false
        controlGeneration++
        runCatching { app.contentResolver.unregisterContentObserver(accessObserver) }
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsChanged) }
        if (Build.VERSION.SDK_INT >= 26) audioCallback?.let { runCatching { audio.unregisterAudioPlaybackCallback(it) } }
        audioCallback = null
        observed.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        observed.clear()
        swap(null)
        previousPackage = null
        onState(null)
    }

    private fun querySessions() {
        if (!started) return
        val sessions = runCatching { manager.getActiveSessions(listener) }.getOrElse {
            choose(emptyList())
            return
        }
        choose(sessions)
    }

    private fun choose(sessions: List<MediaController>) {
        val external = sessions.filter { it.packageName != app.packageName }
        val onlyExternal = external.size == 1
        val tokens = external.mapTo(HashSet()) { it.sessionToken }
        observed.keys.toList().filter { it !in tokens }.forEach { token ->
            observed.remove(token)?.let { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        }
        external.forEach { controller ->
            if (controller.sessionToken !in observed) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = querySessions()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = querySessions()
                    override fun onSessionDestroyed() = querySessions()
                }
                if (runCatching { controller.registerCallback(callback, handler) }.isSuccess) {
                    observed[controller.sessionToken] = controller to callback
                }
            }
        }
        val candidates = external.map { controller ->
            val metadata = runCatching { controller.metadata }.getOrNull()
            val playback = runCatching { controller.playbackState }.getOrNull()
            SessionCandidate(
                packageName = controller.packageName,
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                isPlaying = playbackStatus(playback, onlyExternal) == true,
            )
        }
        val index = selectSession(candidates, previousPackage)
        swap(external.getOrNull(index))
        refresh()
    }

    private fun swap(controller: MediaController?) {
        if (selected?.sessionToken == controller?.sessionToken) return
        selected = controller
        previousPackage = controller?.packageName
    }

    private fun refresh() {
        if (!started) return
        val controller = selected ?: run { onState(null); return }
        val metadata = runCatching { controller.metadata }.getOrNull()
        val playback = runCatching { controller.playbackState }.getOrNull()
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) {
            onState(null)
            return
        }
        val media = metadata ?: run { onState(null); return }
        val actions = playback?.actions ?: 0L
        val playing = playbackStatus(playback, observed.size == 1)
        val artwork = runCatching {
            media.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: media.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: media.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull()
        onState(NowPlayingState(
            appPackage = controller.packageName,
            appName = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(controller.packageName, 0)).toString() }
                .getOrDefault(controller.packageName),
            title = title,
            artist = media.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: media.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            artwork = artwork?.scaledForCard(),
            isPlaying = playing,
            canPlayPause = when (playing) {
                true -> actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L
                false -> actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L
                null -> false
            },
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        ))
    }

    fun togglePlayPause() = withSelected { controller, state ->
        val playing = playbackStatus(state, observed.size == 1)
        when {
            playing == true && state.actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L -> {
                controller.transportControls.pause(); true
            }
            playing == false && state.actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L -> {
                controller.transportControls.play(); true
            }
            else -> false
        }
    }

    private fun playbackStatus(state: PlaybackState?, onlyExternal: Boolean): Boolean? = when (state?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING -> true
        PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> false
        PlaybackState.STATE_NONE -> if (onlyExternal) audio.isMusicActive else null
        else -> null
    }

    fun skipPrevious() = withSelected { controller, state ->
        if (state.actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS == 0L) false
        else { controller.transportControls.skipToPrevious(); true }
    }

    fun skipNext() = withSelected { controller, state ->
        if (state.actions and PlaybackState.ACTION_SKIP_TO_NEXT == 0L) false
        else { controller.transportControls.skipToNext(); true }
    }

    private fun withSelected(action: (MediaController, PlaybackState) -> Boolean) {
        if (!started) return
        val controller = selected ?: return
        val playback = runCatching { controller.playbackState }.getOrNull() ?: return
        if (!runCatching { action(controller, playback) }.getOrDefault(false)) return
        // Some TV apps advertise controls but never send a metadata or playback callback. Reconcile
        // this one user action a few times while the remote app processes it; there is no idle poll.
        val generation = ++controlGeneration
        val token = controller.sessionToken
        querySessions()
        for (delayMs in longArrayOf(300L, 1_200L, 3_000L)) {
            handler.postDelayed({
                if (started && generation == controlGeneration && selected?.sessionToken == token) querySessions()
            }, delayMs)
        }
    }


    private fun Bitmap.scaledForCard(): Bitmap {
        val edge = maxOf(width, height)
        if (edge <= 256) return this
        val ratio = 256f / edge
        return Bitmap.createScaledBitmap(this, (width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1), true)
    }
}
