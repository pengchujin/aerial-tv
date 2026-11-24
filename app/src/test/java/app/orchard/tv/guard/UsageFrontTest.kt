package app.orchard.tv.guard

import app.orchard.tv.guard.UsageFront.MOVE_TO_BACKGROUND
import app.orchard.tv.guard.UsageFront.MOVE_TO_FOREGROUND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageFrontTest {
    private fun pick(vararg e: Pair<Int, Pair<String, Long>>): UsageFront.Front? {
        val p = UsageFront.Picker()
        e.forEach { (type, rest) -> p.add(type, rest.first.substringBefore('/'), rest.first.substringAfter('/'), rest.second) }
        return p.result()
    }

    @Test fun appBackedOutOfWithNothingAfterIsClosed() {
        // Issue #1023: the file manager moved to the front, then to the back, and nothing came up.
        val f = pick(MOVE_TO_FOREGROUND to ("com.stv.filemanager/.Main" to 100L), MOVE_TO_BACKGROUND to ("com.stv.filemanager/.Main" to 200L))
        assertEquals("com.stv.filemanager", f!!.pkg)
        assertTrue(f.closed)
    }

    @Test fun nextAppComingUpIsInFront() {
        val f = pick(
            MOVE_TO_FOREGROUND to ("a/.A" to 100L),
            MOVE_TO_BACKGROUND to ("a/.A" to 200L),
            MOVE_TO_FOREGROUND to ("b/.B" to 210L),
        )
        assertEquals("b", f!!.pkg)
        assertFalse(f.closed)
    }

    @Test fun anotherActivityGoingBackDoesNotCloseTheFront() {
        // The previous app pauses after the new one resumed on some firmwares.
        val f = pick(MOVE_TO_FOREGROUND to ("b/.B" to 210L), MOVE_TO_BACKGROUND to ("a/.A" to 220L))
        assertFalse(f!!.closed)
    }

    @Test fun noEventsIsUnknown() {
        assertNull(pick())
    }

    @Test fun activityPauseIsNotAnExitWhileTheNextActivityIsStarting() {
        val p = UsageFront.Picker()
        p.add(MOVE_TO_FOREGROUND, "org.smarttube.stable", ".BrowseActivity", 100L)
        p.add(MOVE_TO_BACKGROUND, "org.smarttube.stable", ".BrowseActivity", 200L)
        assertFalse(p.result(now = 250L, activitySettleMs = 1000L)!!.closed)
    }

    @Test fun settingsResumingKeepsTheSameAppInFront() {
        val p = UsageFront.Picker()
        p.add(MOVE_TO_FOREGROUND, "org.smarttube.stable", ".BrowseActivity", 100L)
        p.add(MOVE_TO_BACKGROUND, "org.smarttube.stable", ".BrowseActivity", 200L)
        p.add(MOVE_TO_FOREGROUND, "org.smarttube.stable", ".AppDialogActivity", 300L)
        val f = p.result(now = 1400L, activitySettleMs = 1000L)!!
        assertEquals(".AppDialogActivity", f.cls)
        assertFalse(f.closed)
    }

    @Test fun genuineExitIsClosedAfterThePauseHasSettled() {
        val p = UsageFront.Picker()
        p.add(MOVE_TO_FOREGROUND, "com.stv.filemanager", ".Main", 100L)
        p.add(MOVE_TO_BACKGROUND, "com.stv.filemanager", ".Main", 200L)
        assertTrue(p.result(now = 1200L, activitySettleMs = 1000L)!!.closed)
    }

    @Test fun futureDatedEventsInBucketAreIgnored() {
        val now = 1_795_327_433_000L // 2026-11-22
        val future = 1_828_841_225_224L // 2027-12-15
        val p = UsageFront.Picker(maxAt = now + UsageFront.FUTURE_SLACK_MS)
        p.add(MOVE_TO_FOREGROUND, "com.dh.myembyapp", ".MainActivity", now - 2_000L)
        p.add(MOVE_TO_FOREGROUND, "com.mitv.tvhome", "com.mitv.tvhome.MainActivityUserMode", now)
        p.add(MOVE_TO_FOREGROUND, "com.stale.app", ".OldActivity", future)
        val f = p.result(now = now, activitySettleMs = 1000L)!!
        assertEquals("com.mitv.tvhome", f.pkg)
        assertEquals("com.mitv.tvhome.MainActivityUserMode", f.cls)
        assertEquals(now, f.at)
        assertFalse(f.closed)
        assertTrue(p.skippedFuture)
    }

    @Test fun eventWithinFutureSlackIsKept() {
        val now = 10_000L
        val p = UsageFront.Picker(maxAt = now + UsageFront.FUTURE_SLACK_MS)
        p.add(MOVE_TO_FOREGROUND, "com.mitv.tvhome", ".MainActivityUserMode", now + 2_000L)
        val f = p.result(now = now)!!
        assertEquals("com.mitv.tvhome", f.pkg)
        assertFalse(p.skippedFuture)
    }

    @Test fun onlyFutureEventsReturnsNull() {
        val now = 10_000L
        val p = UsageFront.Picker(maxAt = now + UsageFront.FUTURE_SLACK_MS)
        p.add(MOVE_TO_FOREGROUND, "com.stale.app", ".Old", now + 86_400_000L)
        assertNull(p.result(now = now))
        assertTrue(p.skippedFuture)
    }

    @Test fun futureBackgroundEventDoesNotCloseCurrentFront() {
        val now = 10_000L
        val p = UsageFront.Picker(maxAt = now + UsageFront.FUTURE_SLACK_MS)
        p.add(MOVE_TO_FOREGROUND, "com.mitv.tvhome", ".MainActivityUserMode", now)
        p.add(MOVE_TO_BACKGROUND, "com.mitv.tvhome", ".MainActivityUserMode", now + 86_400_000L)
        val f = p.result(now = now)!!
        assertFalse(f.closed)
        assertTrue(p.skippedFuture)
    }

    @Test fun olderForegroundEventDoesNotOverwriteNewerOne() {
        val p = UsageFront.Picker()
        p.add(MOVE_TO_FOREGROUND, "com.mitv.tvhome", ".MainActivityUserMode", 500L)
        p.add(MOVE_TO_FOREGROUND, "com.older.app", ".Old", 200L)
        val f = p.result()!!
        assertEquals("com.mitv.tvhome", f.pkg)
        assertEquals(500L, f.at)
    }

    @Test fun queryEventsUsesOpenEndAndFallsBackToNowIfEmpty() {
        val calls = mutableListOf<Pair<Long, Long>>()
        val fromOpen = UsageFront.queryEvents(
            begin = 8_000L,
            now = 10_000L,
            query = { b, e ->
                calls += b to e
                if (e == Long.MAX_VALUE) listOf("event") else emptyList()
            },
            hasNext = { it.isNotEmpty() },
        )
        assertEquals(listOf("event"), fromOpen)
        assertEquals(listOf(8_000L to Long.MAX_VALUE), calls)

        calls.clear()
        val fromFallback = UsageFront.queryEvents(
            begin = -500L,
            now = 10_000L,
            query = { b, e ->
                calls += b to e
                if (e == Long.MAX_VALUE) emptyList() else listOf("fallback")
            },
            hasNext = { it.isNotEmpty() },
        )
        assertEquals(listOf("fallback"), fromFallback)
        assertEquals(listOf(0L to Long.MAX_VALUE, 0L to 10_000L), calls)
    }
}
