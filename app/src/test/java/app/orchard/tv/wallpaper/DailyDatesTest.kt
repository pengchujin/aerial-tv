package app.orchard.tv.wallpaper

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDatesTest {
    private fun china(time: String): Long =
        SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.parse(time)!!.time

    // cn.bing.com on 2026-11-20 (China): the newest photo is dated the 19th, starting 202611191600 UTC.
    private val starts = listOf("202611191600", "202611181600")

    @Test
    fun theNewestPhotoIsCurrentAllDayInChina() {
        assertTrue(dailyIsCurrent(starts, china("202611200001")))
        assertTrue(dailyIsCurrent(starts, china("202611201711")))
        assertTrue(dailyIsCurrent(starts, china("202611202359")))
    }

    @Test
    fun theNextIsDueFromMidnightInChina() {
        assertFalse(dailyIsCurrent(starts, china("202611210000")))
        assertFalse(dailyIsCurrent(starts, china("202611210019")))
    }

    @Test
    fun anIndexWithoutStartsIsFetchedAgain() {
        assertFalse(dailyIsCurrent(listOf("", ""), china("202611201200")))
        assertFalse(dailyIsCurrent(emptyList(), china("202611201200")))
        assertFalse(dailyIsCurrent(listOf("2026111916"), china("202611201200")))
    }
}
