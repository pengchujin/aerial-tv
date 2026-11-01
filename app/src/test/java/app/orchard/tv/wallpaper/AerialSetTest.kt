package app.orchard.tv.wallpaper

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AerialSetTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private var day = "2026-10-29"

    @Before
    fun setUp() {
        app.getSharedPreferences("aerial_set", 0).edit().clear().commit()
        AerialSet.today = { day }
    }

    @After
    fun tearDown() {
        AerialSet.today = { java.time.LocalDate.now().toString() }
    }

    @Test
    fun startsWithOneFilmAndNothingNewTheSameDay() {
        assertEquals(1, AerialSet.films(app).size)
        assertFalse(AerialSet.due(app))
        assertNull(AerialSet.takeDaily(app))
    }

    @Test
    fun aNewDayBringsOneNewFilmFirst() {
        val first = AerialSet.films(app).single()
        day = "2026-10-30"
        assertTrue(AerialSet.due(app))
        val new = AerialSet.takeDaily(app)
        assertNotNull(new)
        new!!
        assertEquals(listOf(new.id, first.id), AerialSet.films(app).map { it.id })
        // Only one a day.
        assertNull(AerialSet.takeDaily(app))
    }

    @Test
    fun theNewFilmIsTheOneChosenAhead() {
        AerialSet.films(app)
        day = "2026-10-30"
        val ahead = AerialSet.nextNew(app)
        assertEquals(ahead.id, AerialSet.takeDaily(app)?.id)
    }

    @Test
    fun keepsAtMostThreeNewestFirstWithoutRepeats() {
        AerialSet.films(app)
        val added = (1..4).map { AerialSet.addNew(app).id }
        val kept = AerialSet.films(app).map { it.id }
        assertEquals(AerialSet.MAX, kept.size)
        assertEquals(added.reversed().take(AerialSet.MAX), kept)
        assertEquals(kept.size, kept.toSet().size)
    }

    @Test
    fun playlistStartsAtTheCurrentFilmAndGoesRound() {
        AerialSet.films(app)
        AerialSet.addNew(app)
        AerialSet.addNew(app)
        val set = AerialSet.films(app).map { it.id }
        val list = AerialSet.playlist(app, set[1]).map { it.id }
        assertEquals(listOf(set[1], set[2], set[0]), list)
    }

    @Test
    fun aFilmFromOutsideTheSetJoinsIt() {
        AerialSet.films(app)
        val other = Aerials.all(app).first { it.id !in AerialSet.films(app).map { f -> f.id } }
        val list = AerialSet.playlist(app, other.id)
        assertEquals(other.id, list.first().id)
        assertTrue(AerialSet.films(app).any { it.id == other.id })
    }
}
