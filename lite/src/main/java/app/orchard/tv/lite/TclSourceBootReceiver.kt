package app.orchard.tv.lite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * JustTvLauncher starts its TvMonitorService from BOOT_COMPLETED (tvinput/BootReceiver.kt):
 * the TV can boot straight into its last external input, before Home is ever resumed, and the
 * silent-audio case is then already present. Home also starts the watch in onCreate(), so this
 * is only the boot-time safety net. TclSourceWatch.start() does the capability check - on any
 * other TV this receiver does nothing at all.
 */
class TclSourceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "boot completed; checking whether the TCL input watch is needed")
        TclSourceWatch.start(context.applicationContext)
    }

    private companion object {
        const val TAG = "AerialTclWatch"
    }
}
