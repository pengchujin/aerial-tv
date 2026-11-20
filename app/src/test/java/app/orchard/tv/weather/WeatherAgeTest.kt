package app.orchard.tv.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherAgeTest {
    private val hour = 60 * 60 * 1000L
    private val reading = WeatherNow(12, WeatherKind.Clear, day = true, readAt = 1_000_000_000L)

    @Test
    fun aFreshReadingShowsNoAge() {
        assertNull(Weather.hoursOld(reading, reading.readAt + 2 * hour + 59 * 60_000L))
    }

    @Test
    fun anOldReadingShowsWholeHours() {
        assertEquals(3, Weather.hoursOld(reading, reading.readAt + 3 * hour))
        assertEquals(11, Weather.hoursOld(reading, reading.readAt + 11 * hour + 59 * 60_000L))
    }
}
