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
import app.orchard.tv.dream.AerialDream
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

    private const val KEY_REVIVALS = "revivals"

    private const val REVIVAL_WINDOW_MS = 10 * 60 * 1000L
    private const val KEY_KEEP_ADB = "keep_adb"

    /** When [ensureAdbListening] may still act: only boot shows the lying setting (issue #454). */
    private const val ADB_BOOT_WINDOW_MS = 3 * 60_000L
    private const val KEY_ADB_DANCED_AT = "adb_danced_at"
    private val adbDanceLock = Any()
    @Volatile private var adbDancing = false

    /** Test hook: probe another port to watch the toggle without closing a working ADB link. */
    @Volatile var adbProbePort = 5555

    private const val KEY_OWN_SCREENSAVER = "own_screensaver"
    private const val KEY_HOME_IN_FRONT = "home_in_front"
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
    private var screenOnWatch: BroadcastReceiver? = null

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOME_IN_FRONT, inFront).apply()

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
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = recentRevivals(context, now) + now
        prefs.edit().putString(KEY_REVIVALS, recent.joinToString(",")).apply()
    }

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
     * debugging also has the port closed, by choice.
     */
    private fun ensureAdbListening(context: Context) {
        if (adbDancing) return
        Thread {
            if (adbdListens(adbProbePort)) return@Thread
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            synchronized(adbDanceLock) {
                val now = SystemClock.elapsedRealtime()
                if (now > ADB_BOOT_WINDOW_MS) return@Thread
                if (prefs.getLong(KEY_ADB_DANCED_AT, -1L) in 0..now) return@Thread
                prefs.edit().putLong(KEY_ADB_DANCED_AT, now).apply()
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
     * Enabled but not running: seen on TCL after Aerial is updated, where Android keeps the
     * setting but never binds the new version. Writing the list without Aerial and then with it
     * makes Android bind it again.
     */
    fun rebindIfStalled(context: Context) {
        if (HomeGuardService.isRunning) return
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

    /** Switches accessibility off and, a moment later, on again, so Android binds every service anew. */
    fun restartAccessibility(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
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

    /** Returns whether Home Guard had been switched off and was put back. */
    fun ensureEnabled(context: Context): Boolean {
        ensureAdb(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        keepScreensaver(context)
        RevivalSync.ensure(context)
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
