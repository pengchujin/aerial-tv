package app.orchard.tv.update

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Main-thread update checks; only failed checks get a foreground retry timer (#912). */
internal class UpdateCheck(
    private val scope: CoroutineScope,
    private val lastSuccess: () -> Long,
    private val read: suspend () -> String,
    private val save: (String, Long) -> Unit,
    private val log: (String) -> Unit,
    private val wallTime: () -> Long = System::currentTimeMillis,
    private val elapsedTime: () -> Long = SystemClock::elapsedRealtime,
) {
    companion object {
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
        const val PANEL_MAX_AGE_MS = 10 * 60 * 1000L
        private val RETRY_DELAYS = longArrayOf(30_000, 120_000, 600_000, 1_800_000)
        private const val NETWORK_SETTLE_MS = 2_000L
        private const val NETWORK_MIN_INTERVAL_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private class InvalidVersionResponse : IOException()
    private val retry = Runnable { check(source = "retry") }
    private var active = false
    private var checking = false
    private var failures = 0
    private var retryAt: Long? = null
    private var attemptedAt: Long? = null
    private var attemptedElapsed = 0L
    private var result = "not checked in this process"
    @Volatile var diagnostics = "not checked in this process"; private set

    @MainThread
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        scheduleRetry()
        record(if (active) "home resumed" else "home paused")
    }

    @MainThread
    fun check(
        force: Boolean = false,
        maxAgeMs: Long = MAX_AGE_MS,
        networkCameUp: Boolean = false,
        source: String = "home",
    ) {
        if (checking) {
            record("$source skipped: in flight")
            return
        }
        val now = elapsedTime()
        if (!force) {
            if (failures > 0) {
                if (networkCameUp) {
                    // Coalesce callbacks without postponing an already due retry or hammering a
                    // failing endpoint when firmware repeatedly reports the same network.
                    val soon = maxOf(now + NETWORK_SETTLE_MS, attemptedElapsed + NETWORK_MIN_INTERVAL_MS)
                    retryAt = minOf(retryAt ?: soon, soon)
                    scheduleRetry()
                    record("$source retry after network recovery")
                    return
                }
                if (now < (retryAt ?: now)) {
                    scheduleRetry()
                    record("$source skipped: retry pending")
                    return
                }
            } else {
                val checked = lastSuccess()
                val age = wallTime() - checked
                // A clock set backwards must not suppress checks indefinitely.
                if (checked > 0 && age >= 0 && age < maxAgeMs) {
                    record("$source skipped: fresh")
                    return
                }
            }
        }
        handler.removeCallbacks(retry)
        retryAt = null
        checking = true
        attemptedAt = wallTime()
        attemptedElapsed = now
        record("$source started")
        scope.launch {
            try {
                val latest = read().trim().removePrefix("v")
                if (!latest.matches(Regex("\\d+(\\.\\d+)*"))) throw InvalidVersionResponse()
                save(latest, wallTime())
                failures = 0
                result = "success version=$latest"
            } catch (e: CancellationException) {
                result = "cancelled"
                throw e
            } catch (e: Exception) {
                val delay = RETRY_DELAYS[failures.coerceAtMost(RETRY_DELAYS.lastIndex)]
                failures = (failures + 1).coerceAtMost(RETRY_DELAYS.size)
                retryAt = elapsedTime() + delay
                // Exception messages can contain network addresses; report types, not payloads.
                result = if (e is InvalidVersionResponse) "failed invalid version response"
                else "failed ${e.javaClass.simpleName}" + (e.cause?.let { "/${it.javaClass.simpleName}" } ?: "")
            } finally {
                checking = false
                scheduleRetry()
                record("$source finished: $result")
            }
        }
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retry)
        val due = retryAt ?: return
        if (active && !checking) handler.postDelayed(retry, (due - elapsedTime()).coerceAtLeast(0))
    }

    private fun record(event: String) {
        fun stamp(time: Long?) = time?.takeIf { it > 0 }?.let {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(it))
        } ?: "never"
        val next = retryAt?.let { stamp(wallTime() + (it - elapsedTime()).coerceAtLeast(0)) } ?: "none"
        diagnostics = "attempt=${stamp(attemptedAt)}, success=${stamp(lastSuccess())}, result=$result, " +
            "inFlight=$checking, retry=$next, foreground=$active"
        log("$event; $diagnostics")
    }
}
