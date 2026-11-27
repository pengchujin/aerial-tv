package app.orchard.tv.guard

import app.orchard.tv.guard.UsageFront.Event
import app.orchard.tv.guard.UsageFront.MOVE_TO_BACKGROUND
import app.orchard.tv.guard.UsageFront.MOVE_TO_FOREGROUND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostHomeUsageTest {
    private val ownPackage = "app.orchard.tv"
    private val stockPackage = "com.mitv.tvhome"
    private val stockClass = "com.mitv.tvhome.MainActivity"
    private val appPackage = "com.example.player"
    private val appClass = "com.example.player.VideoActivity"
    private val toleranceMs = HomeGuardService.HOME_USAGE_TOLERANCE_MS

    @Test fun stalePrePressStockLauncherEventIsIgnored() {
        val homePressedAt = 100_000L
        val events = listOf(
            Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, homePressedAt - 60_000L),
            Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, homePressedAt - toleranceMs - 1L),
        )
        val front = UsageFront.selectPostHomeFront(
            events = events,
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 1_500L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertNull(front)
    }

    @Test fun freshPostPressStockLauncherEventIsReturned() {
        val homePressedAt = 100_000L
        val stockAt = homePressedAt + 120L
        val events = listOf(
            Event(MOVE_TO_FOREGROUND, appPackage, appClass, homePressedAt - 30_000L),
            Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, stockAt),
        )
        val front = UsageFront.selectPostHomeFront(
            events = events,
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 200L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertEquals(stockPackage, front?.pkg)
        assertEquals(stockClass, front?.cls)
        assertEquals(stockAt, front?.at)
    }

    @Test fun stockLauncherEventWithinBroadcastToleranceIsReturned() {
        // ACTION_CLOSE_SYSTEM_DIALOGS ("homekey") can arrive hundreds of ms after the stock
        // launcher already moved to the front.
        val homePressedAt = 100_000L
        val atLowerBound = homePressedAt - toleranceMs
        val frontAtBound = UsageFront.selectPostHomeFront(
            events = listOf(Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, atLowerBound)),
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 150L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertEquals(stockPackage, frontAtBound?.pkg)
        assertEquals(atLowerBound, frontAtBound?.at)

        val withinTolerance = homePressedAt - 350L
        val frontWithin = UsageFront.selectPostHomeFront(
            events = listOf(Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, withinTolerance)),
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 150L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertEquals(stockPackage, frontWithin?.pkg)
        assertEquals(withinTolerance, frontWithin?.at)
    }

    @Test fun postPressRegularAppNewerThanStockLauncherWins() {
        val homePressedAt = 100_000L
        val events = listOf(
            Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, homePressedAt + 50L),
            Event(MOVE_TO_FOREGROUND, appPackage, appClass, homePressedAt + 120L),
        )
        val front = UsageFront.selectPostHomeFront(
            events = events,
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 300L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertEquals(appPackage, front?.pkg)
        assertEquals(appClass, front?.cls)
        assertEquals(homePressedAt + 120L, front?.at)
    }

    @Test fun outOfOrderEventsStillPickNewestRegularAppOverStockLauncher() {
        val homePressedAt = 100_000L
        val events = listOf(
            Event(MOVE_TO_FOREGROUND, appPackage, appClass, homePressedAt + 200L),
            Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, homePressedAt + 50L),
        )
        val front = UsageFront.selectPostHomeFront(
            events = events,
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 300L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertEquals(appPackage, front?.pkg)
    }

    @Test fun ownPackageMovingToFrontAfterStockLauncherPreventsTakeover() {
        val homePressedAt = 100_000L
        val events = listOf(
            Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, homePressedAt + 40L),
            Event(MOVE_TO_FOREGROUND, ownPackage, "app.orchard.tv.MainActivity", homePressedAt + 100L),
        )
        val front = UsageFront.selectPostHomeFront(
            events = events,
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 200L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertNull(front)
    }

    @Test fun closedPostPressStockLauncherIsIgnoredAfterSettle() {
        val homePressedAt = 100_000L
        val events = listOf(
            Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, homePressedAt + 50L),
            Event(MOVE_TO_BACKGROUND, stockPackage, stockClass, homePressedAt + 100L),
        )
        val front = UsageFront.selectPostHomeFront(
            events = events,
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 1_500L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
            activitySettleMs = 1_000L,
        )
        assertNull(front)
    }

    @Test fun supersededCallbackGenerationIsIgnored() {
        assertTrue(UsageFront.isCurrentGeneration(expected = 1L, current = 1L))
        assertTrue(UsageFront.isCurrentGeneration(expected = 42L, current = 42L))
        assertFalse(UsageFront.isCurrentGeneration(expected = 1L, current = 2L))
        assertFalse(UsageFront.isCurrentGeneration(expected = 2L, current = 1L))
        assertFalse(UsageFront.isCurrentGeneration(expected = 0L, current = 0L))
        assertFalse(UsageFront.isCurrentGeneration(expected = -1L, current = -1L))
    }

    @Test fun backwardClockJumpPastToleranceReturnsNullCleanly() {
        val homePressedAt = 100_000L
        val jumpedNow = homePressedAt - toleranceMs - 1L
        assertNull(UsageFront.postHomeMinAt(homePressedAt, jumpedNow, toleranceMs))
        val front = UsageFront.selectPostHomeFront(
            events = listOf(
                Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, jumpedNow),
                Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, homePressedAt),
            ),
            homePressedWallAt = homePressedAt,
            now = jumpedNow,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertNull(front)
    }

    @Test fun smallBackwardClockSlewWithinToleranceStillSucceeds() {
        val homePressedAt = 100_000L
        val slewedNow = homePressedAt - 200L
        assertEquals(homePressedAt - toleranceMs, UsageFront.postHomeMinAt(homePressedAt, slewedNow, toleranceMs))
        val front = UsageFront.selectPostHomeFront(
            events = listOf(Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, slewedNow)),
            homePressedWallAt = homePressedAt,
            now = slewedNow,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertEquals(stockPackage, front?.pkg)
    }

    @Test fun invalidClockAnchorsAndNearEpochHandledWithoutOverflow() {
        assertNull(UsageFront.postHomeMinAt(Long.MIN_VALUE, 10_000L, toleranceMs))
        assertNull(UsageFront.postHomeMinAt(-1L, 10_000L, toleranceMs))
        assertNull(UsageFront.postHomeMinAt(10_000L, -1L, toleranceMs))
        assertNull(UsageFront.postHomeMinAt(10_000L, 10_500L, -1L))
        assertEquals(0L, UsageFront.postHomeMinAt(200L, 500L, toleranceMs))
    }

    @Test fun emptyEventListReturnsNull() {
        val homePressedAt = 100_000L
        val front = UsageFront.selectPostHomeFront(
            events = emptyList(),
            homePressedWallAt = homePressedAt,
            now = homePressedAt + 1_500L,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertNull(front)
    }

    @Test fun futureDatedStockEventPastSlackIsIgnored() {
        val homePressedAt = 100_000L
        val now = homePressedAt + 200L
        val events = listOf(
            Event(MOVE_TO_FOREGROUND, stockPackage, stockClass, now + UsageFront.FUTURE_SLACK_MS + 1_000L),
        )
        val front = UsageFront.selectPostHomeFront(
            events = events,
            homePressedWallAt = homePressedAt,
            now = now,
            toleranceMs = toleranceMs,
            ownPackage = ownPackage,
        )
        assertNull(front)
    }
}
