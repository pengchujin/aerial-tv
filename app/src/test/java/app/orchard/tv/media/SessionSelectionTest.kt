package app.orchard.tv.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionSelectionTest {
    @Test fun playingMusicWinsOverPausedAndTitleOnlyVideo() {
        val sessions = listOf(
            SessionCandidate("video", "Episode", null, null, true),
            SessionCandidate("spotify", "Song", "Artist", "Album", true),
            SessionCandidate("other", "Old song", "Singer", null, false),
        )
        assertEquals(1, selectSession(sessions, "other"))
    }

    @Test fun pausedSelectionStaysUntilAnotherMusicSessionPlays() {
        val sessions = listOf(
            SessionCandidate("first", "First", "Artist", null, false),
            SessionCandidate("second", "Second", "Artist", null, false),
        )
        assertEquals(1, selectSession(sessions, "second"))
        assertEquals(0, selectSession(sessions.mapIndexed { index, item -> item.copy(isPlaying = index == 0) }, "second"))
    }

    @Test fun missingTitleNeverProducesCard() {
        assertEquals(-1, selectSession(listOf(SessionCandidate("player", null, "Artist", null, true)), null))
    }
}
