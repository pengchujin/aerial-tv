package app.orchard.tv.system

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.content.getSystemService
import kotlin.math.abs

/**
 * Whether the input or app the TV starts on (Personalize) is due: once per power-on and once per
 * wake, never again while the TV stays on. Coocaa stops Aerial in the background; coming back to
 * it from the app it had opened started Aerial cold, which took that for a boot and opened the
 * app again, round and round (issue #766). Kept on disk, since that is a new process each time.
 */
object BootOpen {
    private const val PREFS = "system"
    private const val OPENED_ELAPSED = "boot_open_elapsed"
    private const val OPENED_BOOT = "boot_open_boot"
    private const val OPENED_BOOT_ID = "boot_open_boot_id"
    private const val SCREEN_OFF_ELAPSED = "screen_off_elapsed"
    private const val SCREEN_OFF_BOOT = "screen_off_boot"
    private const val SCREEN_OFF_BOOT_ID = "screen_off_boot_id"
    private const val OPENED_ASLEEP = "boot_open_asleep"
    private const val HOME_ELAPSED = "home_key_elapsed"
    private const val HOME_BOOT = "home_key_boot"
    private const val HOME_BOOT_ID = "home_key_boot_id"

    /**
     * The kernel's name for this boot. TVs without a clock battery start at 1970 and set the wall
     * clock from the network a while in, so a time saved before that names that boot by 0 — the
     * same 0 as the boot after it, which read as this boot (JMGO S60, Android 5.1: the boot source
     * was skipped as "already opened"). A process cannot outlive its boot, so this is read once.
     */
    private val BOOT_ID: String? by lazy {
        runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // Written at once (commit), not later: Xiaomi MiTV-MFTR0 (Android 14) force-stops Aerial 1.3 s
    // after the screen goes off, before apply() had saved the screen-off, so the wake after it took
    // what the TV starts on for opened already and left it shut (2026-11-08, on the owner's TV).

    /** When this boot began by the wall clock, to tell a stored time of this boot from one before. */
    private fun bootStamp(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Whether a time saved as [elapsed] with [stamp] and [bootId] (null in an older version) was in
     * this boot. The boot id decides; without one the clock does, and a 0 stamp (a clock never set
     * yet) says nothing about which boot it was.
     */
    private fun thisBoot(elapsed: Long, stamp: Long, bootId: String?): Boolean {
        val now = BOOT_ID
        if (bootId != null && now != null) return bootId == now
        return stamp > 0 && elapsed in 0..SystemClock.elapsedRealtime() &&
            abs(stamp - bootStamp()) < SAME_BOOT_SLACK_MS
    }

    /** The screen went off: the next time it lights is a wake, and the target is due again. */
    fun noteScreenOff(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(SCREEN_OFF_ELAPSED, SystemClock.elapsedRealtime()).putLong(SCREEN_OFF_BOOT, bootStamp())
                .putString(SCREEN_OFF_BOOT_ID, BOOT_ID)
        }
    }

    /**
     * The viewer pressed Home. Kept on disk as well: pressing it where the stock launcher raises no
     * window change makes the guard switch accessibility off and on, which starts it in a new
     * process a moment later (MiTV4-ANSM0, Android 6.0.1, issue #651), and that one must know the
     * Home press was the viewer's rather than what the TV starts on being covered.
     */
    fun noteHomeKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(HOME_ELAPSED, SystemClock.elapsedRealtime()).putLong(HOME_BOOT, bootStamp())
                .putString(HOME_BOOT_ID, BOOT_ID)
        }
    }

    /** Whether the viewer pressed Home in this boot within the last [ms]; see noteHomeKey. */
    fun homeKeyWithin(context: Context, ms: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(HOME_ELAPSED, -1L)
        return at >= 0 && thisBoot(at, prefs.getLong(HOME_BOOT, 0L), prefs.getString(HOME_BOOT_ID, null)) &&
            SystemClock.elapsedRealtime() - at < ms
    }

    fun noteOpened(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(OPENED_ELAPSED, SystemClock.elapsedRealtime()).putLong(OPENED_BOOT, bootStamp())
                .putLong(OPENED_ASLEEP, asleep()).putString(OPENED_BOOT_ID, BOOT_ID)
        }
    }

    /**
     * Whether the TV has been to standby since the target was last opened, for a process that
     * started just now. Xiaomi stops Aerial during standby and starts it cold on the wake, so the
     * screen-on broadcast and Home Guard's EXTRA_WOKE were both missed and the target stayed shut
     * (MiTV-MFTR0, Android 14, PR #927 by xutaocn; MiTV-ASTP0, Android 9, issue #964). Only the
     * process's first time in front: Back from an app opened after the wake does not open it again.
     */
    fun dueAfterColdWake(context: Context, processAgeMs: Long): Boolean {
        if (processAgeMs !in 0..COLD_WAKE_MS) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sleptSince(prefs.getLong(OPENED_ASLEEP, -1L), asleep()) || offSinceOpened(prefs)
    }

    /**
     * Aerial's process started while the TV was in standby: count that as the screen going off.
     * Xiaomi MiTV-ASTP0 (Android 9) stops Aerial on the way into standby before the screen-off
     * broadcast reaches it, and a short standby does not suspend the kernel long enough for
     * [sleptSince]; the guard revived in standby is the only one to know (issue #964, 6 of 6 wakes
     * from Home stayed on Aerial's Home). getSystemService(Class) is Android 6+: the guard crashed on
     * every bind on Android 5.1 in 1.74 (issues #1010 #1012 #1017 #1018), so this goes through core-ktx.
     */
    fun noteStartedInStandby(context: Context) {
        if (context.getSystemService<PowerManager>()?.isInteractive == false) noteScreenOff(context)
    }

    /** Whether the screen went off in this boot after the target was last opened. */
    private fun offSinceOpened(prefs: SharedPreferences): Boolean {
        val opened = prefs.getLong(OPENED_ELAPSED, -1L)
        if (opened < 0 || !thisBoot(opened, prefs.getLong(OPENED_BOOT, 0L), prefs.getString(OPENED_BOOT_ID, null)))
            return false
        val off = prefs.getLong(SCREEN_OFF_ELAPSED, -1L)
        return off >= 0 && thisBoot(off, prefs.getLong(SCREEN_OFF_BOOT, 0L), prefs.getString(SCREEN_OFF_BOOT_ID, null)) &&
            off > opened
    }

    /** Whether the target was opened in this boot within the last [ms]. */
    fun openedWithin(context: Context, ms: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val opened = prefs.getLong(OPENED_ELAPSED, -1L)
        return opened >= 0 && thisBoot(opened, prefs.getLong(OPENED_BOOT, 0L), prefs.getString(OPENED_BOOT_ID, null)) &&
            SystemClock.elapsedRealtime() - opened < ms
    }

    /** How long this boot has spent suspended: uptime stops in deep sleep, elapsed time does not. */
    private fun asleep(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    /**
     * Whether the TV was suspended since [openedAsleep] was read (a standby, then a wake). Xiaomi
     * MiTV-MFTR0 (Android 14) stops Aerial during standby, so the screen-off broadcast that marks a
     * wake never reached it, and the app to start on opened at power-on only, never after standby
     * (PR #881, by xutaocn, found on that TV). The time asleep is counted by the kernel, whoever
     * was running. A few seconds of suspend with the screen on are not a standby.
     */
    internal fun sleptSince(openedAsleep: Long, asleepNow: Long): Boolean =
        openedAsleep >= 0 && asleepNow - openedAsleep > SLEPT_MS

    /** False when the target was opened in this boot and the TV has not gone to standby since. */
    fun due(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val opened = prefs.getLong(OPENED_ELAPSED, -1L)
        if (opened < 0 || !thisBoot(opened, prefs.getLong(OPENED_BOOT, 0L), prefs.getString(OPENED_BOOT_ID, null)))
            return true
        return sleptSince(prefs.getLong(OPENED_ASLEEP, -1L), asleep()) || offSinceOpened(prefs)
    }

    /**
     * Whether the screen went off in this boot after the target was last opened, for a guard bound
     * with the screen already lit: MiTV-MFTR0 (Android 14) keeps Aerial's process through standby,
     * so the guard is created after the screen lights and its SCREEN_ON is already gone, while the
     * process age rules out every other wake test there (issue #1229). Cleared by the target being
     * opened again, which [noteOpened] does, so it cannot open it twice in one wake.
     */
    fun dueAfterMissedWake(context: Context): Boolean =
        offSinceOpened(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private const val SAME_BOOT_SLACK_MS = 2 * 60 * 1000L
    private const val SLEPT_MS = 30_000L

    /** A wake on MiTV-MFTR0 took up to 2 minutes to bring Aerial's process up (PR #927). */
    private const val COLD_WAKE_MS = 3 * 60 * 1000L
}
