package app.orchard.tv.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.orchard.tv.MainActivity
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.SystemProbe

/**
 * Keeps Aerial as the home screen on firmwares that will not let a third-party app own HOME
 * (Xiaomi PatchWall, some TCL and Hisense builds), without disabling any system app:
 *
 * 1. When the stock launcher's home activity comes to the front (the Home key, an app exiting to
 *    it, the TV booting into it), Aerial is brought back on top.
 * 2. The viewer can still open the stock launcher on purpose (its tile in Aerial's grid, for the
 *    maker's own content). While it is in front the Home key is answered with Aerial, since
 *    pressing Home there would otherwise just stay put.
 *
 * Keys are filtered only up to Android 11 (see updateKeyFilter). With the filter on, every key
 * press on the TV detours through this service; some apps then see a press as held and move twice
 * (云视听小电视 on Xiaomi MiTV-MFTR0, reported by a user and gone once Aerial was uninstalled).
 *
 * Where the stock launcher raises no accessibility event at all (Xiaomi's PatchWall up to Android
 * 6, Hisense VIDAA's 聚好看), the app in front is also read from usage stats once a second (see
 * foregroundCheck).
 *
 * Both only act while Aerial is not the resolved HOME app; once it is, the system does the work.
 * The service reads window-change events and, on those TVs, which app is in front; it never reads
 * screen content.
 */
class HomeGuardService : AccessibilityService() {

    /** HOME activities that are not ours: package to class names. Refreshed on every connect. */
    private var stockHomes: Map<String, Set<String>> = emptyMap()

    /** Set when the user opens the stock launcher on purpose from Aerial's grid. */
    private var allowStockUntil = 0L

    /** The last window that came to the front, as package to class; null until one does. */
    private var lastWindow: Pair<String, String>? = null

    private val handler = Handler(Looper.getMainLooper())

    /** When the screen last lit, for take-overs that are really a wake (issue #111). */
    private var screenOnAt = Long.MIN_VALUE / 2

    private var takeOverReason = ""
    private var takeOverOnWake = false
    private val takeOver = Runnable { takeOverIfStockHomeInFront(takeOverReason, takeOverOnWake) }

    /**
     * Mi TV 4 (MiTV4-ANSM0, Android 6.0.1) covers Aerial some 20 s into a boot with PatchWall's
     * BootActivity, which is not its home activity. When that closes, the home activity under it is
     * only uncovered and no window change follows, so Aerial stayed behind for good (issue #318,
     * the reporter's boot timings). Looks again twice in the first minute after a boot take-over.
     */
    private val bootRecheck = Runnable {
        if (SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS) return@Runnable
        // Bound early in a boot, the guard found no stock launcher at all on the same Mi TV 4, and
        // so took PatchWall for an app the viewer chose (issue #318, the reporter's 1.40 log).
        if (stockHomes.isEmpty()) refreshStockHomes()
        takeOverIfStockHomeInFront("looking again after boot", woke = false)
    }

    /**
     * Xiaomi PatchWall up to Android 6 is invisible to accessibility: no window change when it comes
     * to the front, no Home key in the filter (MitvPhoneWindowManager takes it first), while other
     * apps' events arrive (MiTV4-ANSM0, Android 6.0.1, issue #81; also #209, #220, #355, #413 on
     * MiTV4A, MiTV4X and MiBOX4). Usage stats still say what is in front, so look there once a
     * second. Each take-over answers one move to the front: Aerial takes seconds to open on those
     * TVs, and the stock launcher's event stays the newest meanwhile. Found and checked on a
     * MiTV4-ANSM0 by a user, with a local build (issue #81). Needs GET_USAGE_STATS, granted over
     * ADB by the install command; without it nothing is polled.
     */
    private val foregroundCheck = object : Runnable {
        override fun run() {
            handler.postDelayed(this, FOREGROUND_POLL_MS)
            // Not skipped where Aerial is the default Home, for the same reason as the Home key:
            // Panasonic DX01C (Skyworth-made, Tianci, Android 6) shows its own launcher at power-on
            // and on the Home key whatever Home resolves to.
            if (screenOff || !GuardSetup.isUserUnlocked(this@HomeGuardService)) return
            if (SystemClock.uptimeMillis() < allowStockUntil) return
            val (window, at) = foreground() ?: return
            if (at <= foregroundTakenAt) return
            if (stockHomes.isEmpty()) refreshStockHomes()
            // Its home screen only, as for window changes: PatchWall's children's mode, opened
            // from its tile, is in the same package and stays.
            val (pkg, cls) = window
            if (cls !in stockHomes[pkg].orEmpty()) return
            foregroundTakenAt = at
            if (SystemClock.uptimeMillis() - broughtBackAt < 1_000) return
            Log.i(TAG, "stock launcher $pkg in front by usage stats; bringing Aerial back")
            goHome(woke = SystemClock.elapsedRealtime() - screenOnAt < WAKE_WINDOW_MS)
        }
    }

    /** The usage-stats time of the move to the front the foreground check last answered. */
    private var foregroundTakenAt = Long.MIN_VALUE / 2

    /** The newest activity moved to the front in the last few seconds, with its time; null if none. */
    private fun foreground(lookbackMs: Long = FOREGROUND_LOOKBACK_MS): Pair<Pair<String, String>, Long>? = runCatching {
        val now = System.currentTimeMillis()
        val events = getSystemService<UsageStatsManager>()?.queryEvents(now - lookbackMs, now) ?: return null
        var last: Pair<Pair<String, String>, Long>? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.className != null) {
                last = (event.packageName to event.className) to event.timeStamp
            }
        }
        last
    }.getOrNull()



    /** The activity that last moved to the front, from usage stats, unless it is Aerial's; see takeOverIfStockHomeInFront. */
    private fun frontByUsage(): Pair<String, String>? {
        if (!usageStatsAllowed(this)) return null
        return foreground(UNKNOWN_FRONT_LOOKBACK_MS)?.first?.takeIf { it.first != packageName }
    }

    private fun scheduleBootRechecks() {
        handler.removeCallbacks(bootRecheck)
        BOOT_RECHECKS_MS.forEach { handler.postDelayed(bootRecheck, it) }
    }

    /** Whether any window change has reached this binding. */
    private var sawEvent = false

    /**
     * On the same Mi TV 4 the guard bound at boot is sometimes deaf: bound, yet no window change
     * ever arrives, and only switching accessibility off and on again brings them (issue #318:
     * rewriting the list of services was not enough). Up to Android 6, once per process. Also
     * looks for the stock launchers again if none were found at the connection.
     */
    private val deafCheck = Runnable {
        if (stockHomes.isEmpty()) refreshStockHomes()
        if (sawEvent || deafRetried) return@Runnable
        deafRetried = true
        Log.i(TAG, "no window change since bound; switching accessibility off and on")
        GuardSetup.restartAccessibility(this)
    }

    /**
     * Bound before unlock (Direct Boot), the guard takes over as soon as credential storage is
     * available, from a process that is already running: on TCL 75S365C (Amlogic t982) starting a
     * process right after unlock waits several seconds behind TCL's boot-time scheduling (PR #45:
     * unlock to Aerial 7.6 s -> 0.9 s). It looks at what is in front first, like every take-over, so
     * an input that HDMI-CEC switched to at power-on is left alone.
     */
    private val userUnlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Before unlock only Direct Boot aware launchers resolve.
            refreshStockHomes()
            watchCecInput()
            updateKeyFilter()
            // The ADB and screensaver checks were skipped while locked.
            GuardSetup.ensureEnabled(this@HomeGuardService)
            val skipReason = when {
                SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS -> "outside boot window"
                getSystemService<PowerManager>()?.isInteractive != true -> "screen off"
                else -> null
            }
            if (skipReason == null) takeOverOnceSettled("user unlocked", woke = true)
            else Log.i(TAG, "user unlocked; not taking over Home ($skipReason)")
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            updateKeyFilter()
            if (screenOff) {
                if (GuardSetup.offInStandby(this@HomeGuardService)) GuardSetup.stepOutForStandby(this@HomeGuardService)
                return
            }
            // Firmwares put their own launcher back in front while the TV is in standby, and no
            // window change follows on wake. Look once things have settled after the screen lights.
            screenOnAt = SystemClock.elapsedRealtime()
            takeOverOnceSettled("screen on", woke = true)
        }
    }

    /**
     * In standby the guard filters no keys, so the power key that wakes the TV goes to the system
     * alone rather than through Aerial's process, which the firmware may have frozen by then. A TCL
     * tc8000_ay30a2 (Android 11) with the guard running never came back from standby and had to be
     * unplugged; without the guard it woke every time (issue #371). Back on at SCREEN_ON.
     */
    private var screenOff = false

    /**
     * The Home key where keys are not filtered (see updateKeyFilter): the system closes its dialogs
     * with reason "homekey" for it. In the stock launcher, opened on purpose from Aerial or simply
     * in front, Home starts the launcher over itself and no window change follows. With the front
     * unknown too: the guard leaves an unknown front alone when Aerial keeps being stopped, but a
     * Home press says what the viewer wants. Elsewhere the window change does it.
     */
    private val homeKey = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Not skipped where Aerial is the default Home: Panasonic DX01C (Skyworth-made, Tianci,
            // Android 6) answers the Home key by starting its own launcher by component, and Home
            // resolution never happens.
            if (intent.getStringExtra("reason") != "homekey") return
            if (backOnPause) noteHomeInAerial()
            val front = lastWindow?.first
            if (front != null && front !in stockHomes) return
            // The stock launcher's window change usually comes first and already brought Aerial
            // back; starting it again redid Home's resume (MiTV-MFTR0: 0.2 s apart).
            if (SystemClock.uptimeMillis() - broughtBackAt < 1_000) return
            Log.i(TAG, "Home pressed with ${front ?: "an unknown app"} in front; bringing Aerial back")
            allowStockUntil = 0L
            goHome()
        }
    }

    /** The firmware putting its own screensaver back while Aerial's is chosen (GuardSetup). */
    private val screensaverObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = GuardSetup.screensaverSettingChanged(this@HomeGuardService)
        }
    }

    private val adbObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = GuardSetup.ensureAdb(this@HomeGuardService)
        }
    }

    /**
     * At power-on the guard's process starts well before Android connects the service: on Xiaomi
     * MiTV-MFTR0 (Android 14) 4.7 s and 5 s in two boots, with PatchWall on screen meanwhile
     * (11-13 s from the screen lighting to Aerial). Within the boot window the take-over starts
     * here; it treats the front as unknown, as onServiceConnected does when nothing has been seen.
     * Only with something chosen to open at power-on, which goes over whatever is in front anyway.
     * Otherwise an input PatchWall plays at power-on (the last one used) or an HDMI-CEC device
     * switched to would be pulled out of, which only the window changes after the connection can
     * tell (see takeOverOnceSettled). Not after a wake either, for the same reason.
     */
    override fun onCreate() {
        super.onCreate()
        if (SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS || !GuardSetup.isUserUnlocked(this)) return
        if (!hasBootSource()) return
        if (getSystemService<PowerManager>()?.isInteractive != true) return
        Log.i(TAG, "started shortly after boot; taking over before the service is connected")
        takeOverOnceSettled("started shortly after boot", woke = true)
    }

    // The opt-in CEC Home (CecHome) depends on the input in front, which can change without a new
    // window from the player. Watched only while it is configured, and only its own setting key.
    private val cecInputObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = updateKeyFilter()
    }
    private val cecInputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = updateKeyFilter()
        override fun onInputRemoved(inputId: String) = updateKeyFilter()
        override fun onInputUpdated(inputId: String) = updateKeyFilter()
        override fun onInputStateChanged(inputId: String, state: Int) = updateKeyFilter()
    }
    private var cecWatching = false
    /** Keys are filtered only for CecHome: everything but its Home goes on untouched. */
    private var filteringForCecOnly = false

    private fun watchCecInput() {
        if (cecWatching) {
            runCatching { contentResolver.unregisterContentObserver(cecInputObserver) }
            runCatching { getSystemService<TvInputManager>()?.unregisterCallback(cecInputCallback) }
            cecWatching = false
        }
        val key = CecHome.inputSettingKey(this) ?: return
        runCatching {
            contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, cecInputObserver)
            contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, cecInputObserver)
            getSystemService<TvInputManager>()?.registerCallback(cecInputCallback, handler)
            cecWatching = true
        }.onFailure { Log.w(TAG, "could not watch the current input", it) }
    }

    override fun onServiceConnected() {
        instance = this
        // Register before querying homes or checking the state so an unlock cannot be missed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(userUnlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        // Start the boot take-over timer before the init below. With an unknown front the decision
        // does not need stockHomes, and the init is not free: on Skyworth skyT963 (1AA46_V58FP,
        // Android 9) refreshStockHomes and the rest took ~3 s, all of it added to what the stock
        // launcher showed at boot. CEC is safe the same way it was: the take-over only acts when a
        // stock home is still in front when it fires, so an input the firmware has already switched
        // to is left alone; the input-switch pass-through below keeps its own longer settle.
        val fastBootTakeOver = GuardSetup.isUserUnlocked(this) &&
            SystemClock.elapsedRealtime() < BOOT_WINDOW_MS &&
            getSystemService<PowerManager>()?.isInteractive == true
        if (fastBootTakeOver) {
            takeOverOnceSettled("bound shortly after boot", woke = true)
            scheduleBootRechecks()
        }
        refreshStockHomes()
        screenOff = getSystemService<PowerManager>()?.isInteractive == false
        updateKeyFilter()
        watchCecInput()
        volumeSeen = volumeNow()
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        @Suppress("DEPRECATION")
        ContextCompat.registerReceiver(this, homeKey, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_NOT_EXPORTED)
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        contentResolver.registerContentObserver(Settings.Secure.getUriFor("screensaver_components"), false, screensaverObserver)
        GuardSetup.ensureAdb(this)
        // Not only at boot: a guard switched on later was just as deaf, for good, until Aerial was
        // stopped or accessibility switched off and on (MiTV4-ANSM0, issue #381).
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) handler.postDelayed(deafCheck, DEAF_AFTER_MS)
        // Hisense VIDAA's own launcher (聚好看, com.jamdeo.tv.vod) raises no window change to
        // third-party services on any version seen, while other apps' events arrive; usage stats
        // record its moves to the front as on PatchWall (Vidda C1S, MT9669, Android 9, PR #545,
        // checked by the author with a local build). Only where that launcher is a stock home, so
        // other TVs past Android 6 are not polled.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M || VIDAA_LAUNCHER in stockHomes) {
            handler.removeCallbacks(foregroundCheck)
            if (usageStatsAllowed(this)) handler.post(foregroundCheck)
            else Log.i(TAG, "usage stats not allowed; not watching the front by them")
        }
        if (!GuardSetup.isUserUnlocked(this)) {
            Log.i(TAG, "bound while user locked; deferred takeover until unlock")
            return
        }
        // Bound a little after boot, or brought back after the firmware killed Aerial: either way
        // the stock launcher is what's in front, and the window change that showed it is long gone.
        // Not after an update, though: installing over Aerial also kills it, and whoever is watching
        // an input or another app at that moment must not be pulled back to Home.
        val revived = SystemClock.elapsedRealtime() - GuardSetup.lastRevivedAt < 60_000 && !justUpdated()
        // Aerial died with its home screen in front (it crashed, was updated, or was killed) and the
        // stock launcher was uncovered; pressing Home there changes nothing the guard could see
        // (TCL MT5879, after the screensaver crash of 1.14). Updates count too: Aerial was in front.
        val diedInFront = GuardSetup.wasHomeInFront(this) &&
            (Build.VERSION.SDK_INT < 24 || SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000)
        // Revived with the screen off (standby): the screen-on check takes over at wake.
        // Not gated on being the default Home: Skyworth/Coolita (8R145, Android 11) starts its own
        // launcher at boot whatever Home resolves to (issue #43). The take-over looks first, and
        // bringing Aerial back over itself changes nothing.
        if (!fastBootTakeOver &&
            (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || revived || diedInFront) &&
            getSystemService<PowerManager>()?.isInteractive == true
        ) {
            takeOverOnceSettled(
                when {
                    revived -> "re-enabled after being switched off"
                    diedInFront -> "back after Aerial died in front"
                    else -> "bound shortly after boot"
                },
                // Back from standby or a boot, not from a crash: the moment to open the input
                // the owner wants the TV to start on.
                woke = !diedInFront || revived,
            )
            if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) scheduleBootRechecks()
        }
    }

    /**
     * Takes Home back after a short wait, and only if nothing but the stock launcher came to the
     * front meanwhile. At power-on an HDMI-CEC source (an Apple TV) switches the TV to its input
     * just as the screen lights and the guard is bound again; taking Home at once bounced the
     * viewer straight back out of the input (reported by a user, reproduced on Xiaomi MiTV-MFTR0:
     * the input's ExternalSourceActivity started 130 ms before the guard was bound).
     */
    private fun takeOverOnceSettled(reason: String, woke: Boolean) {
        takeOverReason = reason
        takeOverOnWake = woke
        handler.removeCallbacks(takeOver)
        handler.postDelayed(takeOver, SETTLE_MS)
    }

    private fun hasBootSource(): Boolean = runCatching {
        AerialSettings.init(this)
        AerialSettings.hasBootTarget
    }.getOrDefault(false)

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    /**
     * Also when Aerial is the default Home: that only steers the HOME intent. Xiaomi MiTV-ANSP0
     * (Android 9) accepts set-home-activity, yet when standby force-stops Aerial, PatchWall's old
     * task is simply uncovered and stays in front after wake (issue #6).
     */
    private fun takeOverIfStockHomeInFront(reason: String, woke: Boolean) {
        if (!GuardSetup.isUserUnlocked(this)) return
        // No window seen since bound: ask usage stats what moved to the front last. Revived after
        // the firmware killed Aerial in the background, an unknown front was taken for the stock
        // launcher and Aerial was pulled over the video the viewer was watching (Toshiba
        // tsbU6800C, Android 8, and XGIMI Z6X, Android 11, issue #497, with the logs). The same at
        // the boot rechecks, on firmwares that raise no window change for the app the viewer
        // opened (Skyworth 9S52_Q4A, Android 6, issue #489). Aerial itself says nothing about what
        // it uncovered when it died, so it stays unknown.
        val last = lastWindow ?: frontByUsage()?.also { Log.i(TAG, "$reason; usage stats say ${it.first} is in front") }
        // Unknown means we were only just bound, which happens after a standby kill. Any screen of
        // the stock launcher counts: when Aerial is killed, whatever was on top of the launcher's
        // task is uncovered, not always its home activity (PatchWall's StrategicLayerActivity).
        // Unless the firmware keeps stopping Aerial: then an unknown front is most likely the app
        // the viewer is in, and Home stays with the Home key (LeTV eUI, issue #63).
        if (last == null && GuardSetup.revivedOften(this)) {
            Log.i(TAG, "$reason, but Aerial keeps being stopped and the front is unknown; leaving it")
            return
        }
        // Brought back a moment ago and nothing seen since: what is in front is Aerial. The boot
        // take-over in onCreate and the one at the connection are a few seconds apart, and the
        // second redid Home's resume (MiTV-MFTR0).
        if (last == null && SystemClock.uptimeMillis() - broughtBackAt < 30_000) return
        val stockInFront = last == null || last.first in stockHomes
        // Aerial is in front after a wake because the guard already brought it back when the stock
        // launcher showed, or because it survived standby; either way the input the TV starts on
        // still has to open. Only with one chosen: bringing Aerial back also resets its focus.
        // Xiaomi MiTV-MTEQ0, where the guard is revived after the screen lights (issue #111).
        if (woke && last?.first == packageName && hasBootSource()) {
            Log.i(TAG, "$reason with Aerial itself in front; flagging the wake")
            goHome(woke = true)
            return
        }
        if (!stockInFront && last != null) Log.i(TAG, "$reason with ${last.first} in front; leaving it there")
        if (!stockInFront || SystemClock.uptimeMillis() < allowStockUntil) return
        Log.i(TAG, "$reason with the stock launcher in front; bringing Aerial back")
        goHome(woke)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        runCatching { unregisterReceiver(homeKey) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { unregisterReceiver(userUnlocked) }
        }
        runCatching { contentResolver.unregisterContentObserver(adbObserver) }
        runCatching { contentResolver.unregisterContentObserver(screensaverObserver) }
        if (cecWatching) {
            runCatching { contentResolver.unregisterContentObserver(cecInputObserver) }
            runCatching { getSystemService<TvInputManager>()?.unregisterCallback(cecInputCallback) }
        }
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    /**
     * Filters keys up to Android 11: there, once Home reaches the system, it holds every
     * background activity start for five seconds, so Aerial came back only after five seconds of
     * the TCL launcher (TCL MT5879, since 1.11 stopped filtering by default). Later versions take
     * Home in the stock launcher and other apps from the "homekey" broadcast instead (homeKey).
     * They used to filter there too, and the stock launcher then moved two or three places per
     * press (Xiaomi S Pro mini-LED 2026, issue #293). With Aerial itself in front they filter
     * again (1.42): Home there showed PatchWall for a moment first.
     *
     * With the opt-in X7 CEC transport, filter only while its configured HDMI input is in front,
     * even when Aerial is the default Home. Otherwise leave a default Home's keys to the system;
     * the filter only costs. On Xiaomi MiTV-ASTP0 (Android 9) it crashed the system: every filtered
     * volume key reached Xiaomi's MitvFreezer with no window, which threw in system_server and
     * restarted the TV (issue #249).
     */
    private fun updateKeyFilter() {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        // Later versions filter while Aerial itself is in front, so Home there never reaches the
        // system, which would start PatchWall and show it for a moment before the guard brought
        // Aerial back (MiTV-MFTR0, Android 14). Elsewhere they leave keys alone: #293's
        // repeating keys were in the stock launcher, and in other apps Home goes as above.
        // In front means resumed, not the last window change: an app brought back to its task
        // sends none the guard takes for an activity, and keys stayed filtered in it, two or three
        // places a press again (云视听极光 on MiTV-MFTR0, Android 14; issue #475).
        // Not on Xiaomi, which answers Home in Aerial another way: see backOnPause.
        val aerialInFront = aerialResumed && !backOnPause
        val wanted = !keysPassThrough && !screenOff && (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R || aerialInFront)
        val homeWanted = wanted && !isDefaultHome()
        val cecWanted = !screenOff && CecHome.active(this, lastWindow?.first)
        filteringForCecOnly = cecWanted && !homeWanted
        val want = if (homeWanted || cecWanted) info.flags or flag else info.flags and flag.inv()
        if (want == info.flags) return
        info.flags = want
        serviceInfo = info
        Log.i(TAG, if (want and flag != 0) "filtering keys" else "stopped filtering keys")
    }

    /**
     * Hisense VIDAA handles volume in its own firmware, which never sees keys that went through an
     * accessibility filter: with the filter on, the volume keys did nothing anywhere, though this
     * service passes them on (VIDAA_TV, RTD2851D, Android 10, issue #7). Home is no help there
     * either: VIDAA starts its launcher by component, or Aerial through persist.vt.use_home_pkg.
     */
    private val keysPassThrough by lazy {
        VIDAA_PACKAGES.any { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!GuardSetup.isUserUnlocked(this)) return false
        if (event.keyCode in VOLUME_KEYS) return if (filteringForCecOnly) false else takeVolumeKey(event)
        // Any key that is done: whatever it did to the volume (Control Center's slider) is in.
        if (event.action == KeyEvent.ACTION_UP) volumeSeen = volumeNow()
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (takeCecHome(event)) return true
        if (isDefaultHome()) return false
        // Held, Home is the firmware's task center, which taking the key hid (Xiaomi, issue #185):
        // open the recent apps instead, and leave Home alone when the key comes up. Timed here:
        // the filter sees keys before Android makes the repeats that would say the key is held.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            homeHeld = false
            handler.removeCallbacks(homeHold)
            // Pressed twice, Home is the task center on Xiaomi too (MiTV-MFTP0, issue #335). The
            // first press already brought Aerial back; the second opens the recent apps.
            if (event.eventTime - lastHomeUpAt < DOUBLE_HOME_MS) {
                homeHeld = true
                lastHomeUpAt = Long.MIN_VALUE / 2
                Log.i(TAG, "Home pressed twice; opening recent apps")
                openRecents()
                return true
            }
            handler.postDelayed(homeHold, ViewConfiguration.getLongPressTimeout().toLong())
        }
        if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(homeHold)
            if (!event.isCanceled && !homeHeld) {
                Log.i(TAG, "Home key taken before the system saw it")
                lastHomeUpAt = event.eventTime
                goHome()
                closeSystemDialogs()
            }
        }
        return true
    }

    private var cecHomeDown = false
    private var cecHomeDouble = false
    private var cecHomeHeld = false
    private val cecHomeHold = Runnable {
        if (cecHomeDown && CecHome.active(this, lastWindow?.first)) {
            cecHomeHeld = true
            lastCecHomeUp = Long.MIN_VALUE / 2
            Log.i(TAG, "HDMI Home held; returning to Aerial")
            goHome()
        }
    }
    private var lastCecHomeUp = Long.MIN_VALUE / 2

    private fun takeCecHome(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (!CecHome.active(this, lastWindow?.first)) return false
            cecHomeDown = true
            cecHomeHeld = false
            cecHomeDouble = event.eventTime - lastCecHomeUp in 0 until DOUBLE_HOME_MS
            handler.removeCallbacks(cecHomeHold)
            if (!cecHomeDouble) {
                // A single press is Home on the Apple TV, sent on key-down: never wait for the
                // key-up or the double-press window. Held or pressed twice, Home is Aerial's.
                Log.i(TAG, "HDMI Home down; sending CEC Home")
                CecHome.send(this, lastWindow?.first)
                handler.postDelayed(cecHomeHold, ViewConfiguration.getLongPressTimeout().toLong())
            }
        }
        if (!cecHomeDown) return false
        if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(cecHomeHold)
            cecHomeDown = false
            if (!event.isCanceled && !cecHomeHeld) {
                if (cecHomeDouble) {
                    lastCecHomeUp = Long.MIN_VALUE / 2
                    Log.i(TAG, "HDMI Home pressed twice; returning to Aerial")
                    goHome()
                } else {
                    lastCecHomeUp = event.eventTime
                }
            }
        }
        return true
    }

    /** The music stream's level and mute when the guard last looked, after the last key was done. */
    private var volumeSeen: Pair<Int, Boolean>? = null

    /** Whether the volume key being pressed is kept from the system, repeats included. */
    private var volumeKeyTaken = false

    private fun volumeNow(): Pair<Int, Boolean>? = getSystemService<AudioManager>()?.let {
        runCatching {
            it.getStreamVolume(AudioManager.STREAM_MUSIC) to (Build.VERSION.SDK_INT >= 23 && it.isStreamMute(AudioManager.STREAM_MUSIC))
        }.getOrNull()
    }

    /**
     * TCL MT5879 (Android 11) acts on a volume key before it reaches the filter, then again when
     * the filter passes it on: one press moved the volume two steps (28 -> 29 -> 30, 25 ms apart,
     * reported by a user and seen in the TV's log). Where the volume has already changed by the
     * time the key gets here, the key is not passed on. Decided once per press; elsewhere keys pass
     * as before (where Android's own TV volume handling has the key, it never gets here at all).
     * A held key's repeats are made by Android after the filter, so a key not passed on has none:
     * the guard steps the volume itself at Android's key repeat pace until the key comes up.
     *
     * That stepping is TCL's only (com.tcl.settings), where it was checked. Skyworth's firmware also
     * acts on the key before the filter, but the key's lift never reached the guard while Aerial
     * was in front, and one press ran the volume to 100 or 0 (7AA46_H5D and 8A002_H5D, Android 9,
     * issues #448 #451). There the firmware has the held key. Never longer than VOLUME_REPEAT_MAX_MS.
     *
     * On Skyworth/Coocaa 8R145_P3DM (Android 11, issue #460), the same "key's lift never reached
     * the guard" is caused by this method swallowing the key-up: once the volume has changed before
     * the filter (taken == true), `return taken` also swallowed the ACTION_UP, the firmware never
     * saw the lift and repeated the key indefinitely (logcat: 902 ACTION_DOWN, zero ACTION_UP;
     * one tap drove the volume for ~17 s). With the guard disabled the key-up reached the system
     * and everything was normal (6 events, 4 ACTION_UP). The key-up is therefore always passed on.
     */
    private fun takeVolumeKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            handler.removeCallbacks(volumeRepeat)
            val now = volumeNow()
            val seen = volumeSeen
            volumeKeyTaken = now != null && seen != null && now != seen
            if (volumeKeyTaken) {
                Log.i(TAG, "volume already changed before the key filter; not passing the key on")
                volumeRepeatDirection = when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> 1
                    KeyEvent.KEYCODE_VOLUME_DOWN -> -1
                    else -> 0
                }
                if (volumeRepeatDirection != 0 && repeatsHeldVolume) {
                    volumeRepeatUntil = SystemClock.uptimeMillis() + VOLUME_REPEAT_MAX_MS
                    handler.postDelayed(volumeRepeat, ViewConfiguration.getKeyRepeatTimeout().toLong())
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN) {
            // The firmware repeats the key itself, and acts on each before the filter.
            handler.removeCallbacks(volumeRepeat)
        }
        val taken = volumeKeyTaken
        if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(volumeRepeat)
            volumeKeyTaken = false
            volumeSeen = volumeNow()
            // Skyworth/Coocaa (8R145 etc.): swallowing the key-up keeps the firmware's repeats
            // running (hundreds of ACTION_DOWN, zero ACTION_UP) and the volume runs away. Always
            // pass the key-up on so the firmware stops repeating; only repeat-downs are swallowed,
            // so the volume still steps once per physical press.
            return false
        }
        return taken
    }

    private var volumeRepeatDirection = 0
    private var volumeRepeatUntil = 0L
    private val volumeRepeat: Runnable = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() > volumeRepeatUntil) {
                Log.i(TAG, "volume key still not up after ${VOLUME_REPEAT_MAX_MS / 1000} s; no more steps")
                return
            }
            SystemProbe.adjustVolume(this@HomeGuardService, volumeRepeatDirection, fromKey = true)
            handler.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }

    /** See takeVolumeKey. */
    private val repeatsHeldVolume by lazy {
        runCatching { packageManager.getPackageInfo("com.tcl.settings", 0) }.isSuccess
    }

    /**
     * What Android does for a Home press it sees, and so does not for one the filter took: Xiaomi's
     * settings panel (a system dialog window over the activity) stayed over Aerial, which was in
     * front under it (MiTV4-ANSM0, Android 6.0.1; the reporter closed it with this broadcast, issue
     * #471). Apps may send it up to Android 11 only; from 12 keys are filtered only in Aerial.
     * The guard's own homeKey receiver ignores it: Aerial was brought back just before.
     */
    private fun closeSystemDialogs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        @Suppress("DEPRECATION")
        runCatching { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey")) }
            .onFailure { Log.w(TAG, "could not close the system dialogs", it) }
    }

    /** When a Home press the guard answered came up, for a second press just after it. */
    private var lastHomeUpAt = Long.MIN_VALUE / 2

    private var homeHeld = false
    private val homeHold = Runnable {
        homeHeld = true
        Log.i(TAG, "Home held; opening recent apps")
        openRecents()
    }

    /**
     * The task centre Home opens. Dangbei OS (D5X Pro, Android 9) keeps its own recents app for the
     * remote's D-pad and leaves SystemUI's in place behind it, so the global action still opens the one
     * the remote cannot drive: start the vendor activity when the set has one, else the system's.
     */
    private fun openRecents() {
        for (action in TASK_CENTRE_ACTIONS) {
            val intent = Intent(action)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        sawEvent = true
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Only activities count as what is in front. Overlays and dialogs also raise this event:
        // TCL's voice assistant (com.tcl.walleve) does at boot on TCL 75S365C, and taking it for
        // an app the viewer chose kept the guard from taking over (PR #45).
        val before = lastWindow?.first
        if (isActivity(pkg, cls)) {
            lastWindow = pkg to cls
            if (!CecHome.active(this, pkg)) {
                lastCecHomeUp = Long.MIN_VALUE / 2
                handler.removeCallbacks(cecHomeHold)
            }
        }
        if (!GuardSetup.isUserUnlocked(this)) return
        val allowed = SystemClock.uptimeMillis() < allowStockUntil
        if (pkg in stockHomes) {
            // Any of its screens, uncovered once the installer of an update from Control Center closed.
            if (!allowed && isActivity(pkg, cls) && GuardSetup.installerJustClosed(this)) {
                Log.i(TAG, "stock launcher $pkg in front after an update; bringing Aerial back")
                goHome()
                return
            }
            updateKeyFilter()
            // Only the stock launcher's home activity itself, not its dialogs or other screens.
            // Not gated on isDefaultHome(): see takeOverIfStockHomeInFront.
            if (cls !in stockHomes.getValue(pkg) || allowed) return
            // Switching inputs passes through the stock launcher on Skyworth: the TV service starts
            // its SkyLauncher, the launcher shows for a moment, then the input. Taking Home at once
            // aborted the switch (Skyworth 8H26_M9, Android 5.1, issues #108, #155). Look again
            // once it has settled; the input will be in front by then.
            if (before in SOURCE_SWITCHERS || pkg in SOURCE_SWITCHERS) {
                Log.i(TAG, "stock home $pkg came to the front during an input switch; looking again shortly")
                takeOverOnceSettled("after an input switch", woke = false)
                return
            }
            // One Home press shows two window changes 0.2 s apart (MiTV-MFTR0); one start will do.
            // Yet a second press can come that soon, and then no window changes again: look once
            // more after the second (Coocaa C10, Android 9: Home pressed 0.39 s after Aerial was
            // back left the stock launcher in front for good, issue #487).
            val since = SystemClock.uptimeMillis() - broughtBackAt
            if (since < 1_000) {
                handler.removeCallbacks(stockHomeRecheck)
                handler.postDelayed(stockHomeRecheck, 1_000 - since + 300)
                return
            }
            Log.i(TAG, "stock home $pkg came to the front; bringing Aerial back")
            // Shortly after the screen lit, this is the wake itself (issue #111).
            goHome(woke = SystemClock.elapsedRealtime() - screenOnAt < WAKE_WINDOW_MS)
            return
        }
        updateKeyFilter()
        // Leaving the stock launcher for a real app ends the visit: Home goes to Aerial again.
        if (allowed && pkg != packageName && isApp(pkg)) allowStockUntil = 0L
    }

    private val activityCache = HashMap<Pair<String, String>, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut(pkg to cls) {
        runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
    }

    /** True for packages with a launcher entry, i.e. apps rather than system overlays and dialogs. */
    private fun isApp(pkg: String): Boolean =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null

    private fun isDefaultHome(): Boolean = resolvedHome() == packageName

    private fun resolvedHome(): String? =
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName

    private fun refreshStockHomes() {
        val standard = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        // Coocaa's launcher (Skyworth 9H89_G91 and 9S60_G60, Android 8) declares only private
        // categories such as android.intent.category.HOME.CC; the firmware starts it by component
        // (issues #69, #73). Coocaa 8R134_P6E (RTD2885N, Android 11) uses
        // smartdevice.intent.category.LITE_HOME instead (issue #122). Every MAIN activity is
        // listed so their filters can be read.
        val vendor = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN), PackageManager.GET_RESOLVED_FILTER)
                .filter { ri -> ri.filter?.categoriesIterator()?.asSequence()?.any(::isVendorHomeCategory) == true }
        }.getOrDefault(emptyList())
        // The TV player is where inputs play, even where it also declares a Home filter: TCL
        // 85G60E (Android 11) lists com.tcl.tv as a Home next to com.tcl.cyberui, and every input
        // switch, from Aerial or the remote, was taken back at once (issue #223). It stays one
        // only where it is the Home the system resolves.
        val resolved = resolvedHome()
        val homes = (standard + vendor)
            // Negative priority entries are fallbacks shown only while nothing else can be Home,
            // except the ones a firmware does start.
            .filter { it.activityInfo.packageName != packageName }
            .filter { it.priority >= 0 || it.activityInfo.name == LOW_PRIORITY_HOMES[it.activityInfo.packageName] }
            .filter { it.activityInfo.packageName !in INPUT_PLAYERS || it.activityInfo.packageName == resolved }
            .filter { it.activityInfo.packageName !in NOT_LAUNCHERS }
            .groupBy({ it.activityInfo.packageName }, { it.activityInfo.name })
            .mapValues { it.value.toSet() }
        // Some launchers register Home on a trampoline that starts the screen actually shown, which
        // declares no Home filter itself: LeTV's ExternalJumpActivity opens ui.activity.Launcher
        // (LeTV Zero65 Pro, eUI 8.2, Android 6; reported with a local build in issue #121).
        stockHomes = homes.mapValues { (pkg, classes) ->
            classes + HOME_SCREENS_BEHIND_TRAMPOLINES[pkg].orEmpty().filter { isActivity(pkg, it) }
        }
        // Added to what the package's filters gave, never in place of it: Tianci's movieplatform
        // declares HOME.CC on another activity than the one the firmware shows.
        val unfiltered = FIRMWARE_HOMES_WITHOUT_FILTER.mapValues { (pkg, classes) ->
            classes.filter { isActivity(pkg, it) }.toSet()
        }.filterValues { it.isNotEmpty() }
        stockHomes = (stockHomes.keys + unfiltered.keys).associateWith {
            stockHomes[it].orEmpty() + unfiltered[it].orEmpty()
        }
    }

    /** Android's own SECONDARY_HOME is for other displays, and comes with CATEGORY_HOME anyway. */
    private fun isVendorHomeCategory(category: String): Boolean =
        category.startsWith(VENDOR_HOME_PREFIX) ||
            (category.endsWith(VENDOR_HOME_SUFFIX) && category != "android.intent.category.SECONDARY_HOME")

    /**
     * Brings Aerial's one task to the front. No CATEGORY_HOME: on firmwares where Aerial is not the
     * system Home, that category made the system open a second, home-type task with a fresh
     * MainActivity every time, which lost the focused tile (Xiaomi MiTV-MFTR0, 1.11).
     */
    /** When goHome last started Aerial. */
    private var broughtBackAt = Long.MIN_VALUE / 2

    /** The stock launcher's home screen came up just after Aerial was brought back; is it still there? */
    private val stockHomeRecheck: Runnable = Runnable {
        // Whether Aerial is in front, from Aerial itself: the window changes of the two starts can
        // arrive in either order, and Aerial's came last with PatchWall on top (MiTV-MFTR0).
        if (aerialResumed || SystemClock.uptimeMillis() < allowStockUntil) return@Runnable
        val since = SystemClock.uptimeMillis() - broughtBackAt
        if (since < 1_000) return@Runnable
        // Unless the viewer has opened an app since.
        val front = lastWindow?.first
        if (front != null && front != packageName && front !in stockHomes) return@Runnable
        // Up to Android 11, a Home the system handled itself holds app starts back for 5 s, and the
        // start is still to come (TCL MT5879): look once more after that rather than start twice.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && aerialResumedAt < broughtBackAt && since < APP_SWITCH_HOLD_MS) {
            handler.postDelayed(stockHomeRecheck, APP_SWITCH_HOLD_MS - since)
            return@Runnable
        }
        Log.i(TAG, "the stock launcher came back just after Aerial did; bringing Aerial back again")
        goHome()
    }

    /**
     * Xiaomi from Android 12: Aerial in front filters no keys either, and Home there is answered
     * when Aerial pauses for it. The filter took Home before the system saw it, so PatchWall never
     * came up (1.42), but Xiaomi then made no repeats for a held volume key: the filter drops a key
     * the firmware has already acted on, and repeats are made only after it (MiTV-MFTR0, Android 14,
     * issue #632). Unfiltered, Home starts PatchWall and pauses Aerial 30-80 ms after the key comes
     * up, before PatchWall's task is put over Aerial's; starting Aerial again right then kept
     * PatchWall from the screen in five presses out of five, and Aerial was back in 120-290 ms.
     * Waiting for PatchWall's window instead showed it for 220-370 ms. A timer after the "homekey"
     * broadcast is no good: one that fires before PatchWall starts leaves PatchWall on top.
     * An overlay hiding the bounce is no good either: Xiaomi force-stopped Aerial whenever one was
     * up as PatchWall came. Up to Android 11 Home holds app starts back for five seconds, so the
     * filter stays there (MiTV-MFTP0, issue #460).
     */
    private val backOnPause by lazy {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.R &&
            runCatching { packageManager.getPackageInfo("com.mitv.tvhome", 0) }.isSuccess
    }

    /** When a "homekey" broadcast came with Aerial in front; see backOnPause. */
    private var homeInAerialAt = Long.MIN_VALUE / 2

    /** The Home before it, for a second press: the recent apps once Aerial is back. */
    private var lastHomeInAerialAt = Long.MIN_VALUE / 2
    private var recentsWhenBackAt = Long.MIN_VALUE / 2

    /**
     * Also just after Aerial was brought back for the press before, still on its way to the front:
     * a quick second press pauses it again. Pressed twice, Home opens the recent apps, as the
     * filter did here up to 1.52 (issue #335); MiTV-MFTR0 has no double press of its own, and
     * holding Home is still Xiaomi's task center. Timed by the broadcasts, which come later than
     * the keys while Aerial is on its way back: presses 120 ms apart came 350 ms apart or more.
     */
    private fun noteHomeInAerial() {
        val now = SystemClock.uptimeMillis()
        if (!aerialResumed && now - broughtBackAt > 1_000) return
        if (now - lastHomeInAerialAt < DOUBLE_HOME_BROADCAST_MS) {
            lastHomeInAerialAt = Long.MIN_VALUE / 2
            recentsWhenBackAt = now
            Log.i(TAG, "Home pressed twice; recent apps once Aerial is back")
        } else {
            lastHomeInAerialAt = now
        }
        homeInAerialAt = now
    }

    private fun goHome(woke: Boolean = false) {
        if (!GuardSetup.isUserUnlocked(this)) return
        // Starting an activity can light the screen: on OPPO K9 the TV came back on by itself a few
        // seconds into standby (issue #22). The screen-on check takes over at wake instead.
        if (getSystemService<PowerManager>()?.isInteractive == false) {
            Log.i(TAG, "screen off; leaving Home to the screen-on check")
            return
        }
        // ColorOS TV's quick standby keeps Android awake behind a black screen and shows the stock
        // launcher on the way down; starting Aerial then lit the TV again (OPPO S1 OPPOCNM950,
        // Android 9, found by a user in issue #22). Only that setting tells.
        if (runCatching { Settings.Global.getString(contentResolver, "tv_power_mode") }.getOrNull() == "QUICK") {
            Log.i(TAG, "quick standby; leaving Home alone")
            return
        }
        broughtBackAt = SystemClock.uptimeMillis()
        runCatching {
            startActivity(MainActivity.bringBackIntent(this).putExtra(MainActivity.EXTRA_WOKE, woke))
        }.onFailure { Log.w(TAG, "could not bring Aerial Home to the front", it) }
    }

    companion object {
        private const val TAG = "AerialGuard"
        private const val BOOT_WINDOW_MS = 3 * 60 * 1000L
        private const val SETTLE_MS = 1500L
        private const val WAKE_WINDOW_MS = 15_000L
        private val BOOT_RECHECKS_MS = listOf(30_000L, 45_000L)
        private const val DEAF_AFTER_MS = 20_000L
        private const val FOREGROUND_POLL_MS = 1_000L
        private const val FOREGROUND_LOOKBACK_MS = 10_000L
        /** How far back to look for the front when no window has been seen; see frontByUsage. */
        private const val UNKNOWN_FRONT_LOOKBACK_MS = 6 * 60 * 60 * 1000L
        private const val DOUBLE_HOME_MS = 400L
        /** Android's hold on app starts after Home (stopAppSwitches), with a margin. */
        private const val APP_SWITCH_HOLD_MS = 5_500L
        /** Hisense VIDAA's launcher, invisible to window changes; see foregroundCheck. */
        private const val VIDAA_LAUNCHER = "com.jamdeo.tv.vod"
        /** Actions the firmware's own task centre answers, tried in order before SystemUI's recents. */
        private val TASK_CENTRE_ACTIONS = listOf("com.dangbei.recentstask.action.SHOW_RECENTS_FOR_USER")
        private const val VOLUME_REPEAT_MAX_MS = 8_000L
        private const val HOME_PAUSE_MS = 400L
        private const val DOUBLE_HOME_BROADCAST_MS = 600L
        private val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

        @Volatile
        private var deafRetried = false

        /** VIDAA's assistant and its launcher (聚好看), on the two VIDAA builds reported (issues #7, #40). */
        private val VIDAA_PACKAGES = listOf("com.hisense.vidaaassistant", "com.jamdeo.tv.vod")
        /** The apps that play the TV's inputs (SystemProbe's TCL, Xiaomi and Skyworth routes). */
        private val INPUT_PLAYERS = setOf("com.tcl.tv", "com.xiaomi.mitv.tvplayer", "com.skyworth.tv")
        /**
         * Packages that declare Home but are no launcher to keep away. LeTV's app daemon shows its
         * DaemonActivity on the way into apps, and every app the viewer opened was taken back
         * within seconds; its children's app has a Home of its own and is opened on purpose (LeTV
         * X3-55 Pro, Android 5.0.1, issue #328).
         */
        private val NOT_LAUNCHERS = setOf("com.stv.appdaemon", "com.letv.lekids")
        /** TV services whose input switch shows the stock launcher on the way (Skyworth, #155). */
        private val SOURCE_SWITCHERS = setOf("com.tianci.tv")
        private const val VENDOR_HOME_PREFIX = "android.intent.category.HOME."
        private const val VENDOR_HOME_SUFFIX = "_HOME"

        /** Launcher package to the screens its Home trampoline opens; see refreshStockHomes. */
        private val HOME_SCREENS_BEHIND_TRAMPOLINES = mapOf(
            "com.stv.deskplatform" to listOf("com.stv.deskplatform.ui.activity.Launcher"),
            // Tencent 极光 boxes: Home is LauncherMainActivity, the screen shown is this one
            // (极光盒子 6SE Q0102, Android 10, issue #270; 7S, Android 14, #269).
            "com.ktcp.launcher" to listOf("com.ktcp.launcher.activity.LauncherHomeSingleTaskActivity"),
        )

        /**
         * Launcher screens a firmware starts by component that declare no Home filter at all, by
         * package: they match neither CATEGORY_HOME nor a vendor category, so the guard would take
         * them for an app the viewer had opened. Panasonic DX01C (Skyworth-made, Tianci, Android 6)
         * starts this one from /system/pcfg's DEFAULT_HOMEPAGE, and it declares only the private
         * action com.skyworth.home.neo, which left it in front at power-on and on the Home key.
         */
        private val FIRMWARE_HOMES_WITHOUT_FILTER = mapOf(
            "com.skyworth.panasonichome" to listOf("com.skyworth.panasonichome.MainActivity"),
            // Coocaa's AI launcher, started by com.tianci.system's SLauncher on the Home key and at
            // power-on (Coocaa C10 projector 3H352_C10, Android 9, issue #487, with the log; Coocaa
            // P60, Android 11; Skyworth 7T863_N6E, Android 10, issue #106). It declares no Home.
            "com.coocaa.aigc.home" to listOf("com.coocaa.aigc.home.home.rootpage.HomeActivity"),
            // What Tianci shows as its home, named only in persist.service.homepage.cls; the
            // package's HOME.CC is on MainActivity (Skyworth 7S70_A9, Android 8, issues #106 #461).
            "com.tianci.movieplatform" to listOf("com.coocaa.homepage.vast.HomePageActivity"),
        )

        /**
         * Home activities with a negative priority that a firmware really starts, by package. Hisense
         * VIDAA_TV (MT9653, Android 11) brings up its voice launcher's HomeActivity (priority -150)
         * at Home and on wake; reported with a local build in issue #261.
         */
        private val LOW_PRIORITY_HOMES = mapOf(
            "com.keylab.speech.view.vidaa" to "com.ju.secondscreen.HomeActivity",
        )

        @Volatile
        private var instance: HomeGuardService? = null

        val isRunning: Boolean get() = instance != null

        /** Whether usage stats may be read (GET_USAGE_STATS, granted over ADB); see foregroundCheck. */
        fun usageStatsAllowed(context: Context): Boolean = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService<AppOpsManager>()?.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        fun refreshCecHome() { instance?.run { watchCecInput(); updateKeyFilter() } }

        /** Looks once more at boot completion; false if the guard is not running. */
        fun onBootCompleted(): Boolean {
            val s = instance ?: return false
            s.handler.post {
                // Aerial already in front: the take-over at bind time worked, and has opened the
                // input the TV starts on if there is one.
                if (s.lastWindow?.first == s.packageName) return@post
                if (s.getSystemService<PowerManager>()?.isInteractive == true) {
                    s.takeOverOnceSettled("boot completed", woke = true)
                    s.scheduleBootRechecks()
                }
            }
            return true
        }

        /** The stock launchers the guard keeps away from the front, or null while it is not running. */
        fun stockHomes(): Map<String, Set<String>>? = instance?.stockHomes

        /** True if [packageName] is a stock launcher the guard keeps away from the front. */
        fun isStockHome(packageName: String): Boolean = instance?.stockHomes?.containsKey(packageName) == true

        /**
         * Opens the stock launcher [pkg] on purpose (its tile in Aerial's grid). While Home still
         * resolves to it, through the system Home action, as the remote's Home key would: Xiaomi's
         * launcher activities require a signature permission (com.mitv.tvhome.permission.HOME_STATE),
         * and since 1.19 tried a direct start first, users of other Xiaomi models report the tile
         * no longer opens PatchWall or its VIP content (not reproduced on MiTV-MFTR0, where the
         * direct start throws and Home was used all along). Otherwise [startDirectly]
         * first: Hisense VIDAA hands the Home action to Aerial once persist.vt.use_home_pkg points
         * at it (issue #87), as does any firmware where Aerial is the default Home.
         * Returns false if neither opened it.
         */
        fun openStockLauncher(pkg: String, startDirectly: () -> Boolean): Boolean {
            val s = instance ?: return startDirectly()
            allowStockLauncher()
            if (s.resolvedHome() == pkg && s.performGlobalAction(GLOBAL_ACTION_HOME)) return true
            return startDirectly() || s.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        /** Lets the stock launcher stay in front until Aerial is back: the viewer opened it on purpose. */
        fun allowStockLauncher() {
            instance?.allowStockUntil = SystemClock.uptimeMillis() + 60 * 60 * 1000L
        }

        /** Whether Aerial's Home is resumed; set by MainActivity, in this same process. */
        @Volatile
        private var aerialResumed = false

        /** When Aerial last came to the front, in uptime. */
        @Volatile
        private var aerialResumedAt = Long.MIN_VALUE / 2

        /** Called when Aerial comes back to the front: the stock launcher is fair game again. */
        fun onAerialResumed() {
            aerialResumed = true
            aerialResumedAt = SystemClock.uptimeMillis()
            val s = instance ?: return
            // Back in Aerial: an installer opened from Control Center was left without updating.
            GuardSetup.installerJustClosed(s)
            s.allowStockUntil = 0L
            s.updateKeyFilter()
            if (SystemClock.uptimeMillis() - s.recentsWhenBackAt < 2_000) {
                s.recentsWhenBackAt = Long.MIN_VALUE / 2
                s.openRecents()
            }
        }

        /** Called when Aerial leaves the front: keys are no longer filtered from Android 12 on. */
        fun onAerialPaused() {
            aerialResumed = false
            val s = instance ?: return
            s.updateKeyFilter()
            // Within the pause Home itself makes, not one the viewer makes after it by opening an app.
            if (s.backOnPause && SystemClock.uptimeMillis() - s.homeInAerialAt < HOME_PAUSE_MS) {
                s.homeInAerialAt = Long.MIN_VALUE / 2
                Log.i(TAG, "Aerial paused for Home; bringing it back before the stock launcher shows")
                s.goHome()
            }
        }
    }
}
