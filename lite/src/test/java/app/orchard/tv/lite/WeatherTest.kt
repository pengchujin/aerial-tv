package app.orchard.tv.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class WeatherTest {
    private val min = 60 * 1000L
    private val hour = 60 * min
    private val day = 24 * hour

    @Test
    fun backwardClockJumpMakesRefreshDueImmediately() {
        val lastAttempt = 2_000_000_000L
        val jumpedBack = lastAttempt - 4 * hour
        assertTrue(Weather.isRefreshDue(jumpedBack, lastAttempt, failed = false, failures = 0))
        assertTrue(Weather.isRefreshDue(jumpedBack, lastAttempt, failed = true, failures = 4))
    }

    @Test
    fun normalRefreshAndFailureBackoffIntervalsWork() {
        val lastAttempt = 2_000_000_000L
        assertFalse(Weather.isRefreshDue(lastAttempt + 30 * min, lastAttempt, failed = false, failures = 0))
        assertTrue(Weather.isRefreshDue(lastAttempt + hour, lastAttempt, failed = false, failures = 0))

        assertEquals(3 * min, Weather.retryMs(1))
        assertEquals(6 * min, Weather.retryMs(2))
        assertEquals(12 * min, Weather.retryMs(3))
        assertEquals(24 * min, Weather.retryMs(4))
        assertEquals(48 * min, Weather.retryMs(5))
        assertEquals(60 * min, Weather.retryMs(6))

        assertFalse(Weather.isRefreshDue(lastAttempt + 2 * min, lastAttempt, failed = true, failures = 1))
        assertTrue(Weather.isRefreshDue(lastAttempt + 2 * min, lastAttempt, failed = true, failures = 1, networkUp = true))
        assertTrue(Weather.isRefreshDue(lastAttempt + 3 * min, lastAttempt, failed = true, failures = 1))
        assertFalse(Weather.isRefreshDue(lastAttempt + 5 * min, lastAttempt, failed = true, failures = 2))
        assertTrue(Weather.isRefreshDue(lastAttempt + 6 * min, lastAttempt, failed = true, failures = 2))
    }

    @Test
    fun relocationAndVisibilityHandleBackwardClockJump() {
        val at = 2_000_000_000L
        assertFalse(Weather.needsRelocation(at + day, at))
        assertTrue(Weather.needsRelocation(at + day + 1L, at))
        assertTrue(Weather.needsRelocation(at - 1L, at))

        assertTrue(Weather.isReadingShown(at, at + 11 * hour + 59 * min))
        assertFalse(Weather.isReadingShown(at, at + 12 * hour))
        assertTrue(Weather.isReadingShown(at, at - 60_000L))
        assertFalse(Weather.isReadingShown(at, at - 10 * min))
    }

    @Test
    fun missingCoordinatesFailBeforeOpenMeteo() {
        var locateCalls = 0
        try {
            Weather.resolveCoordinates(
                chosen = false,
                savedLat = Float.NaN,
                savedLon = Float.NaN,
                locatedAtMs = 0L,
                nowMs = 2_000_000_000L,
                locate = { locateCalls++; null },
                onLocated = { fail("should not persist") },
            )
            fail("Expected IOException when no coordinates are available")
        } catch (e: IOException) {
            assertEquals("no location", e.message)
        }
        assertEquals(1, locateCalls)
    }

    @Test
    fun existingCoordinatesSurviveFailedRelocation() {
        val locatedAt = 2_000_000_000L
        var locateCalls = 0
        var persisted: Weather.Guess? = null

        val kept = Weather.resolveCoordinates(
            chosen = false,
            savedLat = 34.41f,
            savedLon = 115.65f,
            locatedAtMs = locatedAt,
            nowMs = locatedAt + day + hour,
            locate = { locateCalls++; null },
            onLocated = { persisted = it },
        )
        assertEquals(34.41f to 115.65f, kept)
        assertEquals(1, locateCalls)
        assertNull(persisted)

        val fresh = Weather.Guess(39.90f, 116.40f, "Beijing", cn = true)
        val updated = Weather.resolveCoordinates(
            chosen = false,
            savedLat = 34.41f,
            savedLon = 115.65f,
            locatedAtMs = locatedAt,
            nowMs = locatedAt + day + hour,
            locate = { locateCalls++; fresh },
            onLocated = { persisted = it },
        )
        assertEquals(39.90f to 116.40f, updated)
        assertEquals(2, locateCalls)
        assertEquals(fresh, persisted)
    }
}
