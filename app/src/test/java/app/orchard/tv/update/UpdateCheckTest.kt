package app.orchard.tv.update

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.IOException
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateCheckTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var wall = 1_790_000_000_000L
    private var checkedAt = 0L
    private var known = "1.69"
    private var calls = 0
    private var response: suspend () -> String = { "v1.70\n" }
    private val logs = mutableListOf<String>()
    private val checker = UpdateCheck(
        scope = scope,
        lastSuccess = { checkedAt },
        read = { calls++; response() },
        save = { version, at -> known = version; checkedAt = at },
        log = { logs += it },
        wallTime = { wall },
    )

    @After
    fun stop() {
        checker.setActive(false)
        scope.cancel()
    }

    @Test
    fun successfulCheckKeepsVersionAndCompletionTime() {
        checker.check()
        assertEquals("1.70", known)
        assertEquals(wall, checkedAt)
        assertTrue(checker.diagnostics.contains("success version=1.70"))
        assertTrue(checker.diagnostics.contains("retry=none"))
    }

    @Test
    fun freshSuccessSkipsHomeAndNetworkChecks() {
        checker.check()
        checker.check()
        checker.check(networkCameUp = true)
        assertEquals(1, calls)
        assertTrue(logs.last().contains("skipped: fresh"))
    }

    @Test
    fun panelRefreshesAfterTenMinutesWithoutChangingHomeLimit() {
        checker.check()
        wall += UpdateCheck.PANEL_MAX_AGE_MS - 1
        checker.check(maxAgeMs = UpdateCheck.PANEL_MAX_AGE_MS)
        assertEquals(1, calls)
        wall++
        checker.check()
        assertEquals(1, calls)
        checker.check(maxAgeMs = UpdateCheck.PANEL_MAX_AGE_MS)
        assertEquals(2, calls)
    }

    @Test
    fun homeChecksAgainWhenThreeHourLimitExpires() {
        checker.check()
        wall += UpdateCheck.MAX_AGE_MS
        checker.check()
        assertEquals(2, calls)
    }

    @Test
    fun inFlightRequestsAreDeduplicatedEvenWhenForced() {
        val pending = CompletableDeferred<String>()
        response = { pending.await() }
        checker.check()
        checker.check(networkCameUp = true)
        checker.check(maxAgeMs = UpdateCheck.PANEL_MAX_AGE_MS)
        checker.check(force = true)
        assertEquals(1, calls)
        assertTrue(logs.last().contains("in flight"))
        wall += 1000
        pending.complete("1.70")
        assertEquals(wall, checkedAt)
        assertFalse(checker.diagnostics.contains("inFlight=true"))
    }

    @Test
    @Config(sdk = [23, 26])
    fun failedCheckRetriesWithoutAnotherUserOrNetworkEvent() {
        checker.setActive(true)
        fail()
        checker.check()
        assertEquals(0L, checkedAt)
        assertEquals("1.69", known)
        response = { "1.70" }
        advance(29_999)
        assertEquals(1, calls)
        advance(1)
        assertEquals(2, calls)
        assertEquals("1.70", known)
        advance(3_600_000)
        assertEquals(2, calls)
    }

    @Test
    fun retriesBackOffAndCapAtThirtyMinutes() {
        checker.setActive(true)
        fail()
        checker.check()
        for (delay in listOf(30_000L, 120_000L, 600_000L, 1_800_000L, 1_800_000L)) {
            val before = calls
            advance(delay - 1)
            assertEquals(before, calls)
            advance(1)
            assertEquals(before + 1, calls)
        }
    }

    @Test
    fun repeatedPanelOpensDoNotBypassFailureBackoff() {
        checker.setActive(true)
        fail()
        checker.check()
        repeat(5) { checker.check(maxAgeMs = UpdateCheck.PANEL_MAX_AGE_MS) }
        assertEquals(1, calls)
        advance(30_000)
        assertEquals(2, calls)
    }

    @Test
    fun networkRecoveryAdvancesRetryAndCoalescesRepeatedCallbacks() {
        checker.setActive(true)
        fail()
        checker.check()
        response = { "1.70" }
        advance(10_000)
        checker.check(networkCameUp = true)
        advance(1000)
        checker.check(networkCameUp = true)
        advance(999)
        assertEquals(1, calls)
        advance(1)
        assertEquals(2, calls)
        advance(30_000)
        assertEquals(2, calls)
    }

    @Test
    fun networkFlappingCannotStartRequestsLessThanFiveSecondsApart() {
        checker.setActive(true)
        fail()
        checker.check()
        repeat(4) {
            checker.check(networkCameUp = true)
            advance(1000)
        }
        assertEquals(1, calls)
        advance(1000)
        assertEquals(2, calls)
        checker.check(networkCameUp = true)
        advance(4999)
        assertEquals(2, calls)
        advance(1)
        assertEquals(3, calls)
    }

    @Test
    fun networkCallbackDuringRequestDoesNotCreateASecondRequest() {
        checker.setActive(true)
        val pending = CompletableDeferred<String>()
        response = { pending.await() }
        checker.check()
        checker.check(networkCameUp = true)
        pending.completeExceptionally(IOException("offline"))
        response = { "1.70" }
        advance(30_000)
        assertEquals(2, calls)
    }

    @Test
    fun retriesPauseInBackgroundAndResumeWhenDue() {
        checker.setActive(true)
        fail()
        checker.check()
        checker.setActive(false)
        advance(60_000)
        assertEquals(1, calls)
        response = { "1.70" }
        checker.setActive(true)
        advance(0)
        assertEquals(2, calls)
    }

    @Test
    fun backgroundCompletionDoesNotScheduleRetryUntilResume() {
        checker.setActive(true)
        val pending = CompletableDeferred<String>()
        response = { pending.await() }
        checker.check()
        checker.setActive(false)
        pending.completeExceptionally(IOException("offline"))
        advance(60_000)
        assertEquals(1, calls)
        response = { "1.70" }
        checker.setActive(true)
        advance(0)
        assertEquals(2, calls)
    }

    @Test
    fun resumeBeforeDeadlineKeepsOriginalRetryTime() {
        checker.setActive(true)
        fail()
        checker.check()
        checker.setActive(false)
        advance(10_000)
        checker.setActive(true)
        advance(19_999)
        assertEquals(1, calls)
        advance(1)
        assertEquals(2, calls)
    }

    @Test
    fun forcedCheckBypassesBackoffAndCancelsOldRetry() {
        checker.setActive(true)
        fail()
        checker.check()
        response = { "1.70" }
        checker.check(force = true)
        assertEquals(2, calls)
        advance(60_000)
        assertEquals(2, calls)
    }

    @Test
    fun failedForcedCheckPreservesKnownVersionAndSuccessTime() {
        checker.setActive(true)
        checker.check()
        val success = checkedAt
        advance(1000)
        fail()
        checker.check(force = true)
        assertEquals(success, checkedAt)
        assertEquals("1.70", known)
        advance(30_000)
        assertEquals(3, calls)
    }

    @Test
    fun invalidResponseCountsAsFailureAndDoesNotReplaceKnownVersion() {
        checker.setActive(true)
        response = { "<html>Unavailable</html>" }
        checker.check()
        assertEquals(0L, checkedAt)
        assertEquals("1.69", known)
        assertTrue(checker.diagnostics.contains("failed invalid version response"))
        assertFalse(logs.any { it.contains("<html>") })
        advance(30_000)
        assertEquals(2, calls)
    }

    @Test
    fun clockRollbackDoesNotKeepCachedSuccessFreshForever() {
        checkedAt = wall + 60_000
        checker.check()
        assertEquals(1, calls)
    }

    @Test
    fun retryDelayUsesMonotonicTimeDespiteWallClockChanges() {
        checker.setActive(true)
        fail()
        checker.check()
        wall -= 86_400_000
        advance(30_000)
        assertEquals(2, calls)
        wall += 172_800_000
        advance(119_999)
        assertEquals(2, calls)
        advance(1)
        assertEquals(3, calls)
    }

    @Test
    fun diagnosticLogsKeepExceptionTypesButNotNetworkAddresses() {
        response = { throw IOException("https://private.example/token", IllegalStateException("secret")) }
        checker.check(source = "network")
        assertTrue(logs.any { it.contains("network started") })
        assertTrue(checker.diagnostics.contains("IOException/IllegalStateException"))
        assertFalse(logs.any { it.contains("private.example") || it.contains("secret") })
    }

    @Test
    fun successResetsBackoffForTheNextFailure() {
        checker.setActive(true)
        fail()
        checker.check()
        advance(30_000)
        response = { "1.70" }
        advance(120_000)
        assertEquals(3, calls)
        fail()
        checker.check(force = true)
        advance(29_999)
        assertEquals(4, calls)
        advance(1)
        assertEquals(5, calls)
    }

    @Test
    fun networkRecoveryInBackgroundWaitsForHomeToResume() {
        checker.setActive(true)
        fail()
        checker.check()
        checker.setActive(false)
        advance(10_000)
        checker.check(networkCameUp = true)
        advance(10_000)
        assertEquals(1, calls)
        response = { "1.70" }
        checker.setActive(true)
        advance(0)
        assertEquals(2, calls)
    }

    private fun fail() { response = { throw IOException("offline") } }

    private fun advance(ms: Long) {
        wall += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }
}
