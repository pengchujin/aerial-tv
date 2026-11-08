package app.orchard.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.orchard.tv.MainActivity
import app.orchard.tv.system.DiagLog
import kotlin.concurrent.thread

/**
 * After a real reboot: restore Home Guard, and if the firmware still hands Home to its own
 * launcher, open Aerial. Starting an activity from a receiver needs the "display over other apps"
 * permission on Android 10+, which tools/install.sh grants.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the boot broadcasts only; anything else could use it to pull Aerial forward.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        // Off the main thread: putting the guard back is settings, AccountManager and sync calls
        // into system_server, and the broadcast's timeout counts every one. XGIMI Z6X (Android 8)
        // sends LOCKED_BOOT_COMPLETED again when the screen locks, while the screensaver's film
        // decodes and the system answers slowly; the receiver ran out of time there and the TV
        // reported Aerial as not responding (issue #614).
        val pending = goAsync()
        val start = SystemClock.elapsedRealtime()
        thread(name = "AerialBoot") {
            try {
                onBoot(context, action)
            } finally {
                pending.finish()
                // In the diagnostics: how long the slow side of #614 takes on the TV at hand.
                DiagLog.i("${action.substringAfterLast('.')} handled in ${SystemClock.elapsedRealtime() - start} ms")
            }
        }
    }

    private fun onBoot(context: Context, action: String) {
        GuardSetup.ensureEnabled(context)
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        GuardSetup.rescanIfMissing(context)
        // Also when Aerial is the default Home: some firmwares start their own launcher at boot
        // regardless (Skyworth/Coolita 8R145, issue #43).
        // A guard that is already running looks at what is in front before taking Home: by the time
        // this arrives, an HDMI-CEC source may already have switched the TV to its input. It still
        // has to look: on Xiaomi MiTV-MTEQ0 (Android 10) it is bound ~20 s before BOOT_COMPLETED,
        // never sees PatchWall's windows, and the take-over at bind time did not stick (issue #154).
        if (HomeGuardService.onBootCompleted()) return
        // JMGO S60 (Amlogic T968): Settings' "Startup Source" has the TV open that input itself at
        // power-on, seconds before BOOT_COMPLETED (2026-11-07: the input at 08:00:43, Aerial at
        // 08:00:51, the input then finished). Coming to the front here covered it. Home Guard leaves
        // the input's player alone, and Home still brings Aerial back on this TV.
        if (GuardSetup.startsOnSignalSource(context)) {
            DiagLog.i("boot: the TV starts on a signal source; leaving it")
            return
        }
        if (MainActivity.leftForAnApp) {
            DiagLog.i("boot: an app opened from Aerial is in front; leaving it")
            return
        }
        runCatching {
            context.startActivity(MainActivity.bringBackIntent(context))
        }
    }
}
