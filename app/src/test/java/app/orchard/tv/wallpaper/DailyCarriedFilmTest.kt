@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.orchard.tv.wallpaper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.exoplayer.ExoPlayer
import app.orchard.tv.ui.home.DailyCarriedFilm
import app.orchard.tv.ui.home.LiveFilm
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

class DailyFilmActivity : ComponentActivity() {
    val film = LiveFilm()
    var saverVisible by mutableStateOf(true)
    var day by mutableStateOf(LocalDate.of(2026, 11, 22))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DailyCarriedFilm(film, saverVisible, day) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DailyCarriedFilmTest {
    @get:Rule val compose = createEmptyComposeRule()
    @After fun resetDate() { AerialSet.today = { LocalDate.now().toString() } }

    @Test fun latePlayerChangesOnceAndHiddenSaverDoesNotConsumeTomorrow() {
        val app = RuntimeEnvironment.getApplication()
        AerialSet.today = { "2026-11-21" }
        AerialSet.films(app)
        AerialSet.today = { "2026-11-22" }
        val controller = Robolectric.buildActivity(DailyFilmActivity::class.java).setup().visible()
        val activity = controller.get()
        val added = mutableListOf<String>()
        var player: ExoPlayer? = null
        try {
            compose.waitForIdle()
            assertTrue(AerialSet.due(app))
            compose.runOnIdle {
                activity.film.insert = { added += it.id }
                player = ExoPlayer.Builder(activity).build()
                activity.film.player = player
            }
            compose.waitForIdle()
            assertEquals(1, added.size)
            assertFalse(AerialSet.due(app))
            compose.runOnIdle { activity.saverVisible = false }
            compose.waitForIdle()
            compose.runOnIdle {
                AerialSet.today = { "2026-11-23" }
                activity.day = LocalDate.of(2026, 11, 23)
                activity.film.player = null
            }
            compose.waitForIdle()
            compose.runOnIdle { activity.film.player = player }
            compose.waitForIdle()
            assertEquals(1, added.size)
            assertTrue(AerialSet.due(app))
            compose.runOnIdle { activity.saverVisible = true }
            compose.waitForIdle()
            assertEquals(2, added.size)
            assertNotEquals(added[0], added[1])
            compose.runOnIdle { activity.film.insert = { added += it.id } }
            compose.waitForIdle()
            assertEquals(2, added.size)
            assertTrue(AerialSet.films(app).size <= 3)
        } finally {
            compose.runOnIdle { activity.film.player = null; player?.release() }
            controller.pause().stop().destroy()
        }
    }

    @Test fun rejectedInsertionKeepsDailyCandidateForTheNextAttempt() {
        val app = RuntimeEnvironment.getApplication()
        AerialSet.today = { "2026-11-21" }; AerialSet.films(app)
        AerialSet.today = { "2026-11-22" }
        val expected = AerialSet.nextNew(app)
        assertFalse(AerialSet.takeDailyWhen(app) { false })
        assertTrue(AerialSet.due(app))
        assertEquals(expected.id, AerialSet.takeDaily(app)?.id)
        assertNull(AerialSet.takeDaily(app))
    }
}
