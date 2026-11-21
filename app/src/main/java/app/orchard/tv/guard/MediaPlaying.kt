package app.orchard.tv.guard

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.core.content.getSystemService

/**
 * Whether a player has announced that it is playing — the one thing the idle screensaver goes by
 * (see HomeGuardService.idleDreamCheck before showing it, and IdleScreensaverActivity, which ends
 * on it). A player announces itself through a media session; while one is in a busy state the
 * screensaver stays away, and once none is, the idle delay runs again.
 *
 * Busy, not just PLAYING: an unbuffered stream sits in BUFFERING before its first frame, seeking
 * reports FAST_FORWARDING / REWINDING, and the viewer is waiting for the film in all of them
 * rather than idle. A paused session is not busy: paused is what the idle delay is for.
 *
 * The audio stream is deliberately not a second source: a box can have sound running with nothing
 * announcing it, and the rule here is that only an announcement keeps the screensaver away.
 */
internal object MediaPlaying {
    private val busyStates = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    )

    fun playing(context: Context): Boolean = runCatching {
        val manager = context.getSystemService<MediaSessionManager>() ?: return@runCatching false
        // RevivalListener is the NotificationListenerService whose grant lets an app ask for the
        // active sessions; without that grant this throws and answers false.
        val listener = ComponentName(context, RevivalListener::class.java)
        manager.getActiveSessions(listener).any { it.playbackState?.state in busyStates }
    }.getOrDefault(false)
}
