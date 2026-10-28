package app.orchard.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import app.orchard.tv.system.DiagLog
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Brings Home Guard back after the firmware kills Aerial.
 *
 * Xiaomi force-stops every third-party app when the TV goes to standby, and Android drops a
 * force-stopped app's accessibility services from the enabled list for good. Notification
 * listeners are treated differently: the system keeps the approval and binds the listener again
 * shortly after its process dies. That rebind is the one thing that reliably restarts Aerial
 * after a standby, so it is used for exactly this. No notification is read or touched.
 */
class RevivalListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var recovering = false
    private var destroyed = false
    private var attempt = 0
    private var revivalNoted = false
    private var watchingUnlock = false
    private var outcome = "not started"
    private val check = Runnable { checkGuard() }
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            startRecovery("user unlocked")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Skyworth 8N312_H5D (Android 8) binds this service at boot, but leaves the guard
        // enabled and unbound. Do not depend on onListenerConnected or USER_UNLOCKED alone:
        // either callback can be missing, while the service and its main looper are alive.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
                watchingUnlock = true
            }.onFailure {
                trace("could not watch unlock; relying on delayed checks: ${it.message}")
            }
        }
        startRecovery("service created")
    }

    override fun onListenerConnected() {
        if (destroyed) return
        trace("notification listener connected")
        // Keep the quick path on wake. The delayed checks also cover a binding that did not
        // arrive after this write; none of the paths may touch credential storage while locked.
        if (GuardSetup.isUserUnlocked(this)) {
            runCatching {
                prepareDiagnostics()
                noteRevival()
                GuardSetup.ensureEnabled(this)
            }.onFailure {
                trace("initial recovery failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        startRecovery("listener connected")
    }

    private fun startRecovery(reason: String) {
        if (destroyed || recovering) return
        recovering = true
        attempt = 0
        outcome = reason
        trace("starting checks: $reason")
        handler.postDelayed(check, RETRY_DELAYS_MS[0])
    }

    private fun checkGuard() {
        if (destroyed || !recovering) return
        if (HomeGuardService.isRunning) {
            finishRecovery("guard bound")
            return
        }
        if (attempt >= RETRY_DELAYS_MS.size) {
            finishRecovery("retry window exhausted; guard still unbound")
            return
        }
        attempt++
        if (GuardSetup.isUserUnlocked(this)) {
            runCatching {
                prepareDiagnostics()
                noteRevival()
                trace("attempt $attempt: unlocked, guard unbound; requesting recovery")
                GuardSetup.ensureEnabled(this)
                // Reuse the existing rebind: it preserves other services, waits between the
                // two writes, and respects the owner's guard_off_in_standby setting.
                GuardSetup.rebindIfStalled(this)
            }.onFailure {
                trace("attempt $attempt failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        } else {
            trace("attempt $attempt: waiting for user unlock")
        }
        // A final check gives Android time to finish the last asynchronous rebind. Stop after
        // about two minutes: this is recovery at service creation, not a permanent watchdog.
        handler.postDelayed(check, RETRY_DELAYS_MS.getOrElse(attempt) { FINAL_CHECK_DELAY_MS })
    }

    private fun prepareDiagnostics() {
        DiagLog.init(this)
    }

    private fun noteRevival() {
        if (revivalNoted) return
        revivalNoted = true
        GuardSetup.noteRevival(this)
    }

    private fun trace(message: String) = DiagLog.i("revival: $message")

    private fun finishRecovery(result: String) {
        recovering = false
        outcome = result
        trace(result)
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("Aerial revival: active=$recovering attempts=$attempt outcome=$outcome")
        writer.println("userUnlocked=${GuardSetup.isUserUnlocked(this)} guardRunning=${HomeGuardService.isRunning}")
        DiagLog.dump(writer)
    }

    override fun onDestroy() {
        destroyed = true
        recovering = false
        handler.removeCallbacks(check)
        if (watchingUnlock) runCatching { unregisterReceiver(unlocked) }
        watchingUnlock = false
        super.onDestroy()
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(5_000, 5_000, 10_000, 15_000, 30_000, 60_000)
        const val FINAL_CHECK_DELAY_MS = 5_000L
    }
}
