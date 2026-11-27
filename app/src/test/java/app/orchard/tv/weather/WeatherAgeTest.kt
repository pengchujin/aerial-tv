package app.orchard.tv.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WeatherAgeTest {
    private val min = 60 * 1000L
    private val hour = 60 * min
    private val day = 24 * hour
    private val reading = WeatherNow(12, WeatherKind.Clear, day = true, readAt = 1_000_000_000L)

    @Test
    fun aFreshReadingShowsNoAge() {
        assertNull(Weather.hoursOld(reading, reading.readAt + 2 * hour + 59 * min))
    }

    @Test
    fun anOldReadingShowsWholeHours() {
        assertEquals(3, Weather.hoursOld(reading, reading.readAt + 3 * hour))
        assertEquals(11, Weather.hoursOld(reading, reading.readAt + 11 * hour + 59 * min))
    }

    @Test
    fun readingVisibilityHandlesAgeAndBackwardClockJump() {
        assertTrue(Weather.isReadingShown(reading.readAt, reading.readAt + 30 * min))
        // HomeChrome ticks on the minute, so a freshly fetched reading can sit up to a minute ahead.
        assertTrue(Weather.isReadingShown(reading.readAt, reading.readAt - 60_000L))
        assertTrue(Weather.isReadingShown(reading.readAt, reading.readAt + 11 * hour + 59 * min))
        assertFalse(Weather.isReadingShown(reading.readAt, reading.readAt + 12 * hour))
        // A real backward clock jump hides a reading whose true age is unknown.
        assertFalse(Weather.isReadingShown(reading.readAt, reading.readAt - 10 * min))
        assertFalse(Weather.isReadingShown(reading.readAt, reading.readAt - day))
        assertNull(Weather.hoursOld(reading, reading.readAt - hour))
    }

    @Test
    fun backwardClockJumpMakesRefreshDueImmediatelyWithZeroDelay() {
        val lastAttempt = 2_000_000_000L
        val jumpedBack = lastAttempt - 5 * hour
        assertTrue(Weather.isRefreshDue(jumpedBack, lastAttempt, failed = false, failures = 0))
        assertEquals(0L, Weather.dueInMs(jumpedBack, lastAttempt, failed = false, failures = 0))
        assertTrue(Weather.isRefreshDue(jumpedBack, lastAttempt, failed = true, failures = 3))
        assertEquals(0L, Weather.dueInMs(jumpedBack, lastAttempt, failed = true, failures = 3))
    }

    @Test
    fun normalRefreshAndFailureBackoffIntervalsWork() {
        val lastAttempt = 2_000_000_000L
        // Healthy hourly schedule.
        assertFalse(Weather.isRefreshDue(lastAttempt + 30 * min, lastAttempt, failed = false, failures = 0))
        assertEquals(30 * min, Weather.dueInMs(lastAttempt + 30 * min, lastAttempt, failed = false, failures = 0))
        assertTrue(Weather.isRefreshDue(lastAttempt + hour, lastAttempt, failed = false, failures = 0))
        assertEquals(0L, Weather.dueInMs(lastAttempt + hour, lastAttempt, failed = false, failures = 0))

        // Exponential retry backoff capped at 1 hour: 3, 6, 12, 24, 48, 60 min.
        assertEquals(3 * min, Weather.retryMs(1))
        assertEquals(6 * min, Weather.retryMs(2))
        assertEquals(12 * min, Weather.retryMs(3))
        assertEquals(24 * min, Weather.retryMs(4))
        assertEquals(48 * min, Weather.retryMs(5))
        assertEquals(60 * min, Weather.retryMs(6))
        assertEquals(60 * min, Weather.retryMs(10))

        // Failed attempt respects backoff unless network comes up.
        assertFalse(Weather.isRefreshDue(lastAttempt + 2 * min, lastAttempt, failed = true, failures = 1))
        assertEquals(min, Weather.dueInMs(lastAttempt + 2 * min, lastAttempt, failed = true, failures = 1))
        assertTrue(Weather.isRefreshDue(lastAttempt + 2 * min, lastAttempt, failed = true, failures = 1, networkCameUp = true))
        assertTrue(Weather.isRefreshDue(lastAttempt + 3 * min, lastAttempt, failed = true, failures = 1))
        assertEquals(0L, Weather.dueInMs(lastAttempt + 3 * min, lastAttempt, failed = true, failures = 1))
        assertFalse(Weather.isRefreshDue(lastAttempt + 5 * min, lastAttempt, failed = true, failures = 2))
        assertTrue(Weather.isRefreshDue(lastAttempt + 6 * min, lastAttempt, failed = true, failures = 2))
    }

    @Test
    fun relocationHandlesStaleAndBackwardClockJump() {
        val locatedAt = 2_000_000_000L
        assertFalse(Weather.needsRelocation(locatedAt + 12 * hour, locatedAt))
        assertFalse(Weather.needsRelocation(locatedAt + day, locatedAt))
        assertTrue(Weather.needsRelocation(locatedAt + day + 1L, locatedAt))
        assertTrue(Weather.needsRelocation(locatedAt - 1L, locatedAt))
        assertTrue(Weather.needsRelocation(locatedAt - day, locatedAt))
    }

    @Test
    fun missingCoordinatesFailBeforeOpenMeteo() {
        var locateCalls = 0
        var saved = false
        try {
            Weather.resolveCoordinates(
                chosen = false,
                savedLat = Float.NaN,
                savedLon = Float.NaN,
                locatedAtMs = 0L,
                nowMs = 2_000_000_000L,
                locate = { locateCalls++; null },
                onLocated = { saved = true },
            )
            fail("Expected IOException when no coordinates are available")
        } catch (e: IOException) {
            assertEquals("no location", e.message)
        }
        assertEquals(1, locateCalls)
        assertFalse(saved)

        // Chosen location with missing longitude also fails without calling locate().
        locateCalls = 0
        try {
            Weather.resolveCoordinates(
                chosen = true,
                savedLat = 39.9f,
                savedLon = Float.NaN,
                locatedAtMs = 0L,
                nowMs = 2_000_000_000L,
                locate = { locateCalls++; null },
                onLocated = { saved = true },
            )
            fail("Expected IOException when longitude is missing")
        } catch (e: IOException) {
            assertEquals("no location", e.message)
        }
        assertEquals(0, locateCalls)
    }

    @Test
    fun existingCoordinatesSurviveFailedRelocationAndUpdateOnSuccess() {
        val locatedAt = 2_000_000_000L
        var locateCalls = 0
        var persisted: Weather.Guess? = null

        // Stale relocation fails: keep saved coordinates.
        val kept = Weather.resolveCoordinates(
            chosen = false,
            savedLat = 23.02f,
            savedLon = 113.12f,
            locatedAtMs = locatedAt,
            nowMs = locatedAt + day + hour,
            locate = { locateCalls++; null },
            onLocated = { persisted = it },
        )
        assertEquals(23.02f to 113.12f, kept)
        assertEquals(1, locateCalls)
        assertNull(persisted)

        // Backward clock jump triggers relocation; failure still keeps saved coordinates.
        val keptAfterJump = Weather.resolveCoordinates(
            chosen = false,
            savedLat = 23.02f,
            savedLon = 113.12f,
            locatedAtMs = locatedAt,
            nowMs = locatedAt - hour,
            locate = { locateCalls++; null },
            onLocated = { persisted = it },
        )
        assertEquals(23.02f to 113.12f, keptAfterJump)
        assertEquals(2, locateCalls)
        assertNull(persisted)

        // Relocation succeeds: update coordinates and persist guess.
        val fresh = Weather.Guess(31.23f, 121.47f, "Shanghai", cn = true)
        val updated = Weather.resolveCoordinates(
            chosen = false,
            savedLat = 23.02f,
            savedLon = 113.12f,
            locatedAtMs = locatedAt,
            nowMs = locatedAt + day + hour,
            locate = { locateCalls++; fresh },
            onLocated = { persisted = it },
        )
        assertEquals(31.23f to 121.47f, updated)
        assertEquals(3, locateCalls)
        assertEquals(fresh, persisted)

        // Freshly located or chosen place does not call locate().
        val cached = Weather.resolveCoordinates(
            chosen = false,
            savedLat = 31.23f,
            savedLon = 121.47f,
            locatedAtMs = locatedAt,
            nowMs = locatedAt + hour,
            locate = { locateCalls++; null },
            onLocated = { fail("should not persist") },
        )
        assertEquals(31.23f to 121.47f, cached)
        assertEquals(3, locateCalls)
    }
}
