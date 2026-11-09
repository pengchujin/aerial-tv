package app.orchard.tv.guard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import android.util.Log
import app.orchard.tv.MainActivity
import app.orchard.tv.dream.AerialDream
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.SystemScreensaver

/**
 * Turns Home Guard back on after the firmware turned it off.
 *
 * Android removes an app's accessibility services from the enabled list whenever the app is
 * force-stopped, and Xiaomi's firmware force-stops every third-party app when the TV goes to
 * standby. With WRITE_SECURE_SETTINGS (a development permission tools/install.sh grants over ADB;
 * it cannot be granted any other way) Aerial can put the service back each time it starts.
 */
object GuardSetup {
    private const val TAG = "AerialGuard"

    /** elapsedRealtime of the last time the guard had to be switched back on. */
    @Volatile
    var lastRevivedAt = Long.MIN_VALUE / 2
        private set

    private const val PREFS = "system"

    /** JMGO's own accessibility service, which filters keys too; see rebindForJmgoKeys. */
    private const val JMGO_KEY_SERVICE_PACKAGE = "com.jmgo.systemapi"
    private const val JMGO_TV_PACKAGE = "com.jmgo.tv"
    private const val KEY_JMGO_KEY_REBIND_BOOT = "jmgo_key_rebind_boot"

    private const val KEY_REVIVALS = "revivals"

    private const val REVIVAL_WINDOW_MS = 10 * 60 * 1000L
    private const val KEY_KEEP_ADB = "keep_adb"
    private const val ENABLED_LISTENERS = "enabled_notification_listeners"

    /** When [ensureAdbListening] may still act: only boot shows the lying setting (issue #454). */
    private const val ADB_BOOT_WINDOW_MS = 3 * 60_000L
    /** After the screen lights, a while in which ADB switched off is the firmware's; see adbSwitchedOffInUse. */
    private const val ADB_WAKE_SLACK_MS = 60_000L
    private val adbDanceLock = Any()
    @Volatile private var adbDancing = false
    /** Once per process, which within the boot window is once or twice per boot. */
    @Volatile private var adbDanced = false

    /** Test hook: probe another port to watch the toggle without closing a working ADB link. */
    @Volatile var adbProbePort = 5555

    private const val KEY_OWN_SCREENSAVER = "own_screensaver"
    private const val KEY_HOME_IN_FRONT = "home_in_front"
    private const val KEY_HOME_IN_FRONT_AT = "home_in_front_at"
    private const val KEY_HOME_IN_FRONT_BOOT = "home_in_front_boot"
    private const val KEY_STOCK_HOME_AT = "stock_home_at"
    private const val KEY_STOCK_HOME_BOOT = "stock_home_boot"
    private const val KEY_INSTALLER_OPENED = "installer_opened_at"
    private const val KEY_SCREENSAVER_SWITCHED_OFF = "screensaver_switched_off"
    private const val KEY_SCREENSAVER_ENABLED = "screensaver_enabled"
    private const val KEY_OFF_IN_STANDBY = "guard_off_in_standby"

    /**
     * Opt-in, over ADB (ConfigReceiver): Home Guard takes itself out of the accessibility list when
     * the screen goes off and is put back when it lights. A TCL tc8000_ay30a2 (Android 11) never
     * woke from standby while the guard was enabled, with or without its key filter, the app freezer
     * or anything else of Aerial's running; with the guard disabled it woke every time (issue #371;
     * the same on tcl_mt5879_cn firmware V745 but not V472, #132).
     */
    fun offInStandby(context: Context): Boolean = isUserUnlocked(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OFF_IN_STANDBY, false)

    fun setOffInStandby(context: Context, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFF_IN_STANDBY, off).commit()
    }

    /** Whether the guard is to stay out now: opted in and the screen is off. */
    private fun standingOut(context: Context): Boolean =
        offInStandby(context) && context.getSystemService<PowerManager>()?.isInteractive == false

    /**
     * Puts the guard back at SCREEN_ON while it is out for standby. Registered on the application
     * context, so it outlives the guard, which is unbound once out of the list. If the firmware
     * kills the process in standby, the next start (the notification listener's rebind, the sync
     * adapter) comes through ensureEnabled, which registers it again while the screen is off.
     */
    @Volatile
    private var screenOnWatch: BroadcastReceiver? = null

    // Synchronized: BootReceiver calls ensureEnabled off the main thread.
    @Synchronized
    private fun watchScreenOn(context: Context) {
        if (screenOnWatch != null) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (screenOnWatch !== this) return
                runCatching { app.unregisterReceiver(this) }
                screenOnWatch = null
                Log.i(TAG, "screen on; putting Home Guard back after standby")
                ensureEnabled(app)
            }
        }
        screenOnWatch = receiver
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        // Lit in between: the broadcast is already gone.
        if (context.getSystemService<PowerManager>()?.isInteractive == true) receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
    }

    /** Called by the guard at SCREEN_OFF: takes it out of the list and waits for the screen. */
    fun stepOutForStandby(service: android.accessibilityservice.AccessibilityService) {
        Log.i(TAG, "screen off; taking Home Guard out for standby")
        watchScreenOn(service)
        // disableSelf has the system drop the service from the list and unbind it at once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && runCatching { service.disableSelf() }.isSuccess) return
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(service, HomeGuardService::class.java).flattenToString()
        val resolver = service.contentResolver
        runCatching {
            val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it)?.flattenToString() != me }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
        }.onFailure { Log.w(TAG, "could not take Home Guard out", it) }
    }

    /**
     * Kept on disk while Aerial's home screen is in front, so a new process can tell how the last
     * one ended: still set means it died in front (a crash, an update, the firmware killing it),
     * and whatever the viewer sees now was uncovered rather than chosen.
     */
    fun setHomeInFront(context: Context, inFront: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOME_IN_FRONT, inFront)
            .putLong(KEY_HOME_IN_FRONT_AT, SystemClock.elapsedRealtime())
            .putLong(KEY_HOME_IN_FRONT_BOOT, System.currentTimeMillis() - SystemClock.elapsedRealtime()).apply()

    /**
     * [wasHomeInFront], when it was written in this boot; null when it was not (a power-on, or a
     * clock set since then). Tells a guard bound at boot from one bound after the firmware killed
     * Aerial while the viewer was in an app.
     */
    fun homeInFrontThisBoot(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_HOME_IN_FRONT_AT, -1L)
        val boot = prefs.getLong(KEY_HOME_IN_FRONT_BOOT, 0L)
        val bootNow = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (at !in 0..SystemClock.elapsedRealtime() || kotlin.math.abs(boot - bootNow) > 2 * 60 * 1000L) return null
        return prefs.getBoolean(KEY_HOME_IN_FRONT, false)
    }

    /**
     * The guard saw a stock launcher's home come to the front; kept on disk, as the firmware may
     * stop Aerial a moment later (see [stockHomeSinceHomeLeft]).
     */
    fun noteStockHomeInFront(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STOCK_HOME_AT, SystemClock.elapsedRealtime())
            .putLong(KEY_STOCK_HOME_BOOT, System.currentTimeMillis() - SystemClock.elapsedRealtime()).apply()

    /**
     * Whether, in this boot, a stock launcher's home came to the front after Aerial's Home last left
     * it. Xiaomi MiTV-MTEQ0 (Android 10) pushed PatchWall back over Aerial 40 s after power-on and
     * stopped Aerial 2 s later; revived, the guard read "Home was not in front when Aerial died" as
     * an app the viewer had opened and left PatchWall there (issue #1047, the reporter's boot log).
     */
    fun stockHomeSinceHomeLeft(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_STOCK_HOME_AT, -1L)
        val bootNow = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (at !in 0..SystemClock.elapsedRealtime() || kotlin.math.abs(prefs.getLong(KEY_STOCK_HOME_BOOT, 0L) - bootNow) > 2 * 60 * 1000L) return false
        return at >= prefs.getLong(KEY_HOME_IN_FRONT_AT, -1L)
    }

    /** Whether the guard saw a stock launcher's home come to the front in this boot within the last [ms]. */
    fun stockHomeSeenWithin(context: Context, ms: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_STOCK_HOME_AT, -1L)
        val bootNow = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        if (at !in 0..SystemClock.elapsedRealtime() || kotlin.math.abs(prefs.getLong(KEY_STOCK_HOME_BOOT, 0L) - bootNow) > 2 * 60 * 1000L) return false
        return SystemClock.elapsedRealtime() - at < ms
    }

    /**
     * The listener's process came back within seconds of the guard seeing the stock launcher: the
     * firmware force-stopped Aerial on a Home press. Dangbei Max1 (RK3568, Android 11) does so about
     * a second after every press; the process is back in 0.4 s, but Android often takes 8 s more to
     * bind the guard, 11 s to Aerial in all (issue #1245, the reporter's measurements). The screen
     * has nothing else to show, so start Aerial now and let the guard bind behind it. Costs a stock
     * launcher visit that Aerial's process does not outlast, as #1276's take-over already does.
     */
    fun startAfterStopOverStock(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || HomeGuardService.isRunning) return false
        if (SystemClock.elapsedRealtime() < 3 * 60_000L) return false
        if (context.getSystemService<PowerManager>()?.isInteractive != true) return false
        if (!stockHomeSinceHomeLeft(context) || !stockHomeSeenWithin(context, 5_000L)) return false
        return runCatching { context.startActivity(MainActivity.bringBackIntent(context)) }.isSuccess
    }

    fun wasHomeInFront(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOME_IN_FRONT, false)

    /**
     * Control Center opened the system installer on a new Aerial (update/Updates.kt). When the
     * installer is done, Aerial has been replaced and its task is gone, so whatever was under it
     * shows; on Xiaomi MiTV-MFTR0 that was a PatchWall details page, not its home activity. The
     * guard of the new Aerial brings Home back then ([installerJustClosed]). Written at once: the
     * install kills this process.
     */
    fun noteInstallerOpened(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_INSTALLER_OPENED, System.currentTimeMillis()).commit()
    }

    /** Whether Control Center opened the installer in the last 15 minutes; asks only once. */
    fun installerJustClosed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_INSTALLER_OPENED, 0L)
        if (at == 0L) return false
        prefs.edit().remove(KEY_INSTALLER_OPENED).apply()
        return System.currentTimeMillis() - at in 0..15 * 60_000L
    }

    /**
     * Some firmwares (Xiaomi) switch ADB debugging off on their own, usually around standby.
     * When the owner opts in (tools/install.sh does, with KEEP_ADB=1), Aerial switches it back on.
     */
    /**
     * Notes that the notification listener brought Aerial back after it was stopped. LeTV's eUI
     * (X43, issue #63) stops Aerial every few seconds to minutes; each time Aerial came back, saw
     * nothing in front it knew, took Home, and pulled the viewer out of the app they were in.
     */
    fun noteRevival(context: Context) {
        // Brought back with the screen off: a standby kill (Xiaomi), not eUI stopping Aerial in use.
        // Counting those, a few standbys in a row made the guard leave PatchWall in front at wake.
        // Not getSystemService(Class): that is Android 6.0, and LeTV and old VIDAA TVs revive on 5.x.
        if (context.getSystemService<PowerManager>()?.isInteractive == false) return
        // One stop, one count: the sync framework and the notification listener both bring the
        // same process back, a second apart. Nor is starting at power-on a stop. On Xiaomi
        // MiTV-ASTP0 (Android 9) the boot and the first wake counted three, and PatchWall stayed
        // in front after that wake (issue #470). Nor is an update installed over Aerial.
        if (revivalNoted || SystemClock.elapsedRealtime() < BOOT_START_MS) return
        revivalNoted = true
        val updatedAt = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (System.currentTimeMillis() - updatedAt in 0..BOOT_START_MS) return
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = recentRevivals(context, now) + now
        prefs.edit().putString(KEY_REVIVALS, recent.joinToString(",")).apply()
    }

    /** Whether this process has counted itself as revived; see noteRevival. */
    @Volatile
    private var revivalNoted = false
    private const val BOOT_START_MS = 2 * 60 * 1000L

    /** Whether the firmware has stopped Aerial three times or more in the last ten minutes. */
    fun revivedOften(context: Context): Boolean = recentRevivals(context, System.currentTimeMillis()).size >= 3

    /**
     * Only since this boot: stops before a restart say nothing about the firmware now. A few
     * reinstalls just before rebooting left the guard leaving the stock launcher in front at boot
     * (Hisense VIDAA_TV, MT9653, issue #261).
     */
    private fun recentRevivals(context: Context, now: Long): List<Long> {
        val bootedAt = now - SystemClock.elapsedRealtime()
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REVIVALS, null).orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }.filter { now - it in 0..REVIVAL_WINDOW_MS && it >= bootedAt }
    }

    fun keepAdb(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_ADB, false)

    fun setKeepAdb(context: Context, keep: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_ADB, keep).apply()
        if (keep) ensureAdb(context)
    }

    /**
     * ADB switched off with the TV in use: the viewer did it, or a settings page does it on its
     * way to switching it on again. keep_adb putting it back at once fought both: on TCL 98Q10M
     * Pro (MT9655, Android 14, issue #1399) the hidden ADB page then failed every time ("获取失败"),
     * and the setting could not be switched off without the ADB it kept from working. So the
     * switch-off is taken as meant and keep_adb ends. Not in standby, nor in the first minutes
     * of a boot or a wake, where firmware switches it off on its own (keep_adb's reason to be).
     */
    fun adbSwitchedOffInUse(context: Context, screenOnForMs: Long): Boolean {
        if (adbDancing || !isUserUnlocked(context) || !keepAdb(context)) return false
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1) != 0) return false
        if (SystemClock.elapsedRealtime() < ADB_BOOT_WINDOW_MS || screenOnForMs < ADB_WAKE_SLACK_MS) return false
        if ((context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != true) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_ADB, false).apply()
        Log.i(TAG, "ADB debugging switched off with the TV in use; keep_adb is off now")
        DiagLog.i("guard: ADB switched off with the TV in use; keep_adb switched off, not turning it back on")
        return true
    }

    /**
     * Home Guard is bound before the user is unlocked (Direct Boot). Until then the preferences
     * below, in credential storage, cannot be read; the guard catches up on unlock.
     */
    fun isUserUnlocked(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            context.getSystemService(UserManager::class.java).isUserUnlocked

    fun ensureAdb(context: Context) {
        if (adbDancing) return // the toggle below writes a 0 on purpose; nothing may undo it
        if (!isUserUnlocked(context) || !keepAdb(context)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 0) {
            runCatching {
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
                Log.i(TAG, "ADB debugging had been switched off; turned it back on")
            }.onFailure { Log.w(TAG, "could not turn ADB back on", it) }
            return
        }
        ensureAdbListening(context)
    }

    /**
     * adb_enabled=1 does not mean adbd listens: after a reboot the MiTV4-ANSM0 firmware
     * (Android 6) left adbd out of TCP mode, and only a 0→1 transition of the setting brought
     * it back (issue #454) — Aerial writing 1 over an unchanged 1 restarts nothing. Probe the
     * port, and when it is closed walk the setting through 0 and back. Only in the first
     * minutes after a boot, and once: that is when the setting can lie; a TV used over USB
     * debugging also has the port closed, by choice. Up to Android 10: from 11 network ADB is
     * mostly wireless debugging on a port of its own, 5555 is closed by design, and switching
     * ADB off ends wireless debugging too. From Android 6: on Xiaomi Mi TV 3 (MiTV3_48, Android
     * 5.0.1) the walk through 0 stopped adbd in TCP mode for good, and ADB could not be reached
     * again until keep_adb was switched off (issue #1099, the reporter's agent).
     */
    private fun ensureAdbListening(context: Context) {
        if (adbDancing || adbDanced || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        ) return
        if (SystemClock.elapsedRealtime() > ADB_BOOT_WINDOW_MS) return
        Thread {
            if (adbdListens(adbProbePort)) return@Thread
            synchronized(adbDanceLock) {
                if (adbDanced || SystemClock.elapsedRealtime() > ADB_BOOT_WINDOW_MS) return@Thread
                adbDanced = true
                adbDancing = true
                try {
                    runCatching {
                        // The observer in HomeGuardService sees the 0 and would put the 1 back
                        // at once; adbDancing holds it off until the 0 has done its work.
                        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                        Thread.sleep(1_000)
                        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                        Log.i(TAG, "adb_enabled was on but adbd was not listening; toggled it to restart it")
                    }.onFailure { Log.w(TAG, "could not toggle ADB debugging", it) }
                } finally {
                    adbDancing = false
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** adbd answering on the TV itself; a refusal means it is not in TCP mode. */
    internal fun adbdListens(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 400) }
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Android 5.x: the guard is enabled in settings, yet the accessibility manager lists no such
     * service, shows "no services installed" and writes accessibility_enabled back to 0. Hisense
     * VIDAA MSD6A828 (5.1.1) did so after every boot until Aerial was installed again, which makes
     * it read the installed services again (issue #578); Skyworth 8S70 (5.0.1) went without the
     * guard until Aerial was revived minutes later (#579). A package change makes it read them as
     * an install does, so Aerial changes one of its own components' state there and back.
     */
    fun rescanIfMissing(context: Context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) return
        val me = ComponentName(context, HomeGuardService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
        if (!enabled) return
        val installed = runCatching {
            context.getSystemService<android.view.accessibility.AccessibilityManager>()?.installedAccessibilityServiceList
                ?.any { it.resolveInfo?.serviceInfo?.let { s -> s.packageName == me.packageName && s.name == me.className } == true }
        }.getOrNull() ?: return
        if (installed) return
        Log.i(TAG, "the guard is enabled but Android lists no such service; asking it to read the services again")
        val pm = context.packageManager
        val marker = ComponentName(context, BootReceiver::class.java)
        runCatching {
            pm.setComponentEnabledSetting(marker, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { pm.setComponentEnabledSetting(marker, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP) }
            }, 1500)
        }.onFailure { Log.w(TAG, "could not change a component's state", it) }
    }

    /**
     * Enabled but not running: seen on TCL after Aerial is updated, where Android keeps the
     * setting but never binds the new version. Writing the list without Aerial and then with it
     * makes Android bind it again.
     */
    fun rebindIfStalled(context: Context) {
        if (HomeGuardService.isRunning) return
        rescanIfMissing(context)
        if (standingOut(context)) { watchScreenOn(context); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
        val resolver = context.contentResolver
        val entries = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }
        val others = entries.filter { ComponentName.unflattenFromString(it)?.flattenToString() != me }
        if (others.size == entries.size) return // not enabled at all; ensureEnabled handles that
        runCatching {
            // A guard that had to be bound again counts as revived: whatever is in front came up
            // while it was not watching (MiTV-MFTR0, Android 11: woken from standby after Aerial's
            // screensaver, PatchWall stayed in front once the guard was back, issue #47).
            lastRevivedAt = SystemClock.elapsedRealtime()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            // Two writes in a row are coalesced and Android sees no change; give it a moment between.
            // Read the list again then, so a service another app added meanwhile is kept (#57).
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    val now = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                        .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it)?.flattenToString() != me }
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (now + me).joinToString(":"))
                }
            }, 1500)
            Log.i(TAG, "Home Guard was enabled but not running; asked Android to bind it again")
        }
    }

    /**
     * Switches accessibility off and, a moment later, on again, so Android binds every service anew.
     * Past Android 6 accessibility_enabled no longer unbinds anything: on Xiaomi Box 5 Max
     * (MiTV-AZFU0, Android 14) the guard stayed deaf after it (issue #651). There Aerial is taken
     * out of the list and put back, as rebindIfStalled does.
     */
    fun restartAccessibility(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
            fun others() = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it)?.flattenToString() != me }
            runCatching {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others().joinToString(":"))
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others() + me).joinToString(":")) }
                        .onFailure { Log.w(TAG, "could not put Home Guard back", it) }
                }, 1500)
            }.onFailure { Log.w(TAG, "could not take Home Guard out", it) }
            return
        }
        runCatching {
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            // See rebindIfStalled: two writes in a row are coalesced.
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1) }
                    .onFailure { Log.w(TAG, "could not switch accessibility back on", it) }
            }, 1500)
        }.onFailure { Log.w(TAG, "could not switch accessibility off", it) }
    }

    /**
     * Whether the firmware is set to open a signal source by itself at power-on: JMGO's Settings
     * keeps "Startup Source" in persist.sys.boot.source, 0 being its own Home. The TV then starts
     * its input player seconds before BOOT_COMPLETED, and Aerial must not come over it.
     */
    fun startsOnSignalSource(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(JMGO_TV_PACKAGE, 0) }.isSuccess &&
            runCatching {
                val getInt = Class.forName("android.os.SystemProperties")
                    .getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
                getInt.invoke(null, "persist.sys.boot.source", 0) as Int
            }.getOrDefault(0) != 0

    /**
     * JMGO S60 (Amlogic T968, Android 5.1): its SystemApi accessibility service asks for key
     * filtering at runtime, and up to Android 6 a key goes to one filtering service only, the last
     * bound first and those not marked default before the default one. Bound at boot, Aerial's
     * service was the first filtered one, so the system handled Home itself and the stock launcher
     * flashed before the guard's key broadcast brought Aerial back. Switching accessibility off and
     * on once made the guard take every Home instead (verified there with a real key event).
     * Done once per boot, named by the kernel's boot id: this device starts at 1970, so its wall
     * clock cannot tell one boot from the next. Called from the guard when it sees the input
     * player, since the firmware opens that without Aerial's activity ever running.
     */
    fun rebindForJmgoKeys(context: Context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) return
        if (runCatching { context.packageManager.getPackageInfo(JMGO_KEY_SERVICE_PACKAGE, 0) }.isFailure) return
        val resolved = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        }.getOrNull()
        if (resolved?.activityInfo?.packageName == context.packageName) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val bootId = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_JMGO_KEY_REBIND_BOOT, null) == bootId) return
        // Marked first: the switch below unbinds the guard, and the next one must not repeat it.
        prefs.edit().putString(KEY_JMGO_KEY_REBIND_BOOT, bootId).commit()
        DiagLog.i("jmgo keys: rebinding accessibility so the remote's keys reach the guard")
        restartAccessibility(context)
    }

    /**
     * Whether Aerial owns the TV's system screensaver (tools/install.sh sets it when it writes
     * screensaver_components; tools/restore.sh clears it). TCL MT5879 writes its own screensaver
     * back on every boot, so Aerial puts itself back each time it starts.
     */
    fun setOwnScreensaver(context: Context, own: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OWN_SCREENSAVER, own).apply()
        if (own) keepScreensaver(context)
    }

    private fun keepScreensaver(context: Context) {
        if (!isUserUnlocked(context)) return
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "screensaver_components"
        val current = Settings.Secure.getString(resolver, key).orEmpty()
        val mine = ComponentName(context, AerialDream::class.java).flattenToString()
        // 1.6 and earlier: the class was OrchardDream, and install.sh had claimed the screensaver.
        val renamed = "${context.packageName}/${context.packageName}.dream.OrchardDream" in current
        if (renamed) prefs.edit().putBoolean(KEY_OWN_SCREENSAVER, true).apply()
        if (!prefs.getBoolean(KEY_OWN_SCREENSAVER, false) || current == mine) return
        // "System" chosen in Personalize: the firmware's dream stays (screensaverModeChanged).
        if (systemChosen(context)) return
        runCatching {
            Settings.Secure.putString(resolver, key, mine)
            Log.i(TAG, "the system screensaver was $current; put Aerial's back")
        }.onFailure { Log.w(TAG, "could not set the system screensaver", it) }
    }

    /**
     * "Never" for the screensaver switches the system's off too while it is Aerial's, since that is
     * what starts over other apps: on a Haier TV (Android 9) it came up over a phone casting to the
     * TV, and no delay in Personalize could stop it (issue #333). Switched back on when a delay is
     * chosen again, and only if Aerial was the one that switched it off.
     */
    fun setSystemScreensaverOff(context: Context, off: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            if (off) {
                val mine = ComponentName(context, AerialDream::class.java).flattenToString()
                if (Settings.Secure.getString(resolver, "screensaver_components") != mine) return
                if (Settings.Secure.getInt(resolver, KEY_SCREENSAVER_ENABLED, 1) == 0) return
                Settings.Secure.putInt(resolver, KEY_SCREENSAVER_ENABLED, 0)
                prefs.edit().putBoolean(KEY_SCREENSAVER_SWITCHED_OFF, true).apply()
                Log.i(TAG, "screensaver set to never; switched the system's off")
            } else if (prefs.getBoolean(KEY_SCREENSAVER_SWITCHED_OFF, false)) {
                Settings.Secure.putInt(resolver, KEY_SCREENSAVER_ENABLED, 1)
                prefs.edit().remove(KEY_SCREENSAVER_SWITCHED_OFF).apply()
                Log.i(TAG, "screensaver delay chosen; switched the system's back on")
            }
        }.onFailure { Log.w(TAG, "could not change the system screensaver", it) }
    }

    private fun systemChosen(context: Context) =
        context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).getString("screensaver", null) == "system"

    /**
     * The screensaver chosen in Personalize changed. "System" gives the TV's dream back to the one
     * the firmware came with, where Aerial had taken it (TCL); any other choice takes it again.
     */
    fun screensaverModeChanged(context: Context) {
        if (!isUserUnlocked(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_OWN_SCREENSAVER, false)) return
        if (!systemChosen(context)) { keepScreensaver(context); return }
        val resolver = context.contentResolver
        val mine = ComponentName(context, AerialDream::class.java).flattenToString()
        val firmware = SystemScreensaver.firmwareDream(context) ?: return
        if (Settings.Secure.getString(resolver, "screensaver_components").orEmpty() != mine) return
        runCatching {
            Settings.Secure.putString(resolver, "screensaver_components", firmware)
            Log.i(TAG, "screensaver set to the system's; the dream is $firmware again")
        }.onFailure { Log.w(TAG, "could not give the system screensaver back", it) }
    }

    /**
     * The firmware wrote its own dream back while Aerial's was chosen: TCL MT5879 put back its
     * art screensaver at boot once, after Aerial had looked (watched by Home Guard).
     */
    fun screensaverSettingChanged(context: Context) = keepScreensaver(context)

    /**
     * A persisted component override survives an APK update. On Skyworth 8N312_H5D the boot
     * receiver was disabled even though the manifest enables it. Android 8's shell cannot
     * change another app's component, but the owning app can clear its own override. Vidda's
     * startup manager also disables ConfigReceiver (V3K_PRO, Android 11), preventing even the
     * owner's protected ADB configuration broadcasts from being delivered. Only do
     * so after the owner has granted the ADB takeover permission, as for the guard itself.
     */
    private fun restoreReceivers(context: Context) {
        restoreReceiversNow(context)
        // Vidda's startup manager disables them again about 2 s after a wake, after the screen-on
        // and onStart that come here had looked; only the notification listener's later rebind
        // found them, and that not on every wake (Vidda 75E5K, MT9653, Android 11, issue #1181,
        // the reporter's timeline). Look again once the firmware is done.
        val app = context.applicationContext
        receiverRecheck.removeCallbacksAndMessages(null)
        for (delay in RECEIVER_RECHECK_MS) receiverRecheck.postDelayed({ restoreReceiversNow(app) }, delay)
    }

    private val receiverRecheck by lazy { Handler(Looper.getMainLooper()) }
    private val RECEIVER_RECHECK_MS = longArrayOf(5_000L, 15_000L)

    private fun restoreReceiversNow(context: Context) {
        val pm = context.packageManager
        for (receiver in listOf(BootReceiver::class.java, ConfigReceiver::class.java)) {
            val component = ComponentName(context, receiver)
            runCatching {
                val state = pm.getComponentEnabledSetting(component)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return@runCatching
                pm.setComponentEnabledSetting(
                    component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP,
                )
                DiagLog.i("revival: cleared disabled ${receiver.simpleName} override (was $state)")
            }.onFailure {
                DiagLog.e("revival: could not restore ${receiver.simpleName}", it)
            }
        }
    }

    /** Returns whether Home Guard had been switched off and was put back. */
    /**
     * Before `cmd notification` (Android 7) the install writes the listener with `settings put`,
     * which Haier HRA920L (YunOS 5.1, Android 5.1) refuses to the shell even with the guard on; its
     * list also starts with a stray literal "null" (issue #1301, the reporter's agent). Aerial holds
     * WRITE_SECURE_SETTINGS there, so add itself after what is there, which stays as it is.
     */
    private fun ensureListenerBeforeNougat(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return
        val me = ComponentName(context, RevivalListener::class.java).flattenToString()
        val current = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS).orEmpty()
        if (current.split(':').any { it == me }) return
        runCatching {
            Settings.Secure.putString(context.contentResolver, ENABLED_LISTENERS, if (current.isEmpty()) me else "$current:$me")
            Log.i(TAG, "notification listener was not allowed; added it")
        }.onFailure { Log.w(TAG, "could not allow the notification listener", it) }
    }

    fun ensureEnabled(context: Context): Boolean {
        ensureAdb(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        restoreReceivers(context)
        keepScreensaver(context)
        RevivalSync.ensure(context)
        ensureListenerBeforeNougat(context)
        if (standingOut(context)) { watchScreenOn(context); return false }
        val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val alreadyOn = entries.any { ComponentName.unflattenFromString(it)?.flattenToString() == me }
        return runCatching {
            if (!alreadyOn) {
                lastRevivedAt = SystemClock.elapsedRealtime()
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (entries + me).joinToString(":"))
                Log.i(TAG, "Home Guard had been switched off; turned it back on")
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            !alreadyOn
        }.onFailure { Log.w(TAG, "could not re-enable Home Guard", it) }.getOrDefault(false)
    }
}
