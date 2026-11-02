package app.orchard.tv.guard

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import app.orchard.tv.MainActivity
import app.orchard.tv.R
import app.orchard.tv.dream.AerialDream
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.KidsLock
import app.orchard.tv.settings.Restricted
import app.orchard.tv.system.DiagLog
import app.orchard.tv.system.SystemProbe

/**
 * Kids and Elders mode while the TV is on, wherever it is (Restricted): counts the time spent in
 * apps and inputs (not on Aerial's Home or its screensaver), starts breaks, warns 5 and 1 minutes
 * before a lock, and brings Aerial back over whatever is in front once locked.
 *
 * Other apps are kept away by Home Guard as their windows come up (HomeGuardService). Without it,
 * where usage stats may be read, the app in front is looked at every few seconds instead; without
 * either, only Home itself is limited and the time is counted from Aerial's own pauses.
 *
 * Runs in Aerial's process on the main thread, started by Home and by Home Guard, whichever comes first.
 */
object KidsWatch {
    private const val TICK_MS = 10_000L

    /** Without Home Guard, how often usage stats are read for an app to keep away. */
    private const val USAGE_TICK_MS = 2_000L

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var ticking = false
    private var lastTickAt = 0L
    private var idleMs = 0L

    /** The last warning's step (5 or 1 minutes), so each is said once; 0 when none is due. */
    private var warnedStep = 0
    private var broughtBackAt = Long.MIN_VALUE / 2

    /** Aerial's Home is resumed (MainActivity): time there is not counted. */
    var homeInFront = false
        set(value) {
            field = value
            // Out to an app or back: look now rather than up to a tick later.
            if (app != null) handler.post { tick() }
        }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A parent who left the mode is gone once the TV sleeps.
            Restricted.rearm()
        }
    }

    private val loop = Runnable { tickAndPlan() }

    fun start(context: Context) {
        if (app == null) {
            app = context.applicationContext
            AerialSettings.init(context)
            Restricted.init(context)
            ContextCompat.registerReceiver(app!!, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        poke()
    }

    /** The mode or its rules changed: look again now, and keep looking while it is on. */
    fun poke() {
        if (app == null) return
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun tickAndPlan() {
        tick()
        if (!Restricted.enabled) {
            ticking = false
            Restricted.lock = null
            return
        }
        ticking = true
        handler.removeCallbacks(loop)
        handler.postDelayed(loop, if (watchesUsage()) USAGE_TICK_MS else TICK_MS)
    }

    private fun tick() {
        val ctx = app ?: return
        val now = System.currentTimeMillis()
        val at = SystemClock.elapsedRealtime()
        val since = if (lastTickAt == 0L) 0L else (at - lastTickAt).coerceAtLeast(0L)
        lastTickAt = at
        val interactive = ctx.getSystemService<PowerManager>()?.isInteractive != false
        val watching = Restricted.active && interactive && !homeInFront && !AerialDream.showing
        if (watching) {
            idleMs = 0
            // A gap longer than two ticks is the process held up (standby), not watching.
            Restricted.count(since.coerceAtMost(2 * TICK_MS), 0, now)
        } else {
            idleMs += since
            Restricted.count(0, idleMs, now)
        }

        val lock = Restricted.lockNow(now)
        if (lock != Restricted.lock) DiagLog.i("kids: ${lockName(lock)}")
        Restricted.lock = lock
        if (lock != null && interactive && !homeInFront) bringBack(ctx, "locked (${lockName(lock)})")

        if (watching && lock == null) warnIfDue(ctx, now) else if (lock != null) warnedStep = 0
        if (watching && watchesUsage()) keepAwayByUsage(ctx)
    }

    private fun warnIfDue(ctx: Context, now: Long) {
        val until = Restricted.untilLockMs(now)
        val step = when {
            until == null || until > 5 * 60_000L -> 0
            until > 60_000L -> 5
            else -> 1
        }
        if (step != 0 && (warnedStep == 0 || step < warnedStep)) {
            val minutes = ((until!! + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            val rules = Restricted.rules
            val usage = Restricted.usage
            val toBreak = rules.breakEvery > 0 && usage.allowUntil <= now &&
                rules.breakEvery * 60_000L - usage.continuousMs == until
            warn(ctx, ctx.getString(if (toBreak) R.string.kids_warn_break else R.string.kids_warn, minutes))
        }
        warnedStep = step
    }

    private fun warn(ctx: Context, text: String) {
        DiagLog.i("kids: warning \"$text\"")
        when {
            homeInFront -> Restricted.warning = text to SystemClock.uptimeMillis()
            HomeGuardService.showBanner(text) -> Unit
            else -> runCatching { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
        }
    }

    private fun bringBack(ctx: Context, why: String) {
        val now = SystemClock.uptimeMillis()
        if (now - broughtBackAt < 3_000) return
        broughtBackAt = now
        DiagLog.i("kids: bringing Aerial back, $why")
        if (HomeGuardService.bringAerialBack()) return
        runCatching { ctx.startActivity(MainActivity.bringBackIntent(ctx)) }
            .onFailure { DiagLog.i("kids: could not bring Aerial back: $it") }
    }

    private val appCache = HashMap<String, Boolean>()

    /**
     * Whether the mode keeps [pkg] off the screen: once locked every app, else whatever is not
     * allowed, when it is an app (it has a launcher entry), the TV's settings or an input's player.
     * Dialogs, keyboards, permission prompts and the system's own windows go through.
     */
    fun keepsAway(ctx: Context, pkg: String): Boolean {
        if (!Restricted.active || pkg == ctx.packageName) return false
        if (Restricted.lock == null && (!Restricted.blockOthers || pkg in Restricted.allowedPackages)) return false
        if (pkg == keyboard(ctx)) return false
        return pkg in Restricted.sourcePlayers || SystemProbe.isSettingsApp(ctx, pkg) || appCache.getOrPut(pkg) {
            val pm = ctx.packageManager
            runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) != null || pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
        }
    }

    private fun keyboard(ctx: Context): String? =
        runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) }.getOrNull()
            ?.substringBefore('/')

    /** Home Guard said it kept [pkg] away: Home names it. */
    fun noteKeptAway(ctx: Context, pkg: String) {
        val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        DiagLog.i("kids: kept $pkg away")
        // Locked, the lock screen says why; naming the app would only add to it.
        if (Restricted.lock == null) Restricted.blocked = label to SystemClock.uptimeMillis()
    }

    /** Without Home Guard, usage stats say what is in front. */
    private fun watchesUsage(): Boolean =
        Restricted.active && (Restricted.blockOthers || Restricted.lock != null) && !HomeGuardService.isRunning &&
            !homeInFront && app?.let { HomeGuardService.usageStatsAllowed(it) } == true

    private fun keepAwayByUsage(ctx: Context) {
        val front = frontByUsage(ctx) ?: return
        if (!keepsAway(ctx, front)) return
        noteKeptAway(ctx, front)
        bringBack(ctx, "$front in front (usage stats)")
    }

    private fun frontByUsage(ctx: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT < 22) return null
        val now = System.currentTimeMillis()
        val events = ctx.getSystemService<UsageStatsManager>()?.queryEvents(now - 60_000L, now) ?: return null
        val e = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName
        }
        last
    }.getOrNull()

    /** Spelled out: R8 renames the classes. */
    private fun lockName(lock: KidsLock?) = when (lock) { is KidsLock.Break -> "break"; KidsLock.DayUsed -> "day used"; KidsLock.Late -> "late"; null -> "unlocked" }

    /** What the lock says, for the diagnostics. */
    fun diagnosticState(): String {
        val r = Restricted
        if (!r.enabled) return "Kids mode off"
        val lock = lockName(r.lock)
        return "Kids mode ${r.kind.key} active=${r.active} allowed=${r.allowed.size} block=${r.blockOthers} rules=${r.rules} " +
            "used=${r.usage.usedMs / 60_000} min continuous=${r.usage.continuousMs / 60_000} min lock=$lock ticking=$ticking"
    }
}
