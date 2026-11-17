package app.orchard.tv.lite

import android.app.ActivityManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * The TCL MS901 / Android 4.2.1 watch for the one path HomeActivity cannot see: an app opened
 * straight from the TV's external-input screen, with Home behind it. On this firmware STORAGE is
 * what routes picture AND audio back to Android; while HDMI is selected a video app stays silent
 * and the external input keeps playing in the background. Home's onResume() only fires when Home
 * itself comes back, so it cannot put STORAGE back in that case.
 *
 * This follows the owner's JustTvLauncher implementation (tvinput/TvMonitorService.kt), already
 * proven on this very set: a START_STICKY Service on its own HandlerThread reads
 * ActivityManager.getRunningTasks(1), acts only when the top package CHANGES (dedupe, so the
 * reflection is not repeated every tick), ignores the TV's own packages, and checks
 * getCurrentInputSource() before setInputSource(STORAGE). Android 4.x has no UsageStats and no
 * foreground-app callback for a normal app; GET_TASKS is already declared up to API 20, and from
 * 5.0 it answers only Lite's own task, so the watch is not even started there.
 *
 * Two differences keep it safe for upstream (PR #1556 review):
 *  - TclSourceWatch.start() is gated on TclMs901Sources.watchable(): the firmware's source table
 *    must be readable AND Android's picture/audio must live on STORAGE (the same capability as
 *    upstream's TclMs901Picture / TclMs901Pages), not a brand string. Other TCL MStar boards
 *    never start this Service and keep the strict "only restore a Lite-initiated switch" rule.
 *  - Home itself and the TV's picture/panels are excluded, so watching the input that was just
 *    picked is never interrupted; the decision and the brief post-switch hold live in
 *    TclMs901Sources.foreground(). The screen-off receiver stops polling entirely; no wake lock.
 */
class TclSourceWatch : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var power: PowerManager
    private var running = false
    @Volatile private var screenOn = true
    /** JustTvLauncher's dedupe: only a new top package is worth a binder call to the TV service. */
    private var lastTopPackage: String? = null
    private var lastCheckAt = 0L

    /**
     * The TV's own picture, its source panel and its settings overlays: they do not need Android
     * audio and must not lose the input the viewer is watching. The first six are the exact
     * TV_PACKAGES list from JustTvLauncher's TvMonitorService (TCL / Konka / Hisense / iFlytek);
     * the rest are MS901's own settings and manager packages.
     */
    private val tvPackages = setOf(
        "com.tcl.tv", "com.iflytek.showcomesettings", "com.tcl.showmessage",
        "com.konka.tvsettings", "com.hisense.ui", "com.jamdeo.tv.livetv",
        "com.tcl.insrc", "com.tcl.mstar.settings", "com.tcl.settings",
        "com.android.settings", "com.tcl.tvmanager", "com.tcl.tv.sys", "com.tcl.ui"
    )

    private val monitorTask = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                checkAndSwitchInputSource()
            } catch (t: Throwable) {
                Log.w(TAG, "monitor task failed", t)
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    running = false
                    handler.removeCallbacks(monitorTask)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    ensureLoop()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("tv-monitor").apply { start() }
        handler = Handler(thread.looper)
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        screenOn = if (Build.VERSION.SDK_INT >= 20) power.isInteractive else power.isScreenOn
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }, null, handler)
        Log.i(TAG, "created (screen on: $screenOn)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (screenOn) ensureLoop()
        return START_STICKY
    }

    private fun ensureLoop() {
        if (running) return
        running = true
        lastTopPackage = null
        lastCheckAt = 0L
        handler.removeCallbacks(monitorTask)
        handler.post(monitorTask)
    }

    /**
     * getRunningTasks is cheap; the TV manager's reflection is not, so it only runs when the app
     * in front changes, or every RESCAN_MS while the same app stays there (the viewer may have
     * changed the input with the remote behind it - exactly the silent-audio case).
     */
    private fun checkAndSwitchInputSource() {
        if (!screenOn) return
        // A stale sticky service after an update on another TV stops itself here.
        if (!TclMs901Sources.watchable(this)) {
            Log.i(TAG, "not a STORAGE-backed TCL firmware; stopping the watch")
            running = false
            stopSelf()
            return
        }
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val top = runCatching { am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName }
            .onFailure { Log.d(TAG, "getRunningTasks failed", it) }.getOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        val changed = top != lastTopPackage
        if (!changed && now - lastCheckAt < RESCAN_MS) return
        lastTopPackage = top
        lastCheckAt = now
        if (!needsAndroidAudio(top)) return
        TclMs901Sources.foreground(this, top)
    }

    /**
     * A real Android app in front. Home itself is left to its own onResume()/backToStorage();
     * the TV's packages, the IME and system overlays are not apps that need Android's audio.
     */
    private fun needsAndroidAudio(pkg: String): Boolean {
        if (pkg == packageName || pkg in tvPackages) return false
        val ime = runCatching { Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) }
            .getOrNull()?.substringBefore('/')
        return pkg != ime
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(monitorTask)
        unregisterReceiver(screen)
        thread.quit()
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "AerialTclWatch"
        /** ForegroundMonitorService's interval: short enough that a silent app is fixed quickly. */
        private const val CHECK_INTERVAL_MS = 2000L
        /** TvMonitorService's interval, kept as the re-scan of an unchanged top package. */
        private const val RESCAN_MS = 10000L

        /** Called from Home, after an input switch and on BOOT_COMPLETED; no-op on other TVs. */
        fun start(context: Context) {
            if (Build.VERSION.SDK_INT >= 21) return
            if (!TclMs901Sources.watchable(context)) return
            runCatching {
                context.applicationContext.startService(Intent(context, TclSourceWatch::class.java))
            }.onFailure { Log.w(TAG, "could not start the TCL input watch", it) }
        }
    }
}
