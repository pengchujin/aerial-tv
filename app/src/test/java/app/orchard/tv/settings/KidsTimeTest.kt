package app.orchard.tv.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class KidsTimeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
    private val min = 60_000L

    // 2026-11-02 is a Monday, 2026-11-07 a Saturday.
    private val monday = at("2026-11-02T16:00")
    private val saturday = at("2026-11-07T16:00")

    @Test
    fun noRulesNeverLock() {
        assertNull(KidsTime.lock(RestrictedRules(), KidsUsage(usedMs = 600 * min), monday, zone))
        assertNull(KidsTime.untilLockMs(RestrictedRules(), KidsUsage(usedMs = 600 * min), monday, zone))
    }

    @Test
    fun weekdayAndWeekendLimits() {
        val rules = RestrictedRules(weekdayMinutes = 60, weekendMinutes = 120)
        assertNull(KidsTime.lock(rules, KidsUsage(usedMs = 59 * min), monday, zone))
        assertEquals(KidsLock.DayUsed, KidsTime.lock(rules, KidsUsage(usedMs = 60 * min), monday, zone))
        assertNull(KidsTime.lock(rules, KidsUsage(usedMs = 90 * min), saturday, zone))
        assertEquals(10 * min, KidsTime.untilLockMs(rules, KidsUsage(usedMs = 50 * min), monday, zone))
    }

    @Test
    fun lateHours() {
        val rules = RestrictedRules(endMinute = 21 * 60)
        assertNull(KidsTime.lock(rules, KidsUsage(), at("2026-11-02T20:59"), zone))
        assertEquals(KidsLock.Late, KidsTime.lock(rules, KidsUsage(), at("2026-11-02T21:00"), zone))
        assertEquals(KidsLock.Late, KidsTime.lock(rules, KidsUsage(), at("2026-11-03T05:30"), zone))
        assertNull(KidsTime.lock(rules, KidsUsage(), at("2026-11-03T06:00"), zone))
        assertEquals(30 * min, KidsTime.untilLockMs(rules, KidsUsage(), at("2026-11-02T20:30"), zone))
    }

    @Test
    fun breaksStartAndEnd() {
        val rules = RestrictedRules(breakEvery = 30)
        var u = KidsTime.advance(rules, KidsUsage(continuousMs = 29 * min), watchedMs = min, idleMs = 0, now = monday)
        assertEquals(0L, u.continuousMs)
        assertEquals(monday + KidsTime.BREAK_MINUTES * min, u.breakUntil)
        assertEquals(KidsLock.Break(u.breakUntil), KidsTime.lock(rules, u, monday + min, zone))
        assertNull(KidsTime.lock(rules, u, u.breakUntil, zone))
        // An idle stretch as long as a break counts as one.
        u = KidsTime.advance(rules, KidsUsage(continuousMs = 20 * min), watchedMs = 0, idleMs = KidsTime.BREAK_MINUTES * min, now = monday)
        assertEquals(0L, u.continuousMs)
        assertEquals(10 * min, KidsTime.untilLockMs(rules, KidsUsage(continuousMs = 20 * min), monday, zone))
    }

    @Test
    fun windowToTheMinute() {
        // 7:30 to 20:45, as the phone page can set it.
        val rules = RestrictedRules(startMinute = 7 * 60 + 30, endMinute = 20 * 60 + 45)
        assertEquals(KidsLock.Late, KidsTime.lock(rules, KidsUsage(), at("2026-11-02T07:29"), zone))
        assertNull(KidsTime.lock(rules, KidsUsage(), at("2026-11-02T07:30"), zone))
        assertNull(KidsTime.lock(rules, KidsUsage(), at("2026-11-02T20:44"), zone))
        assertEquals(KidsLock.Late, KidsTime.lock(rules, KidsUsage(), at("2026-11-02T20:45"), zone))
        assertEquals(15 * min, KidsTime.untilLockMs(rules, KidsUsage(), at("2026-11-02T20:30"), zone))
    }

    @Test
    fun breakLengthAndValidity() {
        val rules = RestrictedRules(breakEvery = 40, breakMinutes = 10)
        val u = KidsTime.advance(rules, KidsUsage(continuousMs = 39 * min), min, 0, monday)
        assertEquals(monday + 10 * min, u.breakUntil)
        // Nine idle minutes are no ten-minute break.
        assertEquals(30 * min, KidsTime.advance(rules, KidsUsage(continuousMs = 30 * min), 0, 9 * min, monday).continuousMs)
        assertEquals(false, RestrictedRules(startMinute = 20 * 60, endMinute = 8 * 60).valid)
        assertEquals(true, RestrictedRules(weekdayMinutes = 75, startMinute = 7 * 60, endMinute = 21 * 60 + 15).valid)
    }

    @Test
    fun parentAllowanceHoldsThenLocks() {
        val rules = RestrictedRules(weekdayMinutes = 60)
        val u = KidsUsage(usedMs = 60 * min, allowUntil = monday + 15 * min)
        assertNull(KidsTime.lock(rules, u, monday, zone))
        assertEquals(15 * min, KidsTime.untilLockMs(rules, u, monday, zone))
        assertEquals(KidsLock.DayUsed, KidsTime.lock(rules, u, monday + 15 * min, zone))
        // No break starts during an allowance.
        val b = KidsTime.advance(RestrictedRules(breakEvery = 30), KidsUsage(continuousMs = 40 * min, allowUntil = monday + min), min, 0, monday)
        assertEquals(0L, b.breakUntil)
    }
}
