package app.orchard.tv.ui.home

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The table was checked against macOS's Chinese calendar for every day from 1901 to 2100; it agreed
 * on all but 2057-09-28 and 2097-08-07, where macOS gives day 0 (the new moon falls about midnight).
 * These are a few of those days, kept so the table and its reading stay right.
 */
class LunarTest {
    private fun text(y: Int, m: Int, d: Int) = Lunar.text(LocalDate.of(y, m, d))

    @Test fun newYear() {
        assertEquals("正月初一", text(2026, 2, 17))
        assertEquals("腊月廿九", text(2026, 2, 16))
        assertEquals("正月初一", text(1901, 2, 19))
    }

    @Test fun ordinaryDays() {
        assertEquals("九月十六", text(2026, 10, 25))
        assertEquals("腊月初一", text(2100, 12, 31))
    }

    @Test fun leapMonths() {
        assertEquals("闰二月初一", text(2023, 3, 22))
        assertEquals("三月初一", text(2023, 4, 20))
        assertEquals("闰六月初一", text(2025, 7, 25))
        assertEquals("闰冬月初一", text(2033, 12, 22))
    }

    @Test fun outsideTheTable() {
        assertNull(text(1901, 2, 18))
        assertNull(text(2101, 3, 1))
    }
}
