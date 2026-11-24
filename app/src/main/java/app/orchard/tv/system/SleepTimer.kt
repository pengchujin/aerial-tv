package app.orchard.tv.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.orchard.tv.guard.HomeGuardService

class SleepTimer : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        HomeGuardService.sleep("sleep timer")
    }

    companion object {
        private fun operation(context: Context, flags: Int): PendingIntent? =
            PendingIntent.getBroadcast(context, 0, Intent(context, SleepTimer::class.java),
                flags or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)

        fun active(context: Context) = operation(context, PendingIntent.FLAG_NO_CREATE) != null

        fun cancel(context: Context) {
            val pending = operation(context, PendingIntent.FLAG_NO_CREATE) ?: return
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
            pending.cancel()
        }

        fun schedule(context: Context, minutes: Int) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
            val pending = operation(context, PendingIntent.FLAG_CANCEL_CURRENT) ?: return
            alarm.set(AlarmManager.ELAPSED_REALTIME, deadline, pending)
        }
    }
}
