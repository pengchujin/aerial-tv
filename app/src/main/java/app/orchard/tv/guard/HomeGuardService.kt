package app.orchard.tv.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
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
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.orchard.tv.MainActivity
import app.orchard.tv.dream.AerialDream
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.BootOpen
import app.orchard.tv.system.CecSwitch
import app.orchard.tv.system.CoocaaHome
import app.orchard.tv.system.DeviceProfile
import app.orchard.tv.system.DiagLog
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

    /**
     * Fallback Home activities (negative priority, kept out of stockHomes): the setup wizard's and
     * Settings' FallbackHome, which show only for a moment at boot. One seen last says nothing about
     * what is in front now: on Tencent 极光 BA101 (Android 14) the guard last saw
     * com.android.provision at boot, and the launcher after it raised no window change, so both
     * boot rechecks left the launcher in front (issue #701, the reporter's 1.61 log).
     */
    private var fallbackHomes: Set<Pair<String, String>> = emptySet()


    /**
     * When the guard itself pressed Home to open the stock launcher from its tile. The system sends
     * "homekey" for that press as for one on the remote; taken for the viewer's, it brought Aerial
     * back at once (MiTV-MFFU0, Android 14, issue #680).
     */
    private var ownHomeAt = Long.MIN_VALUE / 2

    /** The last window that came to the front, as package to class; null until one does. */
    private var lastWindow: Pair<String, String>? = null

    private val handler = Handler(Looper.getMainLooper())

    /** When the screen last lit, for take-overs that are really a wake (issue #111). */
    private var screenOnAt = Long.MIN_VALUE / 2

    /** When BOOT_COMPLETED arrived, for boot-only steps a slow boot outlasts; see inBootWindow. */
    private var bootCompletedAt = 0L

    private var takeOverReason = ""
    private var takeOverOnWake = false
    // On a wake, also ask usage stats whether a stock home silently covered Aerial: Tencent's Aurora
    // box 4 Pro (A4111, Android 11) puts com.ktcp.launcher in front while it goes to sleep, with no
    // window change, so the last window seen was still Aerial's and the launcher stayed after the
    // wake (issue #928, the reporter's log). coveredAerial needs Aerial's Home not resumed and a
    // stock home as the newest move to the front.
    private val takeOver = Runnable {
        patchwallEarlyTakeOver = false
        takeOverIfStockHomeInFront(takeOverReason, takeOverOnWake, coveredSilently = takeOverOnWake)
    }

    /** The take-over onCreate started on PatchWall is still settling; see onServiceConnected. */
    private var patchwallEarlyTakeOver = false

    /**
     * A profile's [DeviceProfile.bootStockMs]: at power-on the stock launcher is left in front
     * until it has shown for that long (or, if it never says it showed, for [BOOT_STOCK_CAP_MS]).
     */
    private var bootStockHolding = false
    private var bootStockShown = false
    private val endBootStockHold = Runnable {
        if (!bootStockHolding) return@Runnable
        bootStockHolding = false
        endStockVisit("the boot hold is over")
        DiagLog.i("guard: stock launcher left on screen at boot (${if (bootStockShown) "it showed" else "it never said so"}); taking Home")
        takeOverOnceSettled("after the stock launcher showed at boot", woke = true)
    }

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
        takeOverIfStockHomeInFront("looking again after boot", woke = false, coveredSilently = true)
    }

    // PatchWall's non-HOME boot cover also appears on MiTV-AZFP0 / Android 9.
    // Watch only during boot/wake; old firmware's existing continuous polling is unchanged.
    private var patchwallBootCover = false

    /**
     * When the guard was bound with the screen already on, outside the windows: a process revived
     * after standby may have missed SCREEN_ON. Only the poll window reads it; screenOnAt (which
     * decides whether a take-over counts as a wake) is left to the screen-on receiver.
     */
    private var boundAwakeAt = Long.MIN_VALUE / 2

    private fun recoveryWindow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now < BOOT_WINDOW_MS || now - screenOnAt in 0 until WAKE_WINDOW_MS ||
            now - boundAwakeAt in 0 until WAKE_WINDOW_MS
    }

    /**
     * Still in the boot window, counted from BOOT_COMPLETED as well as from power-on. A firmware
     * that spends minutes in dexopt has AMS ready long after [BOOT_WINDOW_MS] of uptime, and the
     * input it then opens, seconds before BOOT_COMPLETED, would be outside the window: the guard
     * pulled Aerial over it (JMGO S60, Android 5.1, 2026-11-07: AMS ready at 205 s, input taken
     * over 8 s later).
     */
    private fun inBootWindow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now < BOOT_WINDOW_MS || (bootCompletedAt != 0L && now - bootCompletedAt < BOOT_WINDOW_MS)
    }

    private fun isPatchwallHomeCover(pkg: String, cls: String): Boolean =
        pkg == PATCHWALL && cls == PATCHWALL_BOOT && recoveryWindow()

    private fun continuousForegroundCheck(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.M || INVISIBLE_LAUNCHERS.any { it in stockHomes }

    private fun restartForegroundCheck() {
        handler.removeCallbacks(foregroundCheck)
        if (!continuousForegroundCheck() && !(patchwallBootCover && recoveryWindow())) return
        if (usageStatsAllowed(this)) handler.post(foregroundCheck)
        else Log.i(TAG, "usage stats not allowed; not watching the front by them")
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
            if (!continuousForegroundCheck() && (!patchwallBootCover || !recoveryWindow() || screenOff)) return
            handler.postDelayed(this, FOREGROUND_POLL_MS)
            // Not skipped where Aerial is the default Home, for the same reason as the Home key:
            // Panasonic DX01C (Skyworth-made, Tianci, Android 6) shows its own launcher at power-on
            // and on the Home key whatever Home resolves to.
            if (screenOff || !GuardSetup.isUserUnlocked(this@HomeGuardService)) return
            if (SystemClock.uptimeMillis() < allowStockUntil) return
            // Off the main thread: Binder to usage stats took over 5 s in the first minutes after a
            // cold boot on MiTV-ANSM0 (Android 6, 1 GB), the remote went dead on the ANR and the
            // guard died with the process (five times, one stack, issue #1284).
            if (foregroundQueried) return
            foregroundQueried = true
            usageWorker.post {
                val front = foreground()
                handler.post {
                    foregroundQueried = false
                    if (front != null && isRunning) answerForeground(front.first, front.second)
                }
            }
        }

        private fun answerForeground(window: Pair<String, String>, at: Long) {
            if (screenOff || SystemClock.uptimeMillis() < allowStockUntil) return
            if (at <= foregroundTakenAt) return
            if (stockHomes.isEmpty()) refreshStockHomes()
            // Its home screen only, as for window changes: PatchWall's children's mode, opened
            // from its tile, is in the same package and stays.
            val (pkg, cls) = window
            val bootCover = isPatchwallHomeCover(pkg, cls)
            if (cls !in stockHomes[pkg].orEmpty() && !bootCover) return
            foregroundTakenAt = at
            if (SystemClock.uptimeMillis() - broughtBackAt < 1_000) return
            // PatchWall's boot screen comes up seconds after what the TV starts on was opened (DTV,
            // 5 s later, MiTV4A, Android 6.0.1, issue #963's log): back to that, not Aerial's Home.
            // Not keyed to BootActivity: its home activity can come first (see bootTargetCovered).
            if (bootTargetCovered()) {
                Log.i(TAG, "stock launcher's screen over what the TV starts on; opening that again")
                // Which screen usage stats gave, for the next report from a TV without logcat.
                DiagLog.i("guard: $pkg/$cls over what the TV starts on; opening that again")
                goHome(reopenBoot = true)
                return
            }
            Log.i(TAG, "stock launcher $pkg in front by usage stats; bringing Aerial back")
            val woke = SystemClock.elapsedRealtime() - screenOnAt < WAKE_WINDOW_MS
            if (!woke && homeKeyReachesAerial && isDefaultHome() && pressHomeForStock()) {
                broughtBackAt = SystemClock.uptimeMillis()
                handler.removeCallbacks(homeKeyCheck)
                handler.postDelayed(homeKeyCheck, HOME_KEY_CHECK_MS)
                return
            }
            goHome(woke)
        }
    }

    private var foregroundQueried = false
    private val usageThread = lazy { android.os.HandlerThread("AerialGuardUsage").apply { start() } }
    private val usageWorker by lazy { Handler(usageThread.value.looper) }

    /**
     * Where Aerial is the default Home, the guard answers the stock launcher with a Home press of
     * its own, which the system answers with Aerial. Mi Home's remote starts PatchWall by component
     * from the system, and an activity start from here was then held back as an app switch for
     * about 5 s ("Activity start request from 10028 stopped", MiTV4 pulpfiction, Android 6.0.1,
     * the reporter's log in issue #933). Where that press did not bring Aerial (a firmware that
     * answers Home with its own launcher, as Panasonic DX01C does), the guard starts Aerial as
     * before, and from then on in this process.
     */
    private var homeKeyReachesAerial = true
    private val homeKeyCheck = Runnable {
        if (aerialResumed) return@Runnable
        Log.i(TAG, "a Home press did not bring Aerial back; starting it instead from now on")
        homeKeyReachesAerial = false
        goHome()
    }

    /** Whether usage stats, where Aerial may read them, show a stock launcher in front now. */
    private fun stockLauncherByUsageStats(): Boolean {
        if (!usageStatsAllowed(this)) return false
        val pkg = foreground()?.first?.first ?: return false
        if (stockHomes.isEmpty()) refreshStockHomes()
        return pkg in stockHomes
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
        val front = usageFront(UNKNOWN_FRONT_LOOKBACK_MS)?.takeIf { !it.closed } ?: return null
        return (front.pkg to front.cls).takeIf { it.first != packageName }
    }

    /** Whether usage stats say the app in front went to the back with nothing after it (UsageFront, issue #1023). */
    /**
     * An app left in front that only usage stats named, no window change seen since the binding:
     * backed out of, it may raise none either. LeTV X4-50 (eUI, Android 6.0, issue #1023) force-
     * stops Aerial 2 s after an app opens; revived and bound again, the guard left the app (a
     * NativeActivity), whose exit then sent no window change, and the screen stayed black for
     * 10-23 s, until the deaf check's restart read usage stats again. Asked every few seconds
     * instead, for a while, until a window change comes.
     */
    private fun watchUnseenApp(pkg: String) {
        handler.removeCallbacks(unseenAppCheck)
        unseenApp = pkg
        unseenAppWindows = windowsSeen
        unseenAppUntil = SystemClock.uptimeMillis() + UNSEEN_APP_WATCH_MS
        handler.postDelayed(unseenAppCheck, UNSEEN_APP_POLL_MS)
    }

    private var unseenApp: String? = null
    private var unseenAppWindows = 0L
    private var unseenAppUntil = 0L
    private val unseenAppCheck: Runnable = object : Runnable {
        override fun run() {
            val pkg = unseenApp ?: return
            if (windowsSeen != unseenAppWindows || screenOff || SystemClock.uptimeMillis() > unseenAppUntil) {
                unseenApp = null
                return
            }
            if (nothingInFrontByUsage()) {
                unseenApp = null
                Log.i(TAG, "$pkg went to the back with no window change and nothing came up; bringing Aerial back")
                DiagLog.i("guard: $pkg left with no window change and nothing came up; bringing Aerial back")
                goHome()
                return
            }
            handler.postDelayed(this, UNSEEN_APP_POLL_MS)
        }
    }

    private fun nothingInFrontByUsage(): Boolean =
        usageStatsAllowed(this) && usageFront(UNKNOWN_FRONT_LOOKBACK_MS)?.closed == true

    private fun usageFront(lookbackMs: Long): UsageFront.Front? = runCatching {
        val now = System.currentTimeMillis()
        val events = getSystemService<UsageStatsManager>()?.queryEvents(now - lookbackMs, now) ?: return null
        val picker = UsageFront.Picker()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            picker.add(event.eventType, event.packageName, event.className, event.timeStamp)
        }
        picker.result()
    }.getOrNull()

    /**
     * The stock home that usage stats say moved to the front last, when the last window seen is
     * Aerial's yet its Home is not resumed; null otherwise. Xiaomi MiTV-MTEQ0 (Android 10) puts
     * PatchWall over Aerial's Home some 15 s into a boot with no window change, so both boot
     * rechecks found Aerial in front and left PatchWall there (issue #704: Home created and
     * paused, never shown). Only a stock home counts, so Aerial's own screensaver or settings stay;
     * only at the boot rechecks, when Aerial is not in the middle of opening.
     */
    private fun coveredAerial(window: Pair<String, String>): Pair<String, String>? {
        if (window.first != packageName || aerialResumed || !usageStatsAllowed(this)) return null
        val (front, _) = foreground(UNKNOWN_FRONT_LOOKBACK_MS) ?: return null
        if (front.second !in stockHomes[front.first].orEmpty()) return null
        Log.i(TAG, "Aerial's Home is not resumed and usage stats say ${front.first} is in front")
        return front
    }

    /**
     * Without usage stats to ask (see coveredAerial): Aerial's Home was brought back, covered with no
     * window change within moments of showing, and is still not resumed. Xiaomi MiTV-MTEQ0 (Android
     * 10) started PatchWall's BootActivity, its 15 s start-up advert, 1.6 s after the boot take-over
     * and then uncovered PatchWall; the boot rechecks found Aerial's window last and left it there
     * (issue #1047, the reporter's log). No viewer opens an app that fast from a Home just shown.
     */
    private fun aerialCoveredUnseen(window: Pair<String, String>): Boolean {
        if (window.first != packageName || !window.second.endsWith(".MainActivity") || aerialResumed) return false
        // Left for an app Aerial opened itself, such as what the TV starts on: that app is meant to
        // cover Home at once. MiTV-MFTR0 (Android 14) woke with Aerial's process gone; the revived
        // guard brought Home back, Home opened the boot app 0.3 s later, and the guard's recheck took
        // the app for a cover and pulled Aerial over it 1.7 s after (PR #1366's test, 2026-11-08).
        if (MainActivity.leftForAnApp) return false
        if (aerialResumedAt < broughtBackAt || aerialPausedAt - aerialResumedAt !in 0 until COVERED_UNSEEN_MS) return false
        Log.i(TAG, "Aerial's Home was covered ${aerialPausedAt - aerialResumedAt} ms after it showed, with no window change")
        return true
    }

    private fun scheduleBootRechecks() {
        handler.removeCallbacks(bootRecheck)
        BOOT_RECHECKS_MS.forEach { handler.postDelayed(bootRecheck, it) }
    }

    /** Whether any window change has reached this binding. */
    private var sawEvent = false

    /**
     * Whether another enabled service filters keys without being marked default (flagDefault), up
     * to Android 6. There a key goes to one service only: AccessibilityManagerService
     * .notifyKeyEventLocked walks the bound services from the one bound last and hands the key to
     * the first that filters keys, first among the services not marked default, then among those
     * that are; a key that service lets go goes back to the system, not to the next one. Read from
     * the services.jar of Mi TV 4 (MiTV4 pulpfiction, Android 6.0.1, firmware 9.1.8.2318), where
     * Xiaomi's voice microphone service (小米语言麦克风, com.xiaomi.mitv.karaoke.service) filters
     * keys too: marked default, the guard never got a key and Home went to PatchWall (issue #81).
     * Not marked, it got every key there in 8 boots: started with the others, its process connects
     * last and so is bound last.
     */
    private val keyFilterRival by lazy { keyFilterRival() }

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
        override fun onReceive(context: Context, intent: Intent) = onUserUnlocked("user unlocked")
    }

    /** Bound while locked and the unlock not handled yet; see onUserUnlocked. */
    private var unlockPending = false
    private var unlockPolledSince = 0L

    /**
     * Not every firmware sends ACTION_USER_UNLOCKED to the guard: on Formovie spiderman (Android 9,
     * ro.crypto.state=unsupported) the guard bound while locked, Aerial's receivers then had
     * LOCKED_BOOT_COMPLETED and BOOT_COMPLETED, and the guard heard nothing more, so Home went to
     * FallbackHome for good (1.82, issue
     * #1213, the reporter's log; polling isUserUnlocked fixed it in their local build). So the
     * unlock is also looked for every 2 s, for 10 min, and taken from BOOT_COMPLETED.
     */
    private val unlockPoll = object : Runnable {
        override fun run() {
            if (!unlockPending) return
            if (GuardSetup.isUserUnlocked(this@HomeGuardService)) onUserUnlocked("user unlocked (polled)")
            else if (SystemClock.elapsedRealtime() - unlockPolledSince < UNLOCK_POLL_FOR_MS) handler.postDelayed(this, UNLOCK_POLL_MS)
        }
    }

    private fun onUserUnlocked(how: String) {
        if (!unlockPending) return
        unlockPending = false
        handler.removeCallbacks(unlockPoll)
        // Before unlock only Direct Boot aware launchers resolve, and only Direct Boot aware
        // activities are found: PatchWall's home screen was cached as missing while locked, so
        // FIRMWARE_HOMES_WITHOUT_FILTER lost it until Aerial restarted (MiTV-MOOQ0, Android 11).
        activityCache.clear()
        refreshStockHomes()
        KidsWatch.start(this@HomeGuardService)
        checkMouseWithFilter(this@HomeGuardService)
        restartForegroundCheck()
        watchCecInput()
        updateKeyFilter()
        // The ADB and screensaver checks were skipped while locked.
        GuardSetup.ensureEnabled(this@HomeGuardService)
        // Not bounded by the boot window: the unlock is where a boot ends for apps, however late
        // it comes. XGIMI Play 6 (GMUI, Android 11, low RAM) unlocked 5 min 35 s into a boot,
        // and the guard, bound since Direct Boot, left the firmware's Home chooser on screen
        // (issue #1126, the reporter's timeline). The take-over still looks at the front first.
        val skipReason = when {
            getSystemService<PowerManager>()?.isInteractive != true -> "screen off"
            else -> null
        }
        DiagLog.i("guard: $how; running what was put off while locked")
        if (skipReason == null) takeOverOnceSettled(how, woke = true)
        else Log.i(TAG, "$how; not taking over Home ($skipReason)")
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            updateKeyFilter()
            if (screenOff) {
                // Credential storage, like the Home note below: not while locked.
                if (GuardSetup.isUserUnlocked(this@HomeGuardService)) BootOpen.noteScreenOff(this@HomeGuardService)
                if (GuardSetup.offInStandby(this@HomeGuardService)) GuardSetup.stepOutForStandby(this@HomeGuardService)
                return
            }
            // Firmwares put their own launcher back in front while the TV is in standby, and no
            // window change follows on wake. Look once things have settled after the screen lights.
            screenOnAt = SystemClock.elapsedRealtime()
            restartForegroundCheck()
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
            // The guard's own press for the stock launcher's tile, not the viewer's.
            if (SystemClock.uptimeMillis() - ownHomeAt < OWN_HOME_MS) return
            // On disk as well: in the boot window, answering Home here can end with accessibility
            // restarted (frontAfterHome), and the guard that starts then has to know of it too.
            if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && GuardSetup.isUserUnlocked(this@HomeGuardService)) {
                BootOpen.noteHomeKey(this@HomeGuardService)
            }
            if (backOnPause) noteHomeInAerial()
            val front = lastWindow?.first
            if (front != null && front !in stockHomes) {
                windowsAtHome = windowsSeen
                homePressedAt = SystemClock.uptimeMillis()
                homeFrom = front
                handler.removeCallbacks(frontAfterHome)
                handler.removeCallbacks(frontAfterHomeAgain)
                val quiet = deafAfterHome || front in quietHomeApps(this@HomeGuardService)
                handler.postDelayed(frontAfterHome, if (quiet) DEAF_LOOK_MS else FRONT_AFTER_HOME_MS)
                return
            }
            // The stock launcher's window change usually comes first and already brought Aerial
            // back; starting it again redid Home's resume (MiTV-MFTR0: 0.2 s apart).
            if (SystemClock.uptimeMillis() - broughtBackAt < 1_000) return
            // Nothing seen yet: the stock launcher's window change, a moment away, brings Aerial
            // back before it is drawn. Started at once, Aerial lost to the launcher the firmware
            // started by component 3 ms later, which then drew in full for 1.1 s (NVIDIA SHIELD
            // TV, Android 11, the first Home after a boot, issue #1380). If none comes, Aerial anyway.
            if (front == null) {
                handler.removeCallbacks(unknownFrontHome)
                handler.postDelayed(unknownFrontHome, UNKNOWN_FRONT_HOME_MS)
                return
            }
            Log.i(TAG, "Home pressed with $front in front; bringing Aerial back")
            endStockVisit("Home pressed")
            goHome()
        }
    }

    private val unknownFrontHome = Runnable {
        if (SystemClock.uptimeMillis() - broughtBackAt < 1_000 || aerialResumed) return@Runnable
        Log.i(TAG, "Home pressed with an unknown app in front and nothing came up; bringing Aerial back")
        endStockVisit("Home pressed")
        goHome()
    }

    private var windowsSeen = 0L
    private var windowsAtHome = 0L
    private var homePressedAt = Long.MIN_VALUE / 2

    /**
     * Home pressed in another app, and no window change since: the guard may have gone deaf. On
     * Xiaomi Box 5 Max (MiTV-AZFU0, Android 14) it did after Xiaomi Weather or Mi Home had been
     * open, and stayed so: the last window it had seen was the app's, so Home in it left PatchWall
     * in front, and so did Home in every app after (issue #651). Usage stats still see PatchWall
     * come to the front; then Aerial comes back, and accessibility is switched off and on once per
     * process, which is what brought the events back there (Aerial started by hand did too).
     * Where that did not bring them back, each Home after it waited the full 1.5 s with PatchWall
     * on screen (1.61, the reporter's log: 1.98 s every time). Once deaf, usage stats are read
     * every DEAF_LOOK_MS from the press on instead, until a window change is seen again.
     */
    private val frontAfterHome: Runnable = Runnable {
        // XGIMI (Android 9, m7642) answers Home with its own Home chooser when set-home-activity did
        // not take; it was never drawn and the remote went dead over it (issue #44). Only right
        // after Home: the same chooser also serves apps' "open with".
        lastWindow?.takeIf { isHomeChooser(it.first, it.second) }?.let {
            Log.i(TAG, "Home pressed and the Home chooser (${it.first}) came up; bringing Aerial back")
            goHome()
            return@Runnable
        }
        if (windowsSeen != windowsAtHome) return@Runnable
        val front = frontByUsage()
        if (front == null || (front.first !in stockHomes && !isFallbackHomeScreen(front.first, front.second))) {
            // Usage stats may not have the stock launcher yet: look again until the usual wait is up.
            val quiet = deafAfterHome || homeFrom in quietHomeApps(this)
            if (quiet && SystemClock.uptimeMillis() - homePressedAt < FRONT_AFTER_HOME_MS) {
                handler.postDelayed(frontAfterHomeAgain, DEAF_LOOK_MS)
            }
            return@Runnable
        }
        Log.i(TAG, "Home pressed, no window change since, and usage stats say ${front.first} is in front; bringing Aerial back")
        lastWindow = front
        endStockVisit("Home pressed, seen by usage stats")
        goHome()
        homeFrom?.let { noteQuietHomeApp(this, it) }
        deafAfterHome = true
        if (!deafAfterHomeRestarted) {
            deafAfterHomeRestarted = true
            Log.i(TAG, "no window change for that; switching accessibility off and on")
            GuardSetup.restartAccessibility(this)
        }
    }
    private val frontAfterHomeAgain: Runnable = Runnable { frontAfterHome.run() }

    /** Xiaomi's UI boost, where Aerial answers Home in other apps itself; see uiBoostChanged. */
    private fun watchUiBoost() {
        if (!backOnPause || uiBoostWatched || Settings.Global.getString(contentResolver, UI_BOOST) == null) return
        uiBoostWatched = true
        contentResolver.registerContentObserver(Settings.Global.getUriFor(UI_BOOST), false, uiBoostObserver)
    }

    private var uiBoostWatched = false

    private val uiBoostObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = uiBoostChanged()
        }
    }

    /**
     * Home in another app on Xiaomi from Android 12 (backOnPause): PatchWall showed for some 300 ms
     * before Aerial came back (MiTV-MFFU1, Android 14, Home in Quark: seven frames of it in a
     * screen recording). PatchWall's window change, which the guard answers, comes once it has
     * been drawn: 290 ms after the key there, and PatchWall is drawn at about 220 ms. The
     * "homekey" broadcast is no help: it is held back from Aerial while Aerial is in the back,
     * and comes only once it is in front again (363 ms).
     *
     * Xiaomi's UI boost (TVQSService in services.jar) sets [UI_BOOST] as an activity switch
     * starts, 45-65 ms after the key. From then usage stats are read until they have PatchWall's
     * home screen just come to the front, and Aerial then starts over it, as Home in Aerial is
     * answered on onPause. They lag: PatchWall was there 116-279 ms after the key on MiTV-MFFU1,
     * and 199-434 ms with 云视听小电视 in front on MiTV-MFTR0 (Android 14), which draws PatchWall
     * later. Another app moving to the front ends the look, as a switch within an app does.
     *
     * Not with accessibility's window changes as a second signal: asking for them holds the
     * window change of PatchWall back until the window list has caught up, and on MiTV-MFTR0 the
     * fallback below came at 336 ms instead of 177-277 ms, after PatchWall was on the screen. Not
     * by the last window seen either: an app brought back to its task sends none (issue #475), so
     * the guard still had Aerial in front after 云视听极光 was opened again from it. Boot and wake
     * keep their own handling of PatchWall, as does the stock launcher's tile.
     */
    private fun uiBoostChanged() {
        if (!stockHomeMayCome()) return
        stockHomeLookFrom = System.currentTimeMillis()
        stockHomeLookUntil = SystemClock.uptimeMillis() + STOCK_HOME_LOOK_FOR_MS
        handler.removeCallbacks(stockHomeLook)
        stockHomeLook.run()
    }

    private var stockHomeLookFrom = 0L
    private var stockHomeLookUntil = Long.MIN_VALUE / 2

    private val stockHomeLook: Runnable = object : Runnable {
        override fun run() {
            if (!stockHomeMayCome()) return
            val front = usageFront(STOCK_HOME_FRONT_MS)
            if (front != null && !front.closed && front.at >= stockHomeLookFrom - STOCK_HOME_FRONT_MS) {
                if (stockHomes[front.pkg]?.contains(front.cls) == true) {
                    Log.i(TAG, "usage stats have ${front.pkg} just come to the front; bringing Aerial back")
                    lastWindow = front.pkg to front.cls
                    goHome()
                    return
                }
                // Something else came to the front since the boost started: not Home.
                if (front.at >= stockHomeLookFrom - STOCK_HOME_SLACK_MS) return
            }
            if (SystemClock.uptimeMillis() < stockHomeLookUntil) handler.postDelayed(this, STOCK_HOME_LOOK_MS)
        }
    }

    /** Aerial's Home not in front, so Home may leave something for PatchWall; see uiBoostChanged. */
    private fun stockHomeMayCome(): Boolean {
        if (!backOnPause || aerialResumed || bootStockHolding) return false
        val now = SystemClock.uptimeMillis()
        if (now < allowStockUntil || now - broughtBackAt < 1_000) return false
        val sinceBoot = SystemClock.elapsedRealtime()
        if (sinceBoot < BOOT_WINDOW_MS || sinceBoot - screenOnAt < WAKE_WINDOW_MS) return false
        return usageStatsAllowed(this)
    }

    /** The app Home was last pressed in; see frontAfterHome. */
    private var homeFrom: String? = null

    /** The firmware putting its own screensaver back while Aerial's is chosen (GuardSetup). */
    private val screensaverObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = GuardSetup.screensaverSettingChanged(this@HomeGuardService)
        }
    }

    private val adbObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (GuardSetup.adbSwitchedOffInUse(this@HomeGuardService, SystemClock.elapsedRealtime() - screenOnAt)) return
                GuardSetup.ensureAdb(this@HomeGuardService)
            }
        }
    }

    /**
     * At power-on the guard's process starts well before Android connects the service: on Xiaomi
     * MiTV-MFTR0 (Android 14) 4.7 s and 5 s in two boots, with PatchWall on screen meanwhile
     * (11-13 s from the screen lighting to Aerial). Within the boot window the take-over starts
     * here; it treats the front as unknown, as onServiceConnected does when nothing has been seen.
     * With something chosen to open at power-on, which goes over whatever is in front anyway; or
     * on PatchWall while usage stats show its home itself in front ([patchwallHomeInFront]).
     * Otherwise an input PatchWall plays at power-on (the last one used) or an HDMI-CEC device
     * switched to would be pulled out of, which only the window changes after the connection can
     * tell (see takeOverOnceSettled). Not after a wake either, for the same reason.
     */
    override fun onCreate() {
        super.onCreate()
        if (SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS || !GuardSetup.isUserUnlocked(this)) return
        if (getSystemService<PowerManager>()?.isInteractive != true) return
        val bootSource = hasBootSource()
        val patchwall = !bootSource && patchwallHomeInFront()
        if (!bootSource && !patchwall) return
        Log.i(TAG, "started shortly after boot; taking over before the service is connected")
        takeOverOnceSettled("started shortly after boot", woke = true)
        patchwallEarlyTakeOver = patchwall
    }

    /**
     * PatchWall's home activity is what usage stats last saw come to the front. MiTV-MFTR0
     * (Android 14) lets third-party processes start 4.6 s after the screen lights, and the service
     * was connected 3.9 s and, in another boot, 9.7 s after that (two boots on 2026-11-03: 12.3 s
     * and 19 s of PatchWall). An input PatchWall plays at power-on, or one a CEC device switches
     * to, is its ExternalSourceActivity there, so it is left alone here; the take-over looks again
     * once settled, and an input opened after that comes up over Aerial.
     */
    private fun patchwallHomeInFront(): Boolean {
        if (!usageStatsAllowed(this)) return false
        val (pkg, cls) = frontByUsage() ?: return false
        if (pkg != PATCHWALL) return false
        // Read here as well: the take-over needs them, and the connection may come after it.
        refreshStockHomes()
        return cls in stockHomes[PATCHWALL].orEmpty()
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
        unlockPending = !GuardSetup.isUserUnlocked(this)
        // Both read credential storage: bound before unlock, the guard crashed here twice and the
        // system put off its restart for 30 min, so nothing took Home at boot (1.79 to 1.81: Redmi
        // Projector 4 mt5873, Android 13, issue #684; Formovie spiderman, Android 9, issue #1213;
        // Haier HRA962X2, Android 9, issue #1205; all from the reporters' crash logs). The unlock
        // receiver starts them instead.
        if (GuardSetup.isUserUnlocked(this)) {
            KidsWatch.start(this)
            BootOpen.noteStartedInStandby(this)
        }
        checkMouseWithFilter(this)
        watchUiBoost()
        CecSwitch.register(this)
        // The profile is in credential storage: only once unlocked.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && GuardSetup.isUserUnlocked(this) &&
            DeviceProfile.current(this).bootStockMs > 0
        ) {
            bootStockHolding = true
            // An invisible PatchWall never says it showed, so the cap is the hold there: the 20 s
            // cap cut a longer profile short (MiTV4-ANSM0, Android 6: MiIO binds about 29 s after
            // PatchWall comes up, the reporter's measurement in issue #1071).
            val hold = maxOf(BOOT_STOCK_CAP_MS, DeviceProfile.current(this).bootStockMs.toLong())
            allowStockUntil = SystemClock.uptimeMillis() + hold + DeviceProfile.current(this).bootStockMs
            handler.postDelayed(endBootStockHold, hold)
            DiagLog.i("guard: bound at boot; leaving the stock launcher on screen first (device profile)")
        }
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
        // Killed by the firmware in the first minutes after boot while the viewer was in an app,
        // and bound again in a new process: the boot take-over pulled Aerial over the video (OPPO
        // K9, Android 9, low RAM: com.heytap.tvapiservice stopped Aerial 4 s after an app opened,
        // the sync revival brought it back 15 s later; issue #989, the reporter's log). Aerial's
        // own record of this boot says an app was in front; usage stats, where they show the stock
        // launcher, still win.
        val appLeftInFront = GuardSetup.isUserUnlocked(this) && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && !justUpdated() &&
            GuardSetup.homeInFrontThisBoot(this) == false && !GuardSetup.stockHomeSinceHomeLeft(this) &&
            (Build.VERSION.SDK_INT < 24 || SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000) &&
            !stockLauncherByUsageStats()
        if (appLeftInFront) DiagLog.i("guard: bound again soon after boot with an app in front when Aerial died; leaving it")
        val fastBootTakeOver = !appLeftInFront && GuardSetup.isUserUnlocked(this) &&
            SystemClock.elapsedRealtime() < BOOT_WINDOW_MS &&
            getSystemService<PowerManager>()?.isInteractive == true
        if (fastBootTakeOver) {
            // Starting the wait again would undo what onCreate saved: on MiTV-MFTR0 the connection
            // came 1.3 s into it, and Aerial came a few seconds later than it could have.
            if (!patchwallEarlyTakeOver) takeOverOnceSettled("bound shortly after boot", woke = true)
            scheduleBootRechecks()
        }
        refreshStockHomes()
        registerReceiver(packageChanges, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        screenOff = getSystemService<PowerManager>()?.isInteractive == false
        // On every version: a gamepad only matters up to Android 11, but a mouse also hangs the
        // filter Android 12+ keeps on while Aerial is in front (issue #988).
        runCatching { getSystemService<InputManager>()?.registerInputDeviceListener(gamepadListener, handler) }
        lookForGamepads()
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
        // checked by the author with a local build). Tencent's 极光 launcher (com.ktcp.launcher) on
        // the Aurora box 4 Pro (A4111, Amlogic SC2, Android 11) is the same: it comes to the front
        // at boot, during standby and on Home with no window change (issue #928, the reporter's
        // logs), while usage stats record it. Only where one of these launchers is a stock home,
        // so other TVs past Android 6 are not polled.
        // On MiTV-AZFP0 (Android 9) PatchWall's BootActivity is watched the same way, but only for
        // a bounded window after boot and wake (restartForegroundCheck). A process revived after
        // standby may have missed SCREEN_ON before binding, so its window is not left open.
        patchwallBootCover = isActivity(PATCHWALL, PATCHWALL_BOOT)
        // Not only with PatchWall: MiTV-MFTR0 (Android 14) keeps Aerial's process through standby,
        // so the guard is created after the screen lights, SCREEN_ON is already gone, and nothing
        // below counts as a wake — no boot window, no revival, nothing died in front. What the TV
        // starts on then stayed shut on every wake (issue #1229, the reporter's log).
        if (!screenOff && !recoveryWindow() &&
            (patchwallBootCover || BootOpen.dueAfterMissedWake(this))
        ) {
            boundAwakeAt = SystemClock.elapsedRealtime()
        }
        restartForegroundCheck()
        // Unlocked while binding: what was skipped above at once, whether or not the broadcast follows.
        if (unlockPending && GuardSetup.isUserUnlocked(this)) onUserUnlocked("user unlocked while binding")
        if (!GuardSetup.isUserUnlocked(this)) {
            Log.i(TAG, "bound while user locked; deferred takeover until unlock")
            unlockPolledSince = SystemClock.elapsedRealtime()
            handler.postDelayed(unlockPoll, UNLOCK_POLL_MS)
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
        // Killed in the background while the viewer was in an app, and taken off the accessibility
        // list with it, so that putting it back counts as revived: with no usage stats to ask, the
        // unknown front was taken for the stock launcher, and Aerial came up over the video every
        // few minutes (XGIMI MT9660, Android 13, fongmi; issue #845, from Aerial's own log). Aerial
        // was not on screen when it died, so an app is what is in front. Also where usage stats are
        // allowed but name nothing: XGIMI Z9X (MT9660, Android 13, usage allowed) pulled Aerial
        // over TVBox the same way (issue #1068, PR #1090, checked there for an hour). Where they
        // name the stock launcher, or an app gone to the back with nothing after it (#1023), Home
        // is still taken back. So it is where the guard saw a stock home come up after Aerial's
        // Home left: Xiaomi MiTV-MTEQ0 (Android 10) going to standby starts PatchWall over Aerial
        // and stops Aerial 2 s later, and at wake PatchWall stayed (issue #1047, the reporter's log).
        if (revived && !diedInFront && !GuardSetup.wasHomeInFront(this) && !GuardSetup.stockHomeSinceHomeLeft(this) &&
            frontByUsage() == null && !nothingInFrontByUsage() &&
            SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS && !fastBootTakeOver
        ) {
            // In Aerial's own diagnostics too: on these TVs the owner often cannot read logcat.
            DiagLog.i("guard: re-enabled after being switched off, with an app in front when Aerial died; leaving it")
            return
        }
        // Stopped right after the stock launcher came up, and started again by the system's own
        // rebind rather than revived: Dangbei H3S (RK3566, Android 11) force-stops Aerial a second
        // after every Home press, after the guard had already started it again from Dangbei's
        // launcher, and binds the guard again 23 s later; nothing was taken over then, so Home
        // stayed on Dangbei's launcher for good (1.84, issue #1276, the reporter's log and exit
        // info). The take-over still looks at the front first.
        val stoppedOverStock = Build.VERSION.SDK_INT >= 24 && SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS &&
            SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000 &&
            GuardSetup.stockHomeSinceHomeLeft(this) && GuardSetup.stockHomeSeenWithin(this, STOPPED_OVER_STOCK_MS)
        // Revived with the screen off (standby): the screen-on check takes over at wake.
        // Not gated on being the default Home: Skyworth/Coolita (8R145, Android 11) starts its own
        // launcher at boot whatever Home resolves to (issue #43). The take-over looks first, and
        // bringing Aerial back over itself changes nothing.
        // Bound with the screen already lit and SCREEN_ON missed: this binding is the wake itself.
        val wokeUnseen = boundAwakeAt > 0 && SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS && recoveryWindow()
        if (!fastBootTakeOver && !appLeftInFront &&
            (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || revived || diedInFront || stoppedOverStock || wokeUnseen) &&
            getSystemService<PowerManager>()?.isInteractive == true
        ) {
            takeOverOnceSettled(
                when {
                    revived -> "re-enabled after being switched off"
                    diedInFront -> "back after Aerial died in front"
                    stoppedOverStock -> "back after Aerial was stopped over the stock launcher"
                    wokeUnseen -> "bound after the screen lit"
                    else -> "bound shortly after boot"
                },
                // Back from standby or a boot, not from a crash: the moment to open the input
                // the owner wants the TV to start on.
                woke = (!diedInFront && !stoppedOverStock) || revived,
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

    /**
     * Whether the stock launcher came over what the TV starts on a moment after that opened, rather
     * than the viewer leaving it with Home: then the target is opened again instead of Aerial's Home.
     *
     * Which screen of the launcher it is must not matter. On MiTV4-ANSM0 (Android 6.0.1, firmware
     * 22.10.20.3352, issue #963) the input opened at 8.4 s into the boot, PatchWall's home activity
     * resumed at 30.6 s and its BootActivity started at 31.5 s; the usage-stats poll at 32.7 s still
     * had only the home activity, so a check keyed to BootActivity missed it and Aerial's Home
     * covered the input. Answered at the first poll after the launcher appears, as before.
     *
     * Not after Home: pressing it there means Aerial, and on a wake the target is opened again in
     * the same boot window, where every such press would otherwise reopen it. Kept on disk as well
     * (BootOpen.noteHomeKey): answering that Home can end with accessibility restarted and the
     * guard in a new process, which would otherwise reopen what the viewer just left.
     *
     * Also after a wake, long past the boot window, while the target is fresh: MiTV-MFTR0 (Android
     * 14) brought PatchWall to the front 1.3 s after Aerial had opened the boot app on a wake, and
     * the guard answered with Aerial's Home, so the app stayed shut (2026-11-08, on the owner's TV).
     * Only an open this recent, which nothing but a wake or a boot makes.
     */
    private fun bootTargetCovered(): Boolean =
        hasBootSource() &&
            SystemClock.uptimeMillis() - homePressedAt >= HOME_LEFT_MS &&
            !BootOpen.homeKeyWithin(this, HOME_LEFT_MS) &&
            BootOpen.openedWithin(
                this,
                if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) BOOT_TARGET_COVER_MS else WAKE_TARGET_COVER_MS,
            )

    /**
     * A preinstalled app the firmware starts by itself after a boot, over Aerial: 蓝光影院 on Haier
     * HRA920L (YunOS 5.1, Android 5.1) about a minute in; it declares no boot receiver and cannot be
     * disabled (issue #1301, the reporter's agent). Only in the boot window and before any key:
     * opened from Aerial's tile, a key comes first (filtered everywhere up to Android 11).
     */
    private fun firmwarePushedAtBoot(pkg: String): Boolean =
        pkg in FIRMWARE_BOOT_APPS && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && !keySeen

    private var keySeen = false

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    /**
     * Also when Aerial is the default Home: that only steers the HOME intent. Xiaomi MiTV-ANSP0
     * (Android 9) accepts set-home-activity, yet when standby force-stops Aerial, PatchWall's old
     * task is simply uncovered and stays in front after wake (issue #6).
     */
    private fun takeOverIfStockHomeInFront(reason: String, woke: Boolean, coveredSilently: Boolean = false) {
        if (!GuardSetup.isUserUnlocked(this)) return
        // No window seen since bound: ask usage stats what moved to the front last. Revived after
        // the firmware killed Aerial in the background, an unknown front was taken for the stock
        // launcher and Aerial was pulled over the video the viewer was watching (Toshiba
        // tsbU6800C, Android 8, and XGIMI Z6X, Android 11, issue #497, with the logs). The same at
        // the boot rechecks, on firmwares that raise no window change for the app the viewer
        // opened (Skyworth 9S52_Q4A, Android 6, issue #489). Aerial itself says nothing about what
        // it uncovered when it died, so it stays unknown.
        // Aerial's Home resumed in this process is what is in front, whether a window change said so
        // or not. XGIMI H3 (m7221, Android 8, issue #1383, the reporter's log): the firmware stopped
        // Aerial while an app was open, Back started it cold, and the guard put back on the list 9 s
        // later saw no window, took the unknown front for the stock launcher and brought Aerial back
        // over itself, which sent the focus to the first tile every time.
        val seen = lastWindow?.takeIf { it !in fallbackHomes }?.let { (if (coveredSilently) coveredAerial(it) else null) ?: it }
            ?: if (aerialResumed) packageName to MainActivity::class.java.name else null
        // The app in front was backed out of and nothing came up: a black screen with no window on
        // LeTV X4-50 (eUI, Android 6.0, issue #1023). That is the stock launcher's place.
        val empty = seen == null && nothingInFrontByUsage()
        if (empty) Log.i(TAG, "$reason; usage stats say the app in front went to the back and nothing came up")
        val last = seen ?: if (empty) null else frontByUsage()?.also { Log.i(TAG, "$reason; usage stats say ${it.first} is in front") }
        // Why nothing is known, for TVs whose logcat cannot be read (XGIMI GMUI; PR #1090).
        if (last == null && !empty) {
            DiagLog.i("guard: $reason; front unknown (${if (usageStatsAllowed(this)) "usage stats name no app in ${UNKNOWN_FRONT_LOOKBACK_MS / 3_600_000} h" else "usage stats not allowed"})")
        }
        // Unknown means we were only just bound, which happens after a standby kill. Any screen of
        // the stock launcher counts: when Aerial is killed, whatever was on top of the launcher's
        // task is uncovered, not always its home activity (PatchWall's StrategicLayerActivity).
        // Unless the firmware keeps stopping Aerial: then an unknown front is most likely the app
        // the viewer is in, and Home stays with the Home key (LeTV eUI, issue #63).
        if (last == null && !empty && GuardSetup.revivedOften(this)) {
            Log.i(TAG, "$reason, but Aerial keeps being stopped and the front is unknown; leaving it")
            return
        }
        // Brought back a moment ago and nothing seen since: what is in front is Aerial. The boot
        // take-over in onCreate and the one at the connection are a few seconds apart, and the
        // second redid Home's resume (MiTV-MFTR0).
        if (last == null && SystemClock.uptimeMillis() - broughtBackAt < 30_000) return
        // JMGO S60: with its Settings' "Startup Source" set, the TV opens that input seconds before
        // BOOT_COMPLETED. Android 5.1 does not let the guard read usage stats, so this take-over saw
        // no front and pulled Aerial over the input (2026-11-07 boot: "front unknown; bringing
        // Aerial back" right after the input). The input is what the viewer is waiting for; Home
        // still brings Aerial back, and the key rebind runs here because no Aerial activity starts.
        // inBootWindow also counts from BOOT_COMPLETED: see its comment for the dexopt-slowed boot.
        if (last == null && inBootWindow() && GuardSetup.startsOnSignalSource(this)) {
            DiagLog.i("guard: $reason; the TV starts on a signal source; leaving it")
            GuardSetup.rebindForJmgoKeys(this)
            return
        }
        // XGIMI Z9X (MT9660-class, Android 13, GMUI v6.17.12): with no HOME preferred-activity on
        // record, power-on and wake land on the firmware's own Home chooser
        // (com.xgimi.xgimiframeworkui/.resolver.ResolverActivity) and stay there: usage stats name
        // com.xgimi.xgimiframeworkui, which is no stock home, so the guard left it ("leaving it
        // there") on every take-over, and the firmware force-stopped Aerial every ~30 s during the
        // boot on top. The chooser's own "always" does not persist across reboots there, and
        // set-home-activity reports success without changing what HOME resolves to. Treat the
        // chooser like a stock launcher so the take-over covers boot and wake, the same condition
        // frontAfterHome already accepts right after a Home press (issue #44). The same chooser
        // also serves "open with", but only right after a revival or a wake, which is rarer than a
        // boot and was already accepted there. Android's own chooser likewise: on OBE C3 Ultra
        // (MT9669, Android 9, issue #1401) the firmware's FallbackHome joins the Home candidates
        // during the boot, the system drops the preferred Home for the changed set and asks, and
        // the guard left android/ResolverActivity there for good ("user unlocked with android in
        // front; leaving it there").
        // JMGO S60 (Amlogic T968, Android 5.1): its TV app declares Home, so the guard counted it as
        // the stock launcher by package alone and pulled Aerial back over the input
        // AmlogicTvActivity had just opened, leaving Aerial's Home on screen. That activity is the
        // input player, not a Home, so it never is one whatever opened it; the package's Home is its
        // MainActivity, which the package check still covers. Not timed by BootOpen: this TV sets
        // its clock from the network a while into the boot, which dates the saved open as another
        // boot and let the guard take over 34 s after the input had opened anyway.
        val jmgoPlayer = last != null && last.first == JMGO_TV &&
            last.second.endsWith(".AmlogicTvActivity")
        if (jmgoPlayer) {
            DiagLog.i("guard: $reason with JMGO's TV player in front; leaving it")
            // The firmware can open the input without Aerial's activity ever running, and then the
            // once-per-boot key rebind there would not happen (seen on this TV's boot).
            GuardSetup.rebindForJmgoKeys(this)
        }
        val stockInFront = last == null || (last.first in stockHomes && !jmgoPlayer) ||
            isHomeChooser(last.first, last.second) ||
            firmwarePushedAtBoot(last.first) ||
            (coveredSilently && aerialCoveredUnseen(last))
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
        if (!stockInFront && last != null && seen == null && !jmgoPlayer) watchUnseenApp(last.first)
        if (!stockInFront || SystemClock.uptimeMillis() < allowStockUntil) return
        // What the TV starts on was opened and then covered: it is what the viewer is waiting for,
        // not Aerial's Home. Reached at the boot rechecks and by a process started again in the boot
        // window, where usage stats still have the stock launcher's move to the front from before
        // the target opened (MiTV4-ANSM0, Android 6.0.1, issue #963's log: 38 s after the target).
        if (bootTargetCovered()) {
            Log.i(TAG, "$reason with the stock launcher over what the TV starts on; opening that again")
            val front = last?.let { "${it.first}/${it.second}" } ?: "unknown"
            DiagLog.i("guard: $reason, front $front; opening what the TV starts on again")
            goHome(reopenBoot = true)
            return
        }
        Log.i(TAG, "$reason with the stock launcher in front; bringing Aerial back")
        DiagLog.i("guard: $reason, front ${last?.first ?: "unknown"}; bringing Aerial back")
        goHome(woke)
    }

    override fun onDestroy() {
        if (usageThread.isInitialized()) usageThread.value.quitSafely()
        handler.removeCallbacks(refreshHomesAfterPackageChange)
        handler.removeCallbacks(removeBanner)
        removeBanner.run()
        runCatching { unregisterReceiver(packageChanges) }
        runCatching { unregisterReceiver(screenOn) }
        runCatching { unregisterReceiver(homeKey) }
        if (uiBoostWatched) {
            uiBoostWatched = false
            runCatching { contentResolver.unregisterContentObserver(uiBoostObserver) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { unregisterReceiver(userUnlocked) }
        }
        runCatching { getSystemService<InputManager>()?.unregisterInputDeviceListener(gamepadListener) }
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
        // Asked for by this TV's profile (issue #684): on with the screen, Aerial in front or not.
        val always = filterHomeAlways()
        // A game played with a gamepad: with the filter on, every event of the pad goes through
        // system_server's main thread and is injected again. On Xiaomi MiTV-MFTR0 (Android 11)
        // streaming with Moonlight and a pad, that thread hung in AccessibilityInputFilter's
        // nativeInjectInputEvent holding InputManagerService's lock, and the watchdog restarted the
        // TV every 5-20 minutes (issue #774, eight dropbox records). Home in that app then goes to
        // the system, and Aerial comes back after Android's 5 s hold.
        val padGame = gamepadAttached && !aerialResumed && lastWindow?.first.let { it != null && it != packageName && it !in stockHomes }
        // A mouse hangs it the same way anywhere it moves, Aerial's Home included (issue #988), so
        // on Xiaomi nothing is filtered with one connected: Home goes to the system, and the guard
        // brings Aerial back from the stock launcher. Only there: on TCL 75Q10L (MT9655) a mouse
        // never hung with the filter on, and without it Home showed the TCL launcher first
        // (issue #1039).
        // And where the system went down once with one plugged in and the filter on: TCL TC8000
        // (ay30a2, Android 11) casts every event the filter passes on to KeyEvent, and the mouse's
        // first MotionEvent threw in system_server 0.5 s after it connected (issue #1109, the
        // reporter's crash log; 1.75, which stopped filtering for any mouse, never did).
        val mouseHang = mouseAttached && (xiaomiInput || mouseDownedSystem(this))
        // A Home press taken in Aerial stays taken until its key comes up, wherever Aerial went
        // meanwhile: held, it opens the recent apps, and on TCL T966D5 (Android 14, issue #1402,
        // the reporter's 1.89 log) that is the launcher's MainActivity, so Aerial paused, the filter
        // stopped, and the lift went to the system as a Home press of its own. Its "homekey" then
        // ended the visit and Aerial came back 0.4 s after the task manager opened.
        val homeStillDown = SystemClock.uptimeMillis() - homeDownTakenAt < HOME_DOWN_KEEP_MS
        val wanted = !keysPassThrough && !screenOff && !padGame && !mouseHang &&
            (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R || aerialInFront || always || homeStillDown)
        val homeWanted = wanted && (!isDefaultHome() || defaultHomeNeedsHomeFilter)
        val cecWanted = !screenOff && !mouseHang && CecHome.active(this, lastWindow?.first)
        filteringForCecOnly = cecWanted && !homeWanted
        var want = if (homeWanted || cecWanted) info.flags or flag else info.flags and flag.inv()
        if (GuardSetup.isUserUnlocked(this) && DeviceProfile.current(this).neverFilterKeys) want = want and flag.inv()
        if (keyFilterRival) want = want and AccessibilityServiceInfo.DEFAULT.inv()
        if (want == info.flags) return
        info.flags = want
        serviceInfo = info
        Log.i(TAG, if (want and flag != 0) "filtering keys" else "stopped filtering keys")
    }

    /**
     * Whether a gamepad is connected; see updateKeyFilter. Not the joystick bit: TCL's IR receiver has it.
     * Nor a device named virtual: Sony's voice search device "virtual-search" says it is a gamepad
     * and not virtual, so keys were never filtered in other apps and the first Home there went to
     * the stock launcher (BRAVIA 4K GB, Android 9, issue #840, found by the reporter).
     */
    private var gamepadAttached = false

    /** The device counted as a gamepad, for the diagnostics. */
    private var gamepadName: String? = null

    /** Whether an external mouse is connected (Android 10+, where isExternal is public); see updateKeyFilter. */
    private var mouseAttached = false

    /**
     * Xiaomi's input stack, where events injected again by the filter hung system_server (MiTV-MFTR0,
     * Android 11: a gamepad in #774, a Bluetooth mouse in #988). Known by PatchWall or Xiaomi's
     * system UI being installed; the hang itself can't be seen before it happens.
     */
    private val xiaomiInput by lazy {
        listOf(PATCHWALL, "com.xiaomi.mitv.systemui").any { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
    }

    private fun lookForGamepads() {
        val hadMouse = mouseAttached
        mouseAttached = Build.VERSION.SDK_INT >= 29 && runCatching {
            InputDevice.getDeviceIds().any { id ->
                InputDevice.getDevice(id)?.let {
                    GamepadDevices.isExternalMouse(
                        isExternal = it.isExternal,
                        isVirtual = it.isVirtual,
                        supportsMouse = it.supportsSource(InputDevice.SOURCE_MOUSE),
                    )
                } == true
            }
        }.getOrDefault(false)
        gamepadName = runCatching {
            InputDevice.getDeviceIds().asSequence().mapNotNull { id ->
                InputDevice.getDevice(id)?.takeIf {
                    GamepadDevices.isPhysicalGamepad(
                        name = it.name,
                        isVirtual = it.isVirtual,
                        supportsGamepad = it.supportsSource(InputDevice.SOURCE_GAMEPAD),
                        vendorId = it.vendorId,
                        productId = it.productId,
                        alphabetic = it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
                    )
                }?.let { it.name.orEmpty().ifEmpty { "(no name)" } }
            }.firstOrNull()
        }.getOrNull()
        gamepadAttached = gamepadName != null
        updateKeyFilter()
        val filtering = ((serviceInfo?.flags ?: 0) and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        if (mouseAttached && !hadMouse && filtering) noteMouseWithFilter(this)
    }

    private val gamepadListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = lookForGamepads()
        override fun onInputDeviceRemoved(deviceId: Int) = lookForGamepads()
        override fun onInputDeviceChanged(deviceId: Int) = lookForGamepads()
    }

    private fun keyFilterRival(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) return false
        val me = ComponentName(this, HomeGuardService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').mapNotNull { ComponentName.unflattenFromString(it) }.toSet()
        val installed = getSystemService<AccessibilityManager>()?.installedAccessibilityServiceList.orEmpty()
        return installed.any { info ->
            val name = ComponentName.unflattenFromString(info.id)
            name != me && name in enabled &&
                info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0 &&
                info.flags and AccessibilityServiceInfo.DEFAULT == 0 &&
                info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0
        }
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

    /**
     * Redmi Projector Lite (Projector_10, CVTE/ASU Android 9) runs a vendor cleanup in its normal
     * HOME dispatch that force-stops the process in front. When Aerial is default, that process is
     * Aerial itself, so every Home press becomes a 2-3 second cold start. Detect the firmware's AT
     * package and take HOME before the system, even though Aerial is already default.
     *
     * YunOS (Haier HRA962X2_2G_ALI6G_A, Amlogic tl1, Android 9) answers Home from system_server
     * with yunostv_homeshell://start_home, which names Youku's launcher and never asks what Home
     * resolves to: with Aerial default and no filter, every Home showed it and Aerial came back
     * after Android's 5 s hold (issue #883, the reporter's log). Known by YunOS's home shell being
     * installed.
     *
     * Not on Coocaa, though its CVTE firmware has the same AT package: Coocaa C10 (3H352_C10,
     * Hi3751V350, Android 9) dispatched every volume press the filter passed on a second time,
     * one lift for the two, and the other ran on as Android's repeats, the volume to 0 or 100.
     * 1.76, which left a default Home's keys alone there, stepped once a press (issue #1113, the
     * reporter's system_server log). Known by Coocaa's system app; not tested on a C10.
     */
    /** [DeviceProfile.filterHomeAlways]; the profile is in credential storage, so false while locked. */
    private fun filterHomeAlways(): Boolean =
        GuardSetup.isUserUnlocked(this) && DeviceProfile.current(this).filterHomeAlways

    private val defaultHomeNeedsHomeFilter by lazy {
        fun installed(name: String) = runCatching { packageManager.getPackageInfo(name, 0) }.isSuccess
        (installed("com.cvte.factory.at") && !installed("com.tianci.system")) || installed("com.yunos.tv.homeshell")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        keySeen = true
        if (!GuardSetup.isUserUnlocked(this)) return false
        if (event.keyCode in VOLUME_KEYS) {
            return if (filteringForCecOnly || (isDefaultHome() && defaultHomeNeedsHomeFilter)) {
                false
            } else {
                takeVolumeKey(event)
            }
        }
        // Any key that is done: whatever it did to the volume (Control Center's slider) is in.
        if (event.action == KeyEvent.ACTION_UP) volumeSeen = volumeNow()
        if (event.keyCode != KeyEvent.KEYCODE_HOME && !isStrayHome(event)) return false
        if (takeCecHome(event)) return true
        if (isDefaultHome() && !defaultHomeNeedsHomeFilter) return false
        // Held, Home is the firmware's task center, which taking the key hid (Xiaomi, issue #185):
        // open the recent apps instead, and leave Home alone when the key comes up. Timed here:
        // the filter sees keys before Android makes the repeats that would say the key is held.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            homeHeld = false
            homeDownTakenAt = SystemClock.uptimeMillis()
            handler.removeCallbacks(homeDownLapsed)
            handler.postDelayed(homeDownLapsed, HOME_DOWN_KEEP_MS)
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
            if (homeDownTakenAt > Long.MIN_VALUE / 2) {
                homeDownTakenAt = Long.MIN_VALUE / 2
                handler.removeCallbacks(homeDownLapsed)
                handler.post(homeDownLapsed)
            }
            if (!event.isCanceled && !homeHeld) {
                Log.i(TAG, "Home key taken before the system saw it")
                // Over Aerial's screensaver, Home closes it. Not counted toward a double press: an IR
                // remote's press can come twice (TCL MT5879), and the second opened the recent apps.
                lastHomeUpAt = if (AerialDream.endForHome()) Long.MIN_VALUE / 2 else event.eventTime
                goHome()
                closeSystemDialogs()
            }
        }
        return true
    }

    /**
     * A remote's Home key the firmware's key layout gets wrong, which then never reached the system
     * as Home either: Xiaomi's Bluetooth remote 2717:32b8 has no layout of its own on MiTV4A
     * (Android 6.0.1, issue #1396), and Generic.kl makes its KEY_HOME (102) MOVE_HOME; Haier's
     * HaierTV-RC (LRH812_ALI7G_A, Android 12, issue #1388) sends KEY_HOMEPAGE (172), which its own
     * layout leaves out, so it comes as UNKNOWN. Not from a keyboard with letters, where MOVE_HOME
     * is the key that moves the cursor to the start of the line.
     */
    private fun isStrayHome(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_MOVE_HOME && event.keyCode != KeyEvent.KEYCODE_UNKNOWN) return false
        val stray = strayHome(event.keyCode, event.scanCode, event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC)
        if (stray && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            DiagLog.i("guard: ${KeyEvent.keyCodeToString(event.keyCode)} (scan code ${event.scanCode}) from ${event.device?.name} taken as Home")
        }
        return stray
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
        val stepKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            lastVolumeDown = event.downTime
            handler.removeCallbacks(volumeRepeat)
            // Held, the remote sends a new press every 200 ms, sooner than the step waits: each
            // cancelled the one before, and holding the key moved the volume one step in all
            // (Skyworth 8R134_A7D, Android 11, issue #937 on 1.80). Settle the one before first.
            if (volumeStepPending) volumeStepIfUnmoved.run()
            handler.removeCallbacks(volumeStepIfUnmoved)
            // A second key pressed during the first (two remotes) is not it: a lift lost there
            // leaves the volume running for seconds before the next press.
            if (stepKey && event.downTime - volumeDownPassedAt >= VOLUME_LIFT_WAIT_MS) noteVolumeLiftUnseen(this)
            val now = volumeNow()
            val seen = volumeSeen
            val movedFirst = now != null && seen != null && now != seen
            volumeKeyTaken = movedFirst || (stepKey && volumeLiftUnseen(this))
            if (volumeKeyTaken && !movedFirst) {
                // The firmware acts on the press before the filter, a moment later at times; where
                // it did not, the step is Aerial's, once.
                volumeStepFrom = now
                volumeStepDirection = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                volumeStepPending = true
                handler.postDelayed(volumeStepIfUnmoved, VOLUME_UNMOVED_MS)
            }
            volumeDownPassedAt = if (stepKey && !volumeKeyTaken) event.downTime else Long.MAX_VALUE
            if (movedFirst) {
                Log.i(TAG, "volume already changed before the key filter; not passing the key on")
                volumeRepeatDirection = when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> 1
                    KeyEvent.KEYCODE_VOLUME_DOWN -> -1
                    else -> 0
                }
                if (volumeRepeatDirection != 0 && (repeatsHeldVolume || takenVolumeLiftSeen)) {
                    volumeRepeatUntil = SystemClock.uptimeMillis() + VOLUME_REPEAT_MAX_MS
                    handler.postDelayed(volumeRepeat, ViewConfiguration.getKeyRepeatTimeout().toLong())
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN) {
            // The firmware repeats the key itself, and acts on each before the filter.
            handler.removeCallbacks(volumeRepeat)
        }
        val taken = volumeKeyTaken
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            DiagLog.i("guard: ${KeyEvent.keyCodeToString(event.keyCode)} down at ${event.downTime}${if (taken) ", not passed on" else ""}")
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (taken && stepKey && !takenVolumeLiftSeen) {
                takenVolumeLiftSeen = true
                DiagLog.i("guard: the lift of a volume press not passed on reached the filter; held keys step the volume from now on")
            }
            volumeDownPassedAt = Long.MAX_VALUE
            volumeUps[event.keyCode] = event.eventTime
            DiagLog.i("guard: ${KeyEvent.keyCodeToString(event.keyCode)} up at ${event.eventTime}")
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

    /**
     * When the volume press passed on, whose lift the filter has not seen yet, went down; see
     * [volumeLiftUnseen]. MAX_VALUE when there is none.
     */
    private var volumeDownPassedAt = Long.MAX_VALUE
    private var volumeStepFrom: Pair<Int, Boolean>? = null
    private var volumeStepDirection = 0
    private var volumeStepPending = false
    private val volumeStepIfUnmoved = Runnable {
        volumeStepPending = false
        if (volumeNow() != volumeStepFrom) return@Runnable
        DiagLog.i("guard: the volume did not move for a press not passed on; stepping it")
        SystemProbe.adjustVolume(this, volumeStepDirection, fromKey = true)
        // Its own step is no change the firmware made: the next press must not look moved first.
        volumeSeen = volumeNow()
    }

    /**
     * A volume press not passed on gets no repeats from Android, so held it moved one step:
     * Skyworth/Coocaa 8R145_P3DM (Android 11, issue #1091 on 1.80, the reporter's log: one step
     * for a 6.3 s hold, the lift at the end). Where such a press's lift has reached the filter, the
     * guard steps a held key as on TCL. Not before: on 7AA46_H5D and 8A002_H5D (issues #448 #451)
     * and 8R134_A7D (issue #937, Aerial's log on 1.81) no lift ever came, and stepping until the
     * cap would run the volume for seconds after every press.
     */
    private var takenVolumeLiftSeen = false

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
     * Only where Xiaomi's settings app is: firmwares that clear apps on a Home press listen for
     * it, and Dangbei H3S (RK3566, Android 11) stopped Aerial itself on it, so its Home press
     * ended on Dangbei's launcher (issue #849, the broadcast alone sent from adb did it).
     */
    private val closesXiaomiPanel by lazy {
        runCatching { packageManager.getPackageInfo("com.xiaomi.mitv.settings", 0) }.isSuccess
    }

    private fun closeSystemDialogs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !closesXiaomiPanel) return
        @Suppress("DEPRECATION")
        runCatching { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey")) }
            .onFailure { Log.w(TAG, "could not close the system dialogs", it) }
    }

    /** When a Home press the guard answered came up, for a second press just after it. */
    private var lastHomeUpAt = Long.MIN_VALUE / 2

    private var homeHeld = false

    /** When a Home press the filter took went down, until its key comes up; see updateKeyFilter. */
    private var homeDownTakenAt = Long.MIN_VALUE / 2
    private val homeDownLapsed = Runnable { updateKeyFilter() }

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
    /** When Home held sent TCL's task manager broadcast, in uptime; see the stock launcher check. */
    private var tclTaskCentreAt = Long.MIN_VALUE / 2

    private fun openRecents() {
        // Xiaomi's task centre is a system overlay, not an exported RecentsActivity.
        // Its normal-permission service initializes the overlay before toggling it.
        val xiaomi = ComponentName("com.xiaomi.mitv.systemui", "com.xiaomi.mitv.systemui.MiTVSystemUIService")
        val service = runCatching { packageManager.getServiceInfo(xiaomi, 0) }.getOrNull()
        if (service?.enabled == true && service.applicationInfo.enabled && service.exported &&
            (service.permission.isNullOrEmpty() ||
                ContextCompat.checkSelfPermission(this, service.permission) == PackageManager.PERMISSION_GRANTED)) {
            if (runCatching { startService(Intent().setComponent(xiaomi).putExtra("recent_control", 2)) }.getOrNull() != null) return
        }
        // TCL's task manager (TaskManagerActivity) is not exported. Held, Home makes the firmware
        // send this broadcast to TCL's launcher, which opens it; the receiver is exported without a
        // permission. Verified on TCL 65Q9K (tcl_mt5879_cn, Android 11), issue #1354.
        val tcl = ComponentName("com.tcl.cyberui", "com.tcl.global.history.receiver.GlobalHistoryReceiver")
        val receiver = runCatching { packageManager.getReceiverInfo(tcl, 0) }.getOrNull()
        if (receiver?.enabled == true && receiver.applicationInfo.enabled && receiver.exported &&
            receiver.permission.isNullOrEmpty()) {
            val sent = runCatching {
                sendBroadcast(Intent(TCL_TASK_CENTRE).setComponent(tcl).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
            }.isSuccess
            if (sent) {
                tclTaskCentreAt = SystemClock.uptimeMillis()
                return
            }
        }
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
        deafAfterHome = false
        windowsSeen++
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
        // Kids and Elders mode: an app not chosen for it (or any, once locked) goes straight back
        // to Home. The stock launcher's home activity and input switches are left to the rest below.
        if (isActivity(pkg, cls) && pkg !in SOURCE_SWITCHERS && stockHomes[pkg]?.contains(cls) != true && KidsWatch.keepsAway(this, pkg)) {
            Log.i(TAG, "$pkg kept away by Kids mode; bringing Aerial back")
            KidsWatch.noteKeptAway(this, pkg)
            goHome()
            return
        }
        // A FallbackHome in front once the user is unlocked: Home went nowhere. On Formovie spiderman
        // (Android 9, unlock heard only by polling) Home pressed in Aerial left Settings'
        // FallbackHome on screen, and the guard, which keeps fallbacks out of stockHomes, did
        // nothing; switching accessibility off and on brought Aerial back (issue #1213, the
        // reporter's 1.83 log). The setup wizard's fallbacks are left alone.
        if (isFallbackHomeScreen(pkg, cls) && !screenOff) {
            if (SystemClock.uptimeMillis() - broughtBackAt < 1_000) return
            DiagLog.i("guard: $pkg/$cls in front after the unlock; bringing Aerial back")
            goHome()
            return
        }
        // Old VIDAA (Android 5.1.1, Hi3751v620): the remote has no Home key, and its smart key (F12)
        // is taken in system_server to show Jamdeo's GlobalPanel, a system overlay whose only window
        // event names a RelativeLayout. The reporters asked for the key to be Home everywhere
        // (issue #808, events from the reporter's logcat): close the panel and bring Aerial back.
        // Not tried on that TV.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1 && pkg == JAMDEO_LAUNCHER &&
            cls == "android.widget.RelativeLayout" && pkg in stockHomes && !screenOff
        ) {
            DiagLog.i("guard: Jamdeo's panel over ${before ?: "?"}; closing it and bringing Aerial back")
            performGlobalAction(GLOBAL_ACTION_BACK)
            goHome()
            return
        }
        if (firmwarePushedAtBoot(pkg) && isActivity(pkg, cls) && !screenOff) {
            DiagLog.i("guard: $pkg/$cls came up at boot with no key pressed; bringing Aerial back")
            goHome()
            return
        }
        val allowed = SystemClock.uptimeMillis() < allowStockUntil
        if (pkg in stockHomes) {
            // Any of its screens, uncovered once the installer of an update from Control Center closed.
            if (!allowed && isActivity(pkg, cls) && GuardSetup.installerJustClosed(this)) {
                Log.i(TAG, "stock launcher $pkg in front after an update; bringing Aerial back")
                goHome()
                return
            }
            updateKeyFilter()
            if (cls in stockHomes.getValue(pkg)) GuardSetup.noteStockHomeInFront(this)
            if (bootStockHolding && !bootStockShown && cls in stockHomes.getValue(pkg)) {
                bootStockShown = true
                handler.removeCallbacks(endBootStockHold)
                handler.postDelayed(endBootStockHold, DeviceProfile.current(this).bootStockMs.toLong())
            }
            // The task manager opened for Home held: on TCL T966D5 (Android 14, issue #1402) and
            // Q9L Pro (#1369) the launcher's broadcast brings up its MainActivity, the task manager
            // inside it, and the guard took it away a second later. On TCL 65Q9K (MT5879) the same
            // broadcast opens TaskManagerActivity, a screen of its own, and this never comes up.
            if (!allowed && cls in stockHomes.getValue(pkg) &&
                SystemClock.uptimeMillis() - tclTaskCentreAt < TCL_TASK_CENTRE_MS) {
                tclTaskCentreAt = Long.MIN_VALUE / 2
                Log.i(TAG, "$pkg came up for its task manager after Home was held; leaving it in front")
                allowStockLauncher()
                return
            }
            // Only the stock launcher's home activity itself, not its dialogs or other screens.
            // Not gated on isDefaultHome(): see takeOverIfStockHomeInFront.
            val bootCover = isPatchwallHomeCover(pkg, cls)
            if ((cls !in stockHomes.getValue(pkg) && !bootCover) || allowed) return
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
            // What the TV starts on opened, then the stock launcher came up over it: 16 s later on
            // MiTV-MFTR0 (Android 14, 1.85, issue #1229's log), and Aerial's Home then took "already
            // opened" for done, so the target was lost. This path missed the check the two others have.
            if (bootTargetCovered()) {
                Log.i(TAG, "stock home $pkg came to the front over what the TV starts on; opening that again")
                DiagLog.i("guard: stock home $pkg/$cls over what the TV starts on; opening that again")
                goHome(reopenBoot = true)
                return
            }
            Log.i(TAG, "stock home $pkg came to the front; bringing Aerial back")
            DiagLog.i("guard: stock home $pkg/$cls in front, not opened on purpose${if (allowStockUntil != 0L) " (allowance ran out)" else ""}; bringing Aerial back")
            // Shortly after the screen lit, this is the wake itself (issue #111).
            goHome(woke = SystemClock.elapsedRealtime() - screenOnAt < WAKE_WINDOW_MS)
            return
        }
        updateKeyFilter()
        // Leaving the stock launcher for a real app ends the visit: Home goes to Aerial again. Not
        // while the stock launcher's own start is still on screen, where the firmware's SystemUI
        // comes to the front for a moment and would end the visit before it began.
        if (allowed && pkg != packageName && isApp(pkg) && !visitStillOpening()) endStockVisit("$pkg in front")
    }

    private var bannerView: android.view.View? = null
    private val removeBanner = Runnable {
        bannerView?.let { v -> runCatching { getSystemService<android.view.WindowManager>()?.removeView(v) } }
        bannerView = null
    }

    private fun banner(text: String) {
        val wm = getSystemService<android.view.WindowManager>() ?: return
        handler.removeCallbacks(removeBanner)
        removeBanner.run()
        // Sized on Aerial's 960 x 540 canvas, as Home's own notice.
        val dp = resources.displayMetrics.widthPixels / 960f
        val view = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 17 * dp)
            setPadding((22 * dp).toInt(), (12 * dp).toInt(), (22 * dp).toInt(), (12 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE61C1C1E.toInt())
                cornerRadius = 22 * dp
            }
        }
        @Suppress("InlinedApi")
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = (28 * dp).toInt()
        }
        runCatching { wm.addView(view, lp); bannerView = view }.onFailure { Log.w(TAG, "could not show a banner", it) }
        handler.postDelayed(removeBanner, BANNER_MS)
    }

    private val activityCache = HashMap<Pair<String, String>, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut(pkg to cls) {
        runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
    }

    /** True for packages with a launcher entry, i.e. apps rather than system overlays and dialogs. */
    private fun isApp(pkg: String): Boolean =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null

    /** Settings' FallbackHome (one of fallbackHomes, named so), not the setup wizard's; see onAccessibilityEvent. */
    private fun isFallbackHomeScreen(pkg: String, cls: String): Boolean =
        cls.endsWith(".FallbackHome") && (pkg to cls) in fallbackHomes && GuardSetup.isUserUnlocked(this)

    private fun isDefaultHome(): Boolean = resolvedHome() == packageName

    private fun resolvedHome(): String? =
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName

    /**
     * The stock launcher list is read again when a package is enabled, disabled, installed or
     * removed. Read only at bind and unlock, it went stale: a guard bound while PatchWall was
     * disabled kept ignoring it after `pm enable`, so Home stayed on PatchWall until a reboot
     * (Xiaomi MiTV-MFTR0, OS3.0.101, issue #901). One update sends several broadcasts, so they
     * are folded into one read.
     */
    private val refreshHomesAfterPackageChange = Runnable { refreshStockHomes() }

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(refreshHomesAfterPackageChange)
            handler.postDelayed(refreshHomesAfterPackageChange, PACKAGE_SETTLE_MS)
        }
    }

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
                .filter { ri -> ri.filter?.categoriesIterator()?.asSequence()?.any(Companion::isVendorHomeCategory) == true }
        }.getOrDefault(emptyList())
        // The TV player is where inputs play, even where it also declares a Home filter: TCL
        // 85G60E (Android 11) lists com.tcl.tv as a Home next to com.tcl.cyberui, and every input
        // switch, from Aerial or the remote, was taken back at once (issue #223). It stays one
        // only where it is the Home the system resolves.
        val resolved = resolvedHome()
        fallbackHomes = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .filter { it.priority < 0 && it.activityInfo.packageName != packageName }
                .filter { it.activityInfo.name != LOW_PRIORITY_HOMES[it.activityInfo.packageName] }
                .map { it.activityInfo.packageName to it.activityInfo.name }.toSet()
        }.getOrDefault(emptySet())
        val homes = (standard + vendor)
            // Negative priority entries are fallbacks shown only while nothing else can be Home,
            // except the ones a firmware does start.
            .filter { it.activityInfo.packageName != packageName }
            .filter { it.priority >= 0 || it.activityInfo.name == LOW_PRIORITY_HOMES[it.activityInfo.packageName] }
            .filter { it.activityInfo.packageName !in INPUT_PLAYERS || it.activityInfo.packageName == resolved }
            .filter { it.activityInfo.packageName !in NOT_LAUNCHERS }
            // An app the viewer installed that declares Home too, but that Home does not resolve
            // to, only comes up when the viewer opens it: Pegasus Frontend's one activity carries
            // LAUNCHER, LEANBACK_LAUNCHER and HOME, and every time it was opened from Aerial the
            // guard took it for a launcher and brought Aerial back over it (Mi TV 4A, Android 6,
            // issue #1136). Firmware launchers are system apps; one installed and made the
            // default Home stays a stock home.
            .filter {
                (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    it.activityInfo.packageName == resolved
            }
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
        // Tianci / Coocaa: the launcher the firmware names in persist.service.homepage.* first for
        // its package, so the 原桌面 tile opens that one and not another activity of the package
        // (Skyworth 8R734_A7HP: the AI launcher com.coocaa.aigc.home.home.rootpage.HomeActivity, in
        // com.tianci.movieplatform, declares no Home; that package's HOME.CC is the older UI).
        CoocaaHome.launcher(this)?.takeIf { isActivity(it.packageName, it.className) }?.let { own ->
            stockHomes = stockHomes + (own.packageName to (setOf(own.className) + stockHomes[own.packageName].orEmpty()))
        }
        // Kept in the companion: a tile pressed while the guard is unbound still has to be
        // recognised as the stock launcher (see knownStockHomes).
        knownStockHomes = stockHomes
    }

    /**
     * Brings Aerial's one task to the front ([MainActivity.bringBackIntent]). No CATEGORY_HOME on
     * firmwares where Aerial is not the system Home: there that category made the system open a
     * second, home-type task with a fresh MainActivity every time, which lost the focused tile
     * (Xiaomi MiTV-MFTR0, 1.11). Where Aerial is the system Home it is a Home intent, see there.
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

    private fun pressHomeForStock(): Boolean {
        ownHomeAt = SystemClock.uptimeMillis()
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun goHome(woke: Boolean = false, reopenBoot: Boolean = false) {
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
            val intent = MainActivity.bringBackIntent(this).putExtra(MainActivity.EXTRA_WOKE, woke)
                .putExtra(MainActivity.EXTRA_REOPEN_BOOT, reopenBoot)
            if (!startPastHomeHold(intent)) startActivity(intent)
        }.onFailure { Log.w(TAG, "could not bring Aerial Home to the front", it) }
    }

    /**
     * Android 5 to 8.1 hold an app's activity starts for 5 s after a Home the system handled
     * (stopAppSwitches). A PendingIntent is sent by the system there, which may switch apps, so
     * it is not held: Home from the Mi Home app's remote reaches PatchWall without the key filter,
     * and Aerial came back after 5 s; sent this way, 0.25 s (Mi TV 4, Android 6.0.1, tried by the
     * reporter, issue #933). Android 9 counts the app that sends it instead.
     */
    private fun startPastHomeHold(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) return false
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return runCatching { PendingIntent.getActivity(this, BRING_BACK_REQUEST, intent, flags).send() }
            .onFailure { Log.w(TAG, "bringing Aerial back through a PendingIntent failed; starting it directly", it) }
            .isSuccess
    }

    companion object {
        private const val TAG = "AerialGuard"
        private const val BRING_BACK_REQUEST = 933
        private const val BOOT_WINDOW_MS = 3 * 60 * 1000L

        /** How recently the stock launcher must have come up for a new process to count as stopped over it (issue #1276). */
        private const val STOPPED_OVER_STOCK_MS = 2 * 60 * 1000L
        private const val UNLOCK_POLL_MS = 2_000L
        private const val UNLOCK_POLL_FOR_MS = 10 * 60 * 1000L
        private const val HOME_KEY_CHECK_MS = 1500L
        /**
         * How long after what the TV starts on was opened the stock launcher may still come over it
         * and be answered by opening that again. 24.3 s on MiTV4-ANSM0 (Android 6.0.1, firmware
         * 22.10.20.3352, issue #963), 38.4 s in that boot when the guard's process was started again
         * and usage stats still had the launcher's move to the front from before the target opened.
         * Slower TVs come later: 30 s left little room.
         */
        private const val BOOT_TARGET_COVER_MS = 60_000L
        /** The same after a wake, outside the boot window; see bootTargetCovered. */
        private const val WAKE_TARGET_COVER_MS = 15_000L
        /** A Home press this recently is the viewer leaving what the TV starts on; see bootTargetCovered. */
        private const val HOME_LEFT_MS = 5_000L
        private const val SETTLE_MS = 1500L
        private const val BOOT_STOCK_CAP_MS = 20_000L
        /** How long the stock launcher's own start may show the firmware's SystemUI; see visitStillOpening. */
        private const val STOCK_OPENING_MS = 2_000L
        private const val WAKE_WINDOW_MS = 15_000L
        private val BOOT_RECHECKS_MS = listOf(30_000L, 45_000L)
        private const val COVERED_UNSEEN_MS = 3_000L
        private const val DEAF_AFTER_MS = 20_000L
        /** How long Home with no front seen yet waits for a window change; see homeKey. */
        private const val UNKNOWN_FRONT_HOME_MS = 400L
        /** See watchUnseenApp. */
        private const val UNSEEN_APP_POLL_MS = 2_000L
        private const val UNSEEN_APP_WATCH_MS = 2 * 60_000L
        /** How long a stock launcher's window change may take to arrive after Home (see frontAfterHome). */
        private const val FRONT_AFTER_HOME_MS = 1_500L
        /** How often usage stats are read after Home once the guard has been found deaf. */
        private const val DEAF_LOOK_MS = 150L
        /** How recently PatchWall must have come to the front, and how often and how long to look; see uiBoostChanged. */
        private const val STOCK_HOME_FRONT_MS = 2_000L
        private const val STOCK_HOME_LOOK_MS = 20L
        private const val STOCK_HOME_LOOK_FOR_MS = 600L
        /** Usage stats' times and the boost's are the same clock read in two processes. */
        private const val STOCK_HOME_SLACK_MS = 100L
        /** Xiaomi's UI boost; see uiBoostChanged. */
        private const val UI_BOOST = "mitv_fast_cur_anim_scale"
        private const val XGIMI_FRAMEWORK = "com.xgimi.xgimiframeworkui"
        /** Linux input codes of a remote's Home key: KEY_HOME and KEY_HOMEPAGE; see isStrayHome. */
        private const val SCAN_KEY_HOME = 102
        private const val SCAN_KEY_HOMEPAGE = 172

        /** See isStrayHome; [lettered] is a keyboard with letters. */
        internal fun strayHome(keyCode: Int, scanCode: Int, lettered: Boolean): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_MOVE_HOME -> scanCode == SCAN_KEY_HOME && !lettered
            KeyEvent.KEYCODE_UNKNOWN -> scanCode == SCAN_KEY_HOME || scanCode == SCAN_KEY_HOMEPAGE
            else -> false
        }

        /** The system's chooser asking which Home to use: XGIMI's own, or Android's (see takeOver). */
        internal fun isHomeChooser(pkg: String, cls: String): Boolean =
            (pkg == XGIMI_FRAMEWORK && cls.endsWith(".resolver.ResolverActivity")) ||
                (pkg == "android" && cls == "com.android.internal.app.ResolverActivity")
        private const val JMGO_TV = "com.jmgo.tv"
        private const val FOREGROUND_POLL_MS = 1_000L
        private const val FOREGROUND_LOOKBACK_MS = 10_000L
        /** How far back to look for the front when no window has been seen; see frontByUsage. */
        private const val UNKNOWN_FRONT_LOOKBACK_MS = 6 * 60 * 60 * 1000L
        private const val DOUBLE_HOME_MS = 400L
        /** Android's hold on app starts after Home (stopAppSwitches), with a margin. */
        private const val APP_SWITCH_HOLD_MS = 5_500L
        private const val PACKAGE_SETTLE_MS = 1000L
        private const val PATCHWALL = "com.mitv.tvhome"
        /**
         * PatchWall's boot cover, which comes up over Aerial some 20 s into a boot and is no home
         * activity. Taken over from usage stats in the boot window, at the next poll instead of at
         * the boot recheck: 19.7 s of it before, 1.0 s with this (MiTV4 pulpfiction, Android 6.0.1;
         * the class is the one usage stats record at every boot there).
         */
        private const val PATCHWALL_BOOT = "com.mitv.tvhome.BootActivity"
        /** Hisense VIDAA's launcher, invisible to window changes; see foregroundCheck. */
        private const val VIDAA_LAUNCHER = "com.jamdeo.tv.vod"

        /** Stock launchers that come to the front with no window change; see foregroundCheck. */
        private val INVISIBLE_LAUNCHERS = setOf(VIDAA_LAUNCHER, "com.ktcp.launcher")
        /** Actions the firmware's own task centre answers, tried in order before SystemUI's recents. */
        private const val JAMDEO_LAUNCHER = "com.jamdeo.launcher"
        private const val TCL_TASK_CENTRE ="com.tcl.action.OPEN_GLOBAL_HISTORY"
        /** How long after that broadcast TCL's launcher coming up is its task manager (1 s seen, #1402). */
        private const val TCL_TASK_CENTRE_MS = 4_000L
        /** How long a Home press may stay down and keep the filter on for its lift; a lost lift ends it. */
        private const val HOME_DOWN_KEEP_MS = 10_000L
        private val TASK_CENTRE_ACTIONS = listOf("com.dangbei.recentstask.action.SHOW_RECENTS_FOR_USER")
        private const val VOLUME_REPEAT_MAX_MS = 8_000L
        private const val HOME_PAUSE_MS = 400L
        /** "homekey" came 350 ms or more after the key while Aerial was busy (see noteHomeInAerial). */
        private const val OWN_HOME_MS = 1_500L
        private const val DOUBLE_HOME_BROADCAST_MS = 600L
        private val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

        /** When the filter last saw each volume key come up (event time); see volumeKeyCameUp. */
        private val volumeUps = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        /**
         * Whether the volume key of a press that went down at [downTime] has come up at the filter.
         * On Coocaa with the filter on, the lift never reached Aerial's window, and Android went on
         * repeating the press there every 50 ms until another key came, each repeat a volume step
         * (8R134_A7D and 8R145_P3DM on Realtek RTD2885N, Android 11; 8A002_A3 on T963, Android 9;
         * issues #937 #1091 #1058, Aerial's own key log in each). MainActivity drops such repeats.
         */
        fun volumeKeyCameUp(keyCode: Int, downTime: Long): Boolean = (volumeUps[keyCode] ?: Long.MIN_VALUE) >= downTime

        /** When the filter last saw a volume press go down (down time); see volumePressOver. */
        @Volatile
        private var lastVolumeDown = Long.MIN_VALUE

        /**
         * Whether a volume press that went down at [downTime] is over at the filter: a newer volume
         * press went down there, or the firmware's lifts never reach it (see [volumeLiftUnseen]).
         * Android repeats a press passed on whose lift never came until another key reaches it, and
         * a press the guard keeps is none: Skyworth 8R134_A7D (RTD2885N, Android 11) with 1.78 sent
         * VOLUME_UP #120 to #152 to Aerial's window every 50 ms while the guard kept three newer
         * presses (issue #937, the reporter's log); every repeat there was a volume step.
         */
        fun volumePressOver(context: Context, downTime: Long): Boolean =
            lastVolumeDown > downTime || volumeLiftUnseen(context)

        /**
         * A mouse connected while the filter was on: when, and when this boot began. If Aerial is
         * bound again in a new process within [MOUSE_CRASH_MS] in the same boot (system_server
         * restarted, the kernel did not), the mouse took the system down, and from then on a mouse
         * stops the filter on this firmware build; see updateKeyFilter. A process that lives out
         * the window clears the note.
         */
        private const val KEY_MOUSE_FILTER_AT = "guard_mouse_filter_at"
        private const val KEY_MOUSE_FILTER_BOOT = "guard_mouse_filter_boot"
        private const val KEY_MOUSE_DOWNED_SYSTEM = "guard_mouse_downed_system"
        private const val MOUSE_CRASH_MS = 180_000L
        @Volatile
        private var mouseDownedSystem: Boolean? = null

        private fun bootStartedAt() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        // Credential storage: nothing is read or written before the unlock (Direct Boot).
        private fun mouseDownedSystem(context: Context): Boolean = mouseDownedSystem
            ?: if (!GuardSetup.isUserUnlocked(context)) false else (context.getSharedPreferences("system", Context.MODE_PRIVATE).getString(KEY_MOUSE_DOWNED_SYSTEM, null) == Build.FINGERPRINT)
                .also { mouseDownedSystem = it }

        private fun noteMouseWithFilter(context: Context) {
            if (!GuardSetup.isUserUnlocked(context) || mouseDownedSystem(context)) return
            val prefs = context.getSharedPreferences("system", Context.MODE_PRIVATE)
            val at = System.currentTimeMillis()
            prefs.edit().putLong(KEY_MOUSE_FILTER_AT, at).putLong(KEY_MOUSE_FILTER_BOOT, bootStartedAt()).commit()
            Handler(Looper.getMainLooper()).postDelayed({
                if (prefs.getLong(KEY_MOUSE_FILTER_AT, 0L) == at) prefs.edit().remove(KEY_MOUSE_FILTER_AT).remove(KEY_MOUSE_FILTER_BOOT).apply()
            }, MOUSE_CRASH_MS)
        }

        private fun checkMouseWithFilter(context: Context) {
            if (!GuardSetup.isUserUnlocked(context)) return
            val prefs = context.getSharedPreferences("system", Context.MODE_PRIVATE)
            val at = prefs.getLong(KEY_MOUSE_FILTER_AT, 0L)
            if (at == 0L) return
            val boot = prefs.getLong(KEY_MOUSE_FILTER_BOOT, 0L)
            val edit = prefs.edit().remove(KEY_MOUSE_FILTER_AT).remove(KEY_MOUSE_FILTER_BOOT)
            val since = System.currentTimeMillis() - at
            val sameBoot = kotlin.math.abs(bootStartedAt() - boot) < 10_000
            // A new process: the one that wrote the note did not live out the window. The system
            // going down takes every app with it; Aerial alone dying that soon after a mouse
            // connected is not seen otherwise, and costs only a Home that shows the stock
            // launcher a moment while a mouse is in.
            val newProcess = Build.VERSION.SDK_INT >= 24 && bootStartedAt() + Process.getStartElapsedRealtime() > at
            if (since in 0..MOUSE_CRASH_MS && sameBoot && newProcess) {
                mouseDownedSystem = true
                edit.putString(KEY_MOUSE_DOWNED_SYSTEM, Build.FINGERPRINT)
                Log.w(TAG, "Aerial died ${since / 1000} s after a mouse connected with the filter on; a mouse stops the filter from now on")
                DiagLog.i("guard: Aerial died ${since / 1000} s after a mouse connected with keys filtered; a mouse stops the filter from now on")
            }
            edit.apply()
        }

        /**
         * Firmwares where a volume key's lift never reaches the filter, while its press does. Passed
         * on, the press reached a second volume path in the firmware that then waited for a lift
         * that never came and repeated the key every 50 ms until another key: one press ran the
         * volume to 0 or 100. The firmware has already acted on the press before the filter there,
         * so from the first press passed on with no lift before the next, no volume press is passed
         * on again on this firmware. Seen in the reporters' logs: Skyworth 4T834_Z22S (MT9638,
         * Android 10; the guard logged downs and no ups, issue #937) and Coocaa C10 projector
         * (3H352_C10, Hi3751, Android 9; DOWN on VolServer's Local and Broadcast paths, UP on Local
         * only, issue #1113); also #1058 #1091. Kept per firmware build.
         */
        private const val KEY_VOLUME_LIFT_UNSEEN = "guard_volume_lift_unseen"
        private const val VOLUME_UNMOVED_MS = 300L
        private const val VOLUME_LIFT_WAIT_MS = 1_000L
        @Volatile
        private var volumeLiftUnseen: Boolean? = null

        private fun volumeLiftUnseen(context: Context): Boolean = volumeLiftUnseen
            ?: if (!GuardSetup.isUserUnlocked(context)) false else (context.getSharedPreferences("system", Context.MODE_PRIVATE).getString(KEY_VOLUME_LIFT_UNSEEN, null) == Build.FINGERPRINT)
                .also { volumeLiftUnseen = it }

        private fun noteVolumeLiftUnseen(context: Context) {
            if (!GuardSetup.isUserUnlocked(context) || volumeLiftUnseen(context)) return
            volumeLiftUnseen = true
            Log.i(TAG, "a volume press passed on never came up at the filter; no volume press is passed on from now on")
            DiagLog.i("guard: a volume press passed on never came up here; volume presses stay with the firmware from now on")
            context.getSharedPreferences("system", Context.MODE_PRIVATE).edit().putString(KEY_VOLUME_LIFT_UNSEEN, Build.FINGERPRINT).apply()
        }

        @Volatile
        private var deafRetried = false

        /** Accessibility switched off and on for a deaf guard once already in this process; see frontAfterHome. */
        @Volatile
        private var deafAfterHomeRestarted = false

        /** Home has found the guard deaf, and no window change has come since; see frontAfterHome. */
        @Volatile
        private var deafAfterHome = false

        /**
         * Apps where Home brought no window change of the stock launcher, kept across processes.
         * In the Bilibili client blbl.cat3399 on Xiaomi MiTV-MFTR0 (Android 14, OS3.0.106), the
         * guard saw the app's window but none of PatchWall's after Home, every time and again after
         * accessibility was switched off and on; any window change after that ended deafAfterHome,
         * so each Home there waited the full FRONT_AFTER_HOME_MS with PatchWall on screen (issue
         * #984). In these apps usage stats are read every DEAF_LOOK_MS from the press on.
         */
        private var quietHomeApps: Set<String>? = null

        private const val QUIET_HOME_PREFS = "guard_quiet_home"
        private const val QUIET_HOME_MAX = 20

        private fun quietHomeApps(context: Context): Set<String> = quietHomeApps
            ?: context.getSharedPreferences(QUIET_HOME_PREFS, Context.MODE_PRIVATE)
                .getStringSet("apps", null).orEmpty().toSet().also { quietHomeApps = it }

        private fun noteQuietHomeApp(context: Context, pkg: String) {
            val now = quietHomeApps(context)
            if (pkg in now) return
            val next = (now + pkg).toList().takeLast(QUIET_HOME_MAX).toSet()
            quietHomeApps = next
            Log.i(TAG, "no window change after Home in $pkg; reading usage stats at once there from now on")
            context.getSharedPreferences(QUIET_HOME_PREFS, Context.MODE_PRIVATE).edit().putStringSet("apps", next).apply()
        }

        /** VIDAA's assistant and its launcher (聚好看), on the two VIDAA builds reported (issues #7, #40). */
        private val VIDAA_PACKAGES = listOf("com.hisense.vidaaassistant", "com.jamdeo.tv.vod")
        /**
         * The apps that play the TV's inputs (SystemProbe's TCL, Xiaomi and Skyworth routes).
         * HiSilicon's TV player declares a TV_HOME category and plays the input picked in Coocaa's
         * source dialog: taken for the stock launcher, it was closed half a second after every
         * switch to HDMI (Coocaa C10 projector 3H352_C10, Android 9, issue #779, with the log).
         */
        private val INPUT_PLAYERS = setOf("com.tcl.tv", "com.xiaomi.mitv.tvplayer", "com.skyworth.tv", "com.hisilicon.tvui")
        /**
         * Packages that declare Home but are no launcher to keep away. LeTV's app daemon shows its
         * DaemonActivity on the way into apps, and every app the viewer opened was taken back
         * within seconds; its children's app has a Home of its own and is opened on purpose (LeTV
         * X3-55 Pro, Android 5.0.1, issue #328).
         */
        private val NOT_LAUNCHERS = setOf("com.stv.appdaemon", "com.letv.lekids")
        /** TV services whose input switch shows the stock launcher on the way (Skyworth, #155). */
        private val SOURCE_SWITCHERS = setOf("com.tianci.tv")
        private const val BANNER_MS = 8_000L
        private const val VENDOR_HOME_PREFIX = "android.intent.category.HOME."
        private const val VENDOR_HOME_SUFFIX = "_HOME"

        /**
         * Android's own SECONDARY_HOME is for other displays, and comes with CATEGORY_HOME anyway.
         * Nor an automatic test's "home": CVTE's factory test com.cvte.factory.at declares
         * CVTE_AT_HOME, and as a 原桌面 tile it switched the TV into factory mode, a yellow K on
         * screen until the factory app was told to quit (OPPO OPPOCNM632, m7332, Android 9; issue #1028).
         */
        internal fun isVendorHomeCategory(category: String): Boolean =
            category.startsWith(VENDOR_HOME_PREFIX) ||
                (category.endsWith(VENDOR_HOME_SUFFIX) && category != "android.intent.category.SECONDARY_HOME" &&
                    !category.endsWith("_AT_HOME"))

        /** Launcher package to the screens its Home trampoline opens; see refreshStockHomes. */
        private val FIRMWARE_BOOT_APPS = setOf("com.yunos.tv.bluray")

        private val HOME_SCREENS_BEHIND_TRAMPOLINES = mapOf(
            "com.stv.deskplatform" to listOf("com.stv.deskplatform.ui.activity.Launcher"),
            // Tencent 极光 boxes: Home is LauncherMainActivity, the screen shown is this one
            // (极光盒子 6SE Q0102, Android 10, issue #270; 7S, Android 14, #269).
            "com.ktcp.launcher" to listOf("com.ktcp.launcher.activity.LauncherHomeSingleTaskActivity"),
            // Dangbei launcher preinstalled by Skyworth: Tianci's Home starts cos.launcher.Mac, the
            // screen shown is this one (7S72_G25, m7221, Android 8, issue #1297, the reporter's log).
            "com.cos.launcher" to listOf("com.dangbei.tvlauncher.IndexActivity"),
        )

        /**
         * Launcher screens a firmware starts by component that declare no Home filter at all, by
         * package: they match neither CATEGORY_HOME nor a vendor category, so the guard would take
         * them for an app the viewer had opened. Panasonic DX01C (Skyworth-made, Tianci, Android 6)
         * starts this one from /system/pcfg's DEFAULT_HOMEPAGE, and it declares only the private
         * action com.skyworth.home.neo, which left it in front at power-on and on the Home key.
         * E900V21F's LeJia is here for the other reason: it does declare CATEGORY_HOME, but the
         * firmware keeps Home candidates out of a third-party caller's query, so that query does
         * not return it either.
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
            // China Mobile "Mobile HD Box 6" 2024 (Skyworth E900V21F, S905L3S, Android 9): the
            // firmware starts LeJia's DesktopActivity by component at power-on and on the Home
            // key. It declares CATEGORY_HOME, but queryIntentActivities(HOME) from a third-party
            // process returns nothing (the firmware filters Home candidates by signature), so
            // resolve-activity stays on FallbackHome and set-home-activity rejects Aerial with
            // "cannot be home". Verified on device; dumpsys and logs in the linked issue.
            "com.mylejia.launcher" to listOf("com.mylejia.launcher.activity.DesktopActivity"),
            // TCL's launcher on sugarcane_mi (MStar m7221, Android 8.0): a Home query from Aerial
            // found nothing at all, not even this, so it stayed in front after every power-on until
            // Aerial was opened once (issue #1076, the reporter's agent sampling every 2 minutes).
            // Other TCLs list it as a Home; the class is theirs (sugarcane #898, MT5879).
            "com.tcl.cyberui" to listOf("com.tcl.cyberui.MainActivity"),
            // Hisense VIDAA's 聚好看 on VIDAA_TV V7N_Ultra (MT9653, Android 11): Home showed it, the
            // diagnostics list it as a Home candidate (priority 50), yet the guard did not keep it
            // away, so Home stayed there and its usage stats were not watched (issue #700). Not
            // seen on a TV here; named so it counts wherever the activity exists.
            "com.jamdeo.tv.vod" to listOf("com.ju.main.launcher.LauncherActivity"),
            // Xiaomi's office mode (办公模式) home on Mi TV 4 (MiTV4-ANSM0, Android 6.0.1): with it
            // on, Aerial stopped from the task list never came back, as the guard did not count
            // it (issue #861, the reporter's mResumedActivity). The elderly mode's home is
            // PatchWall's MainActivityUserMode, which already counts.
            "com.xiaomi.eetv.litehome" to listOf("com.xiaomi.eetv.litehome.Launcher"),
            // PatchWall on Xiaomi's Android TV firmware is its own package, and its home screen
            // declares no Home: the Mi key (KEYCODE_BUTTON_5) makes partnercustomizer's
            // GlobalKeyReceiver start it by component (the owner saw it at power-on too), and it
            // stayed over Aerial (MiTV-MOOQ0, Android 11; dumpsys window on device). Its input
            // source popup is another activity in the package, and stays.
            "com.mitv.tvhome.atv" to listOf("com.mitv.tvhome.atv.app.PatchWallActivity"),
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

        /** Brings Aerial's Home to the front as Home does; false while the guard is not running. */
        fun bringAerialBack(): Boolean {
            val s = instance ?: return false
            s.handler.post { s.goHome() }
            return true
        }

        /**
         * A line at the top of the screen over whatever app is in front (Kids mode's warnings),
         * drawn as the accessibility service's own overlay: no other permission is needed. False
         * where it cannot be (no guard, Android 5.0).
         */
        fun showBanner(text: String): Boolean {
            val s = instance ?: return false
            if (Build.VERSION.SDK_INT < 22) return false
            s.handler.post { s.banner(text) }
            return true
        }

        fun diagnosticState(): String {
            val s = instance ?: return "Home Guard bound=false resumed=$aerialResumed"
            val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            return "Home Guard bound=true resumed=$aerialResumed filter=${s.serviceInfo?.flags?.and(flag) != 0} " +
                "screenOff=${s.screenOff} gamepad=${s.gamepadName?.let { "'$it'" } ?: false} mouse=${s.mouseAttached} mouseDowned=${mouseDownedSystem(s)} volumeLiftUnseen=${volumeLiftUnseen(s)} stockAllowed=${SystemClock.uptimeMillis() < allowStockUntil} " +
                "front=${s.lastWindow} windowEvents=${s.windowsSeen} resolved=${s.resolvedHome()} homes=${s.stockHomes}"
        }

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
            s.bootCompletedAt = SystemClock.elapsedRealtime()
            s.handler.post {
                // Unlocked by now, whether or not the guard heard of it (issue #1213).
                if (s.unlockPending && GuardSetup.isUserUnlocked(s)) {
                    s.onUserUnlocked("boot completed while the unlock was unheard")
                    return@post
                }
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

        /**
         * The stock launchers the guard found last, kept in the companion so it outlives the
         * service. The firmware may unbind the guard at any moment — Xiaomi kills Aerial's process
         * and binds the service again — and a tile pressed in that gap must still be recognised as
         * the stock launcher: [isStockHome] answered false without this, so Aerial took PatchWall
         * for an ordinary app, the direct start threw for the signature permission and
         * [MainActivity.startStockLauncher] opened the same screen from its fallback, which never
         * calls [allowStockLauncher]; the guard then took Home back at once (MiTV-ANSP0, Android 9,
         * issue #971).
         */
        @Volatile
        private var knownStockHomes: Map<String, Set<String>> = emptyMap()

        /** True if [packageName] is a stock launcher the guard keeps away from the front. */
        fun isStockHome(packageName: String): Boolean =
            instance?.stockHomes?.containsKey(packageName) == true || knownStockHomes.containsKey(packageName)

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
            // Before the instance check: the allowance lives in the companion, so it counts even
            // while the guard is unbound, and the guard reads it again as soon as it binds.
            allowStockLauncher()
            val s = instance ?: return startDirectly()
            if (s.resolvedHome() == pkg && s.pressHomeForStock()) return true
            return startDirectly() || s.pressHomeForStock()
        }

        /**
         * Set when the viewer opens the stock launcher on purpose from Aerial's grid. Kept for the
         * process, not the service: on Xiaomi MiTV-ASTP0 and MiTV-ANSP0 (Android 9) PatchWall
         * opened from its tile was taken away 0.3-5 s later as if Home had been pressed, with the
         * guard's allowance already gone and no path that ends it logged (issues #971 #1055, the
         * reporters' logs); one guess is a service bound again in between. Each end is logged now.
         */
        @Volatile
        private var allowStockUntil = 0L

        /**
         * When that allowance was set. Starting the stock launcher shows the firmware's own
         * SystemUI over Aerial for a moment — Xiaomi's com.xiaomi.mitv.systemui has a launch intent
         * too, so it counts as an app — and ending the visit there left PatchWall in front with no
         * allowance, taken away at once (MiTV-ANSP0, Android 9, issue #971, the reporter's log:
         * "opened on purpose" 19:41:54.321, "visit over (com.xiaomi.mitv.systemui in front)"
         * .563, "bringing Aerial back" .644).
         */
        @Volatile
        private var allowStockFrom = 0L

        private fun endStockVisit(why: String) {
            if (allowStockUntil == 0L) return
            if (SystemClock.uptimeMillis() < allowStockUntil) DiagLog.i("guard: stock launcher visit over ($why)")
            allowStockUntil = 0L
        }

        /** Lets the stock launcher stay in front until Aerial is back: the viewer opened it on purpose. */
        fun allowStockLauncher() {
            val now = SystemClock.uptimeMillis()
            allowStockUntil = now + 60 * 60 * 1000L
            allowStockFrom = now
            DiagLog.i("guard: the stock launcher opened on purpose; leaving it in front")
        }

        /** The visit has not begun yet while the stock launcher's own start is still on screen. */
        private fun visitStillOpening(): Boolean =
            SystemClock.uptimeMillis() - allowStockFrom < STOCK_OPENING_MS

        /** Whether Aerial's Home is resumed; set by MainActivity, in this same process. */
        @Volatile
        private var aerialResumed = false

        /** When Aerial last came to the front, in uptime. */
        @Volatile
        private var aerialResumedAt = Long.MIN_VALUE / 2

        /** When Aerial last left the front, in uptime. */
        @Volatile
        private var aerialPausedAt = Long.MIN_VALUE / 2

        /** Called when Aerial comes back to the front: the stock launcher is fair game again. */
        fun onAerialResumed() {
            aerialResumed = true
            aerialResumedAt = SystemClock.uptimeMillis()
            val s = instance ?: return
            // Back in Aerial: an installer opened from Control Center was left without updating.
            GuardSetup.installerJustClosed(s)
            endStockVisit("Aerial back")
            s.updateKeyFilter()
            if (SystemClock.uptimeMillis() - s.recentsWhenBackAt < 2_000) {
                s.recentsWhenBackAt = Long.MIN_VALUE / 2
                s.openRecents()
            }
        }

        /**
         * Called when Aerial leaves the front: keys are no longer filtered from Android 12 on.
         * True when the pause is only Home's bounce (backOnPause) and Aerial is on its way back.
         */
        fun onAerialPaused(): Boolean {
            aerialResumed = false
            aerialPausedAt = SystemClock.uptimeMillis()
            val s = instance ?: return false
            s.updateKeyFilter()
            // Within the pause Home itself makes, not one the viewer makes after it by opening an app.
            if (s.backOnPause && SystemClock.uptimeMillis() - s.homeInAerialAt < HOME_PAUSE_MS) {
                s.homeInAerialAt = Long.MIN_VALUE / 2
                Log.i(TAG, "Aerial paused for Home; bringing it back before the stock launcher shows")
                s.goHome()
                return true
            }
            return false
        }
    }
}
