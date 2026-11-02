package app.orchard.tv.proxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.orchard.tv.MainActivity
import app.orchard.tv.R
import java.util.concurrent.Executors

/**
 * User-started proxy lifetime; an engine is never silently restarted after boot or a crash. Also
 * Aerial's VpnService: only this object can open the TUN that ProxyManager hands to the core.
 * START (from the app process starting with a profile, or the phone page) runs the core; RESTART
 * restarts a running one when TUN is turned on or off; STOP ends it. All three run in order on one
 * worker thread.
 */
class ProxyService : VpnService() {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "AerialProxyService") }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.proxy_channel), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) },
            )
        }
        val notification = notification(starting = true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION, notification)
        }
    }

    // The system binds with android.net.VpnService to manage the tunnel; returning null here would
    // break that binding (VpnService.onBind answers it and returns null for other actions).
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    /**
     * Another VPN app took Android's single VPN slot, or the user disconnected Aerial's VPN in
     * Settings. Not super (that stops the service): the proxy keeps running without TUN.
     */
    override fun onRevoke() {
        ProxyManager.onVpnRevoked()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || intent == null) {
            worker.execute {
                try { runCatching { ProxyManager.onServiceStop(applicationContext) } } finally {
                    main.post { if (!destroyed) stopSelfResult(startId) }
                }
            }
        } else {
            val restart = intent.action == RESTART
            worker.execute {
                val ready = runCatching {
                    if (restart) ProxyManager.onServiceRestart(applicationContext, this)
                    else ProxyManager.onServiceStart(applicationContext, this)
                }.getOrDefault(false)
                main.post {
                    if (destroyed) return@post
                    if (ready) getSystemService(Context.NOTIFICATION_SERVICE).let { it as NotificationManager }
                        .notify(NOTIFICATION, notification(starting = false))
                    else stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        worker.execute { runCatching { ProxyManager.onServiceStop(applicationContext) } }
        worker.shutdown()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    /**
     * Aerial's tunnel, or null if Android has no permission for it (VpnService.Builder.establish).
     * IPv4 only: with no IPv6 address or route Android blocks IPv6 for the apps in the VPN instead
     * of letting it bypass the tunnel (VpnService.Builder.allowFamily). Aerial itself is outside
     * the VPN: the core runs as Aerial's uid, so its own connections go straight to Wi-Fi/Ethernet
     * and never loop back into the tunnel, and the phone page and Home's own traffic stay direct.
     * Address, DNS and MTU match what the core derives itself (MihomoConfig.TUN_*).
     */
    internal fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.proxy_vpn_session))
            .setMtu(MihomoConfig.TUN_MTU)
            .addAddress(MihomoConfig.TUN_ADDRESS, MihomoConfig.TUN_PREFIX)
            .addDnsServer(MihomoConfig.TUN_DNS)
            .addDisallowedApplication(packageName)
        TunRoutes.complement().forEach { builder.addRoute(it.address, it.prefix) }
        // Without this Android 10+ marks the VPN metered and apps such as YouTube lower quality.
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        builder.setConfigureIntent(PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        ))
        return builder.establish()
    }

    private fun notification(starting: Boolean): android.app.Notification {
        val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or immutable,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.proxy_notification_title))
            .setContentText(getString(if (starting) R.string.proxy_notification_starting else R.string.proxy_notification_running))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.proxy_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "aerial_proxy"
        private const val NOTIFICATION = 8791
        private const val START = "app.orchard.tv.proxy.START"
        private const val RESTART = "app.orchard.tv.proxy.RESTART"
        private const val STOP = "app.orchard.tv.proxy.STOP"

        fun requestStart(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ProxyService::class.java).setAction(START))
        }

        fun requestRestart(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ProxyService::class.java).setAction(RESTART))
        }

        fun requestStop(context: Context) {
            // Starting with STOP is intentional: it also repairs a stale proxy lease if the
            // core/service has already exited. The service foregrounds before doing any work.
            ContextCompat.startForegroundService(context, Intent(context, ProxyService::class.java).setAction(STOP))
        }
    }
}