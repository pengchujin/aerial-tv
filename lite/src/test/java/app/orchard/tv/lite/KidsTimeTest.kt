package app.orchard.tv.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** The full app's KidsTimeTest, on Lite's Calendar version of the same rules. */
class KidsTimeTest {
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        Calendar.getInstance(zone).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis
    private val min = 60_000L

    // 2026-11-02 is a Monday, 2026-11-07 a Saturday.
    private val monday = at(2026, 11, 2, 16)
    private val saturday = at(2026, 11, 7, 16)

    @Test
    fun limitsByDay() {
        val rules = KidsRules(weekdayMinutes = 60, weekendMinutes = 120)
        assertNull(KidsTime.lock(rules, KidsUsage(usedMs = 59 * min), monday, zone))
        assertEquals(KidsLock.DayUsed, KidsTime.lock(rules, KidsUsage(usedMs = 60 * min), monday, zone))
        assertNull(KidsTime.lock(rules, KidsUsage(usedMs = 90 * min), saturday, zone))
        assertEquals(10 * min, KidsTime.untilLockMs(rules, KidsUsage(usedMs = 50 * min), monday, zone))
    }

    @Test
    fun lateHours() {
        val rules = KidsRules(endMinute = 21 * 60)
        assertNull(KidsTime.lock(rules, KidsUsage(), at(2026, 11, 2, 20, 59), zone))
        assertEquals(KidsLock.Late, KidsTime.lock(rules, KidsUsage(), at(2026, 11, 2, 21), zone))
        assertEquals(KidsLock.Late, KidsTime.lock(rules, KidsUsage(), at(2026, 11, 3, 5, 30), zone))
        assertEquals(30 * min, KidsTime.untilLockMs(rules, KidsUsage(), at(2026, 11, 2, 20, 30), zone))
    }

    @Test
    fun breaksAndAllowance() {
        val rules = KidsRules(breakEvery = 30)
        val u = KidsTime.advance(rules, KidsUsage(continuousMs = 29 * min), min, 0, monday)
        assertEquals(monday + KidsTime.BREAK_MINUTES * min, u.breakUntil)
        assertTrue(KidsTime.lock(rules, u, monday + min, zone) is KidsLock.Break)
        val allowed = KidsUsage(usedMs = 60 * min, allowUntil = monday + 15 * min)
        assertNull(KidsTime.lock(KidsRules(weekdayMinutes = 60), allowed, monday, zone))
        assertEquals(15 * min, KidsTime.untilLockMs(KidsRules(weekdayMinutes = 60), allowed, monday, zone))
        assertEquals("2026-11-02", KidsTime.dayOf(monday, zone))
    }
}
