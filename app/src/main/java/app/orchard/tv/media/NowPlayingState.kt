package app.orchard.tv.media

import android.graphics.Bitmap

/** Only media information needed to draw and operate the Home card. Never persisted. */
data class NowPlayingState(
    val appPackage: String,
    val appName: String,
    val title: String,
    val artist: String?,
    val artwork: Bitmap?,
    /** Null when the session does not report a usable state and audio cannot be attributed. */
    val isPlaying: Boolean?,
    val canPlayPause: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
)

/** A small, testable description of a session before any artwork is decoded. */
internal data class SessionCandidate(
    val packageName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val isPlaying: Boolean,
)

/** Prefer active music, then keep a paused selection stable until its session disappears. */
internal fun selectSession(candidates: List<SessionCandidate>, previousPackage: String?): Int {
    val eligible = candidates.indices.filter { index ->
        val item = candidates[index]
        !item.title.isNullOrBlank()
    }
    if (eligible.isEmpty()) return -1
    val musicLike = eligible.filter { index ->
        val item = candidates[index]
        !item.artist.isNullOrBlank() || !item.album.isNullOrBlank()
    }
    val pool = musicLike.ifEmpty { eligible }
    return pool.firstOrNull { candidates[it].isPlaying }
        ?: pool.firstOrNull { candidates[it].packageName == previousPackage }
        ?: pool.first()
}
