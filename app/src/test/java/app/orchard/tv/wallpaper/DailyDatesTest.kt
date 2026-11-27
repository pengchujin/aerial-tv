package app.orchard.tv.wallpaper

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDatesTest {
    private fun china(time: String): Long =
        SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.parse(time)!!.time

    private fun entry(date: String, title: String = "Title $date", caption: String = "Caption $date", start: String = "${date}1600") =
        DailyEntry(date, title, caption, start)

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

    @Test
    fun pinnedDailyDateOnlyMatchesSpecificDailyIds() {
        assertEquals("20261110", pinnedDailyDate("daily:20261110"))
        assertNull(pinnedDailyDate("daily:latest"))
        assertNull(pinnedDailyDate("daily:"))
        assertNull(pinnedDailyDate("grad:aurora"))
        assertNull(pinnedDailyDate("auto"))
        assertNull(pinnedDailyDate("aerial:live"))
    }

    @Test
    fun twoRoundRefreshPreservesPinnedDailyPhotoAndDeletesOtherExpiredFiles() {
        // Round 1: Bing returns 8 days (Nov 10 down to Nov 03).
        val round1Fetched = (10 downTo 3).map { d -> entry("202611%02d".format(d)) }
        val round1Files = round1Fetched.map { "${it.date}.jpg" }.toMutableSet()
        val round1 = mergeDailyIndex(round1Fetched, emptyList(), "daily:latest", round1Files)
        assertEquals(round1Fetched, round1.entries)
        assertNull(round1.preservedFile)

        // Owner manually pins the oldest photo in round 1 (Nov 03).
        val selected = "daily:20261103"

        // Round 2: Days pass; Bing now returns Nov 15 down to Nov 08 (Nov 03..07 rolled out).
        val round2Fetched = (15 downTo 8).map { d -> entry("202611%02d".format(d)) }
        round1Files += round2Fetched.map { "${it.date}.jpg" }
        val round2 = mergeDailyIndex(round2Fetched, round1.entries, selected, round1Files)

        // Nov 03 is appended after the 8 new photos; newest stays first for daily:latest.
        assertEquals(round2Fetched + entry("20261103"), round2.entries)
        assertEquals("20261115", round2.entries.first().date)
        assertEquals("20261103.jpg", round2.preservedFile)
        assertTrue("20261103.jpg" in round2.keepFiles)

        // Simulate cleanup: other expired files (Nov 04..07) are deleted, pinned Nov 03 stays.
        val deleted = round1Files.filter { it !in round2.keepFiles }.toSet()
        assertEquals(setOf("20261104.jpg", "20261105.jpg", "20261106.jpg", "20261107.jpg"), deleted)
        round1Files.removeAll(deleted)
        assertTrue("20261103.jpg" in round1Files)

        // Round 3: Another day passes; pinned Nov 03 continues to survive.
        val round3Fetched = (16 downTo 9).map { d -> entry("202611%02d".format(d)) }
        round1Files += round3Fetched.map { "${it.date}.jpg" }
        val round3 = mergeDailyIndex(round3Fetched, round2.entries, selected, round1Files)
        assertEquals(round3Fetched + entry("20261103"), round3.entries)
        assertEquals("20261103.jpg", round3.preservedFile)
        assertFalse("20261108.jpg" in round3.keepFiles)
        assertTrue("20261103.jpg" in round3.keepFiles)
    }

    @Test
    fun latestDailyDoesNotKeepExpiredEntries() {
        val existing = listOf(entry("20261110"), entry("20261103"))
        val fetched = listOf(entry("20261119"), entry("20261118"))
        val files = setOf("20261119.jpg", "20261118.jpg", "20261110.jpg", "20261103.jpg")

        val plan = mergeDailyIndex(fetched, existing, "daily:latest", files)

        assertEquals(fetched, plan.entries)
        assertEquals(setOf("20261119.jpg", "20261118.jpg"), plan.keepFiles)
        assertNull(plan.preservedFile)
    }

    @Test
    fun pinnedDateMissingFileOnDiskIsNotKept() {
        val existing = listOf(entry("20261103"))
        val fetched = listOf(entry("20261119"), entry("20261118"))
        val files = setOf("20261119.jpg", "20261118.jpg")

        val plan = mergeDailyIndex(fetched, existing, "daily:20261103", files)

        assertEquals(fetched, plan.entries)
        assertEquals(setOf("20261119.jpg", "20261118.jpg"), plan.keepFiles)
        assertNull(plan.preservedFile)
    }

    @Test
    fun pinnedDateStillInNewBingListIsNotDuplicated() {
        val existing = listOf(entry("20261118", title = "Old Title"))
        val fetched = listOf(entry("20261119"), entry("20261118", title = "Fresh Title"))
        val files = setOf("20261119.jpg", "20261118.jpg")

        val plan = mergeDailyIndex(fetched, existing, "daily:20261118", files)

        assertEquals(fetched, plan.entries)
        assertEquals(2, plan.entries.size)
        assertEquals("Fresh Title", plan.entries[1].title)
        assertEquals(setOf("20261119.jpg", "20261118.jpg"), plan.keepFiles)
        assertNull(plan.preservedFile)
    }

    @Test
    fun dailyIsCurrentStaysAccurateWhenOlderPinnedEntryIsAppended() {
        val existing = listOf(entry("20261103", start = "202611031600"))
        val fetched = listOf(entry("20261119", start = "202611191600"), entry("20261118", start = "202611181600"))
        val plan = mergeDailyIndex(fetched, existing, "daily:20261103", setOf("20261119.jpg", "20261118.jpg", "20261103.jpg"))

        val mergedStarts = plan.entries.map { it.start }
        assertTrue(dailyIsCurrent(mergedStarts, china("202611200001")))
        assertTrue(dailyIsCurrent(mergedStarts, china("202611202359")))
        assertFalse(dailyIsCurrent(mergedStarts, china("202611210000")))

        // A pinned entry migrated from an older index without `start` also does not break freshness.
        val legacyPinned = listOf(entry("20261103", start = ""))
        val legacyPlan = mergeDailyIndex(fetched, legacyPinned, "daily:20261103", setOf("20261119.jpg", "20261118.jpg", "20261103.jpg"))
        assertTrue(dailyIsCurrent(legacyPlan.entries.map { it.start }, china("202611201200")))
        assertFalse(dailyIsCurrent(legacyPlan.entries.map { it.start }, china("202611210000")))
    }

    @Test
    fun switchingSelectionAwayAllowsCleanupOnNextRefresh() {
        val pinned = entry("20261103")
        val round1Fetched = listOf(entry("20261118"), entry("20261117"))
        val files = mutableSetOf("20261118.jpg", "20261117.jpg", "20261103.jpg")

        // While pinned, Nov 03 is preserved.
        val whilePinned = mergeDailyIndex(round1Fetched, listOf(pinned), "daily:20261103", files)
        assertTrue("20261103.jpg" in whilePinned.keepFiles)

        // Owner switches away to daily:latest -> next refresh cleans up Nov 03.
        val round2Fetched = listOf(entry("20261119"), entry("20261118"))
        files += "20261119.jpg"
        val afterLatest = mergeDailyIndex(round2Fetched, whilePinned.entries, "daily:latest", files)
        assertEquals(round2Fetched, afterLatest.entries)
        assertFalse("20261103.jpg" in afterLatest.keepFiles)
        assertNull(afterLatest.preservedFile)

        // Switching to a built-in wallpaper also cleans up Nov 03 on the next refresh.
        val afterBuiltin = mergeDailyIndex(round2Fetched, whilePinned.entries, "grad:aurora", files)
        assertEquals(round2Fetched, afterBuiltin.entries)
        assertFalse("20261103.jpg" in afterBuiltin.keepFiles)
        assertNull(afterBuiltin.preservedFile)
    }

    @Test
    fun marketSwitchSparesOnlyPinnedPhotoOutsideNewWindow() {
        val existing = listOf(entry("20261118", title = "ZH 18"), entry("20261103", title = "ZH 03"))
        val enFetched = listOf(entry("20261119", title = "EN 19"), entry("20261118", title = "EN 18"))
        val existingFiles = setOf("20261118.jpg", "20261103.jpg")

        // Pinned outside new window: preserved across market switch.
        val outside = mergeDailyIndex(enFetched, existing, "daily:20261103", existingFiles)
        assertEquals("20261103.jpg", outside.preservedFile)

        // Pinned inside new window: re-downloaded for the new market so photo matches caption.
        val inside = mergeDailyIndex(enFetched, existing, "daily:20261118", existingFiles)
        assertNull(inside.preservedFile)
    }
}
