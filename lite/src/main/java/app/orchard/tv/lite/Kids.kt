package app.orchard.tv.lite

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.util.Calendar
import java.util.TimeZone

/** The full app's RestrictedKind. */
enum class KidsKind(val key: String) {
    Kids("kids"), Elders("elders");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Kids
    }
}

/** The full app's KidsLock. */
sealed class KidsLock {
    class Break(val untilMs: Long) : KidsLock()
    object DayUsed : KidsLock()
    object Late : KidsLock()

    val logName: String get() = when (this) { is Break -> "break"; DayUsed -> "day used"; Late -> "late" }
}

/** The full app's RestrictedRules: any minutes, a window in minutes of the day ([endMinute] 0 for all day). */
data class KidsRules(
    val weekdayMinutes: Int = 0,
    val weekendMinutes: Int = 0,
    val startMinute: Int = KidsTime.DEFAULT_START,
    val endMinute: Int = 0,
    val breakEvery: Int = 0,
    val breakMinutes: Int = KidsTime.BREAK_MINUTES,
) {
    val any: Boolean get() = weekdayMinutes > 0 || weekendMinutes > 0 || endMinute > 0 || breakEvery > 0
    val valid: Boolean get() = weekdayMinutes in 0..KidsTime.DAY && weekendMinutes in 0..KidsTime.DAY &&
        startMinute in 0 until KidsTime.DAY && (endMinute == 0 || endMinute in startMinute + 1 until KidsTime.DAY) &&
        breakEvery in 0..KidsTime.DAY && breakMinutes in 1..120
}

/** The full app's KidsUsage. */
data class KidsUsage(val usedMs: Long = 0, val continuousMs: Long = 0, val breakUntil: Long = 0, val allowUntil: Long = 0)

/**
 * The full app's KidsTime, with Calendar in place of java.time (which would need desugaring on 4.x).
 * The same rules and numbers; see settings/Restricted.kt there.
 */
object KidsTime {
    const val DAY = 24 * 60
    const val DEFAULT_START = 6 * 60
    const val BREAK_MINUTES = 5

    fun clock(minute: Int) = "%d:%02d".format(minute / 60, minute % 60)

    private fun calendar(ms: Long, zone: TimeZone) = Calendar.getInstance(zone).apply { timeInMillis = ms }

    fun limitMinutes(rules: KidsRules, now: Long, zone: TimeZone = TimeZone.getDefault()): Int {
        val day = calendar(now, zone).get(Calendar.DAY_OF_WEEK)
        return if (day == Calendar.SATURDAY || day == Calendar.SUNDAY) rules.weekendMinutes else rules.weekdayMinutes
    }

    private fun late(rules: KidsRules, now: Long, zone: TimeZone): Boolean {
        if (rules.endMinute <= 0) return false
        val c = calendar(now, zone)
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return m < rules.startMinute || m >= rules.endMinute
    }

    private fun ruleLock(rules: KidsRules, usage: KidsUsage, now: Long, zone: TimeZone): KidsLock? {
        if (late(rules, now, zone)) return KidsLock.Late
        val limit = limitMinutes(rules, now, zone)
        if (limit > 0 && usage.usedMs >= limit * 60_000L) return KidsLock.DayUsed
        if (usage.breakUntil > now) return KidsLock.Break(usage.breakUntil)
        return null
    }

    fun lock(rules: KidsRules, usage: KidsUsage, now: Long, zone: TimeZone = TimeZone.getDefault()): KidsLock? =
        if (usage.allowUntil > now) null else ruleLock(rules, usage, now, zone)

    fun untilLockMs(rules: KidsRules, usage: KidsUsage, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (lock(rules, usage, now, zone) != null) return 0
        if (usage.allowUntil > now) {
            val end = usage.copy(usedMs = usage.usedMs + (usage.allowUntil - now), allowUntil = 0)
            return if (ruleLock(rules, end, usage.allowUntil, zone) != null) usage.allowUntil - now else null
        }
        val left = ArrayList<Long>()
        val limit = limitMinutes(rules, now, zone)
        if (limit > 0) left += limit * 60_000L - usage.usedMs
        if (rules.endMinute > 0) {
            val cutoff = calendar(now, zone).apply {
                set(Calendar.HOUR_OF_DAY, rules.endMinute / 60); set(Calendar.MINUTE, rules.endMinute % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            left += cutoff - now
        }
        if (rules.breakEvery > 0) left += rules.breakEvery * 60_000L - usage.continuousMs
        return left.filter { it > 0 }.minOrNull()
    }

    fun advance(rules: KidsRules, usage: KidsUsage, watchedMs: Long, idleMs: Long, now: Long): KidsUsage {
        var u = usage
        if (watchedMs > 0) u = u.copy(usedMs = u.usedMs + watchedMs, continuousMs = u.continuousMs + watchedMs)
        if (idleMs >= rules.breakMinutes * 60_000L) u = u.copy(continuousMs = 0)
        if (rules.breakEvery > 0 && u.continuousMs >= rules.breakEvery * 60_000L && u.allowUntil <= now) {
            u = u.copy(continuousMs = 0, breakUntil = now + rules.breakMinutes * 60_000L)
        }
        return u
    }

    /** "2026-11-02": the day the usage counts for. */
    fun dayOf(now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = calendar(now, zone)
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun startOfNextDay(now: Long, zone: TimeZone = TimeZone.getDefault()): Long = calendar(now, zone).apply {
        add(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * Kids and Elders mode in Lite, as the full app's Restricted and KidsWatch together: the settings,
 * the day's time, and the watch that counts it while Lite's process lives. Lite has no Home Guard:
 * up to Android 4.4 the app in front is read from the running tasks (GET_TASKS), which Android 5
 * stopped answering for other apps; there only Home itself is limited.
 *
 * Main thread only.
 */
object Kids {
    private const val PREFS = "restricted"
    private const val TICK_MS = 10_000L
    private const val FRONT_TICK_MS = 2_000L
    private const val TAG = "AerialLite"
    const val COLUMNS = 4

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var started = false
    private val handler = Handler(Looper.getMainLooper())

    var enabled = false; private set
    var kind = KidsKind.Kids; private set
    var allowed: List<String> = emptyList(); private set
    var allowedPackages: Set<String> = emptySet(); private set
    var blockOthers = true; private set
    var rules = KidsRules(); private set
    var suspended = false; private set
    val active: Boolean get() = enabled && !suspended && allowed.isNotEmpty()
    var usage = KidsUsage(); private set
    private var usageDay = ""
    var lock: KidsLock? = null; private set

    /** Home redraws for the mode coming or going, or the lock; set by HomeActivity. */
    var onChange: () -> Unit = {}
    /** A warning, or an app kept away, for Home to say while it is in front. */
    var onNotice: (String) -> Unit = {}

    /** HomeActivity is resumed: time there is not counted. */
    var homeInFront = false
        set(v) { field = v; if (started) handler.post(tick) }

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", false)
        kind = KidsKind.of(prefs.getString("kind", null))
        allowed = prefs.getString("allowed", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        allowedPackages = prefs.getStringSet("allowed_packages", null)?.toSet() ?: emptySet()
        blockOthers = prefs.getBoolean("block_others", true)
        rules = KidsRules(
            prefs.getInt("weekday_minutes", 0), prefs.getInt("weekend_minutes", 0),
            prefs.getInt("start_minute", KidsTime.DEFAULT_START), prefs.getInt("end_minute", 0),
            prefs.getInt("break_every", 0), prefs.getInt("break_minutes", KidsTime.BREAK_MINUTES),
        ).takeIf { it.valid } ?: KidsRules()
        usageDay = prefs.getString("usage_day", "") ?: ""
        usage = KidsUsage(prefs.getLong("used_ms", 0), prefs.getLong("continuous_ms", 0), prefs.getLong("break_until", 0), prefs.getLong("allow_until", 0))
        rollDay(System.currentTimeMillis())
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = rearm()
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
        poke()
    }

    private fun put(block: SharedPreferences.Editor.() -> Unit) = prefs.edit().apply(block).apply()

    fun chooseEnabled(on: Boolean) { enabled = on; suspended = on; put { putBoolean("enabled", on) }; poke() }
    fun chooseKind(k: KidsKind) { kind = k; put { putString("kind", k.key) } }
    fun chooseBlockOthers(on: Boolean) { blockOthers = on; put { putBoolean("block_others", on) } }
    /** As the full app: new rules hold at once, ending time a parent allowed under the old ones. */
    fun chooseRules(r: KidsRules) {
        if (r != rules && usage.allowUntil > 0) saveUsage(usage.copy(allowUntil = 0))
        rules = r
        put {
            putInt("weekday_minutes", r.weekdayMinutes); putInt("weekend_minutes", r.weekendMinutes)
            putInt("start_minute", r.startMinute); putInt("end_minute", r.endMinute)
            putInt("break_every", r.breakEvery); putInt("break_minutes", r.breakMinutes)
        }
        poke()
    }
    fun chooseAllowed(keys: List<String>) {
        allowed = keys
        put { putString("allowed", allowed.joinToString("\n")) }
    }
    /** Home again after a change from the phone page. */
    fun changedElsewhere() = changed()
    fun toggleAllowed(key: String) {
        allowed = if (key in allowed) allowed - key else allowed + key
        put { putString("allowed", allowed.joinToString("\n")) }
    }
    fun notePackages(pkgs: Set<String>) {
        if (pkgs == allowedPackages) return
        allowedPackages = pkgs
        put { putStringSet("allowed_packages", pkgs) }
    }

    fun start() { if (enabled && suspended) { suspended = false; changed() } }
    fun suspend() { if (!suspended) { suspended = true; changed() } }
    fun rearm() { if (enabled && suspended) { suspended = false; changed() } }

    private fun changed() { poke(); onChange() }

    private fun rollDay(now: Long) {
        val day = KidsTime.dayOf(now)
        if (day == usageDay) return
        usageDay = day
        saveUsage(KidsUsage(allowUntil = usage.allowUntil.takeIf { it > now } ?: 0))
    }

    fun saveUsage(u: KidsUsage) {
        usage = u
        put {
            putString("usage_day", usageDay)
            putLong("used_ms", u.usedMs); putLong("continuous_ms", u.continuousMs)
            putLong("break_until", u.breakUntil); putLong("allow_until", u.allowUntil)
        }
    }

    fun allow(minutes: Int?) {
        val now = System.currentTimeMillis()
        rollDay(now)
        val until = if (minutes == null) KidsTime.startOfNextDay(now) else maxOf(now, usage.allowUntil) + minutes * 60_000L
        saveUsage(usage.copy(allowUntil = until, breakUntil = 0, continuousMs = 0))
        changed()
    }

    fun limitToday(now: Long = System.currentTimeMillis()) = KidsTime.limitMinutes(rules, now)

    // ---- the watch ----

    private var lastTickAt = 0L
    private var idleMs = 0L
    private var warnedStep = 0
    private var broughtBackAt = Long.MIN_VALUE / 2
    private val tick = Runnable { tickNow() }
    private val loop = Runnable { tickNow(); plan() }

    fun poke() {
        if (!started) return
        handler.removeCallbacks(loop)
        handler.post(loop)
    }

    private fun plan() {
        handler.removeCallbacks(loop)
        if (!enabled) { if (lock != null) { lock = null; onChange() }; return }
        handler.postDelayed(loop, if (readsFront()) FRONT_TICK_MS else TICK_MS)
    }

    @Suppress("DEPRECATION")
    private fun tickNow() {
        if (!started) return
        val now = System.currentTimeMillis()
        val at = SystemClock.elapsedRealtime()
        val since = if (lastTickAt == 0L) 0L else (at - lastTickAt).coerceAtLeast(0L)
        lastTickAt = at
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val interactive = if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else pm.isScreenOn
        val watching = active && interactive && !homeInFront
        rollDay(now)
        val next = if (watching) {
            idleMs = 0
            KidsTime.advance(rules, usage, since.coerceAtMost(2 * TICK_MS), 0, now)
        } else {
            idleMs += since
            KidsTime.advance(rules, usage, 0, idleMs, now)
        }
        if (next != usage) saveUsage(next)

        val newLock = if (active) KidsTime.lock(rules, usage, now) else null
        if (newLock?.logName != lock?.logName) {
            Log.i(TAG, "kids: ${newLock?.logName ?: "unlocked"}")
            lock = newLock
            onChange()
        } else lock = newLock
        if (newLock != null && interactive && !homeInFront) bringBack("locked (${newLock.logName})")

        if (watching && newLock == null) warnIfDue(now) else if (newLock != null) warnedStep = 0
        if (watching && readsFront()) keepAway()
    }

    private fun warnIfDue(now: Long) {
        val until = if (active) KidsTime.untilLockMs(rules, usage, now) else null
        val step = when {
            until == null || until > 5 * 60_000L -> 0
            until > 60_000L -> 5
            else -> 1
        }
        if (step != 0 && (warnedStep == 0 || step < warnedStep)) {
            val minutes = ((until!! + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
            val toBreak = rules.breakEvery > 0 && usage.allowUntil <= now && rules.breakEvery * 60_000L - usage.continuousMs == until
            val text = app.getString(if (toBreak) R.string.kids_warn_break else R.string.kids_warn, minutes)
            Log.i(TAG, "kids: warning \"$text\"")
            if (homeInFront) onNotice(text) else Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
        warnedStep = step
    }

    private fun bringBack(why: String) {
        val now = SystemClock.uptimeMillis()
        if (now - broughtBackAt < 3_000) return
        broughtBackAt = now
        Log.i(TAG, "kids: bringing Lite back, $why")
        runCatching {
            app.startActivity(Intent(app, HomeActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    }

    /** Up to Android 4.4 the running tasks name the app in front; from 5.0 only Lite's own. */
    private fun readsFront(): Boolean = Build.VERSION.SDK_INT < 21 && active && (blockOthers || lock != null) && !homeInFront

    @Suppress("DEPRECATION")
    private fun keepAway() {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pkg = runCatching { am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName }.getOrNull() ?: return
        if (!keepsAway(pkg)) return
        Log.i(TAG, "kids: kept $pkg away")
        if (lock == null) {
            val label = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            onNotice(app.getString(R.string.kids_blocked, label))
        }
        bringBack("$pkg in front")
    }

    private val appCache = HashMap<String, Boolean>()

    /** As the full app's KidsWatch.keepsAway: apps with a launcher entry and the settings; not keyboards or dialogs. */
    private fun keepsAway(pkg: String): Boolean {
        if (pkg == app.packageName) return false
        if (lock == null && (!blockOthers || pkg in allowedPackages)) return false
        val keyboard = runCatching { Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) }.getOrNull()?.substringBefore('/')
        if (pkg == keyboard) return false
        return appCache.getOrPut(pkg) { runCatching { app.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false) } ||
            pkg in HomeActivity.SETTINGS_APPS
    }
}
