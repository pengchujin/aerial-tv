package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.orchard.tv.dream.AerialDream

/**
 * The TV's own screensaver: Android's dream where the firmware uses it (TCL, most Android TVs), or
 * the maker's own app where it runs outside it (Xiaomi HyperOS).
 */
object SystemScreensaver {
    private const val TAG = "AerialScreensaver"
    private const val COMPONENTS = "screensaver_components"
    private const val DEFAULT_COMPONENT = "screensaver_default_component"

    /** Xiaomi's screensaver, which HyperOS starts by its own timer (screen_saver_timeout), not as a dream. */
    const val XIAOMI = "com.xiaomi.mitv.hyper.screensaver"

    /** Android's dream that starts on demand (what "Start now" in Android's settings opens). */
    private val SOMNAMBULATOR = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")

    private fun dreamsOn(context: Context) =
        runCatching { Settings.Secure.getInt(context.contentResolver, "screensaver_enabled", 0) == 1 }.getOrDefault(false)

    private fun component(context: Context): String =
        runCatching { Settings.Secure.getString(context.contentResolver, COMPONENTS) }.getOrNull().orEmpty()

    /** Aerial's own dream is the TV's screensaver (tools/install.sh sets it where dreams are on). */
    fun isAerials(context: Context): Boolean =
        dreamsOn(context) && component(context).split(',').firstOrNull() == ComponentName(context, AerialDream::class.java).flattenToString()

    /** The dream the firmware came with, or null where it has none on record. */
    fun firmwareDream(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, DEFAULT_COMPONENT) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith(context.packageName + "/") }

    /**
     * After how long the firmware starts its own screensaver, for "System" chosen: Xiaomi's own
     * timer (system/screen_saver_timeout, 60000 on MiTV-MFTR0, -1 when off), else Android's dream
     * after screen_off_timeout (TCL MT5879: 120000). 0 when it is off; null where it is not known.
     */
    fun firmwareDelayMs(context: Context): Long? {
        val resolver = context.contentResolver
        val xiaomi = runCatching { context.packageManager.getPackageInfo(XIAOMI, 0) }.isSuccess
        if (xiaomi) {
            val ms = runCatching { Settings.System.getLong(resolver, "screen_saver_timeout") }.getOrNull() ?: return null
            return if (ms > 0) ms else 0
        }
        if (!dreamsOn(context)) return 0
        return runCatching { Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
            ?.takeIf { it in 1 until 24 * 60 * 60 * 1000L }
    }

    /**
     * When Home last asked for Aerial's dream, and when Home's window went away after that (both
     * elapsed time, -1 for none): how long the screen had nothing to show is measured from there to
     * the dream's first frame (dreamFirstFrame). Coocaa C10 (Android 9, issue #915) showed the dream
     * every time, but only after cold-starting SystemUI, with 1.2 to 2.7 s of black after Home went.
     */
    @Volatile private var dreamAskedAt = -1L
    @Volatile private var homeHiddenAt = -1L

    fun noteDreamAsked() {
        dreamAskedAt = SystemClock.elapsedRealtime()
        homeHiddenAt = -1L
    }

    fun forgetDreamAsked() { dreamAskedAt = -1L }

    fun noteHomeHidden() {
        val asked = dreamAskedAt
        if (asked >= 0 && homeHiddenAt < 0 && SystemClock.elapsedRealtime() - asked < DREAM_ANSWER_MS) homeHiddenAt = SystemClock.elapsedRealtime()
    }

    /**
     * For the screensaver's first frame: how long the screen was black before it, when Home had
     * asked for the dream a moment ago; null for any other first frame (Home's own screensaver,
     * the dream the TV started by its own timer).
     */
    fun dreamFirstFrame(): Long? {
        val asked = dreamAskedAt
        val now = SystemClock.elapsedRealtime()
        if (asked < 0 || now - asked > DREAM_FRAME_MS) return null
        dreamAskedAt = -1L
        return now - (homeHiddenAt.takeIf { it >= 0 } ?: asked)
    }

    private const val DREAM_ANSWER_MS = 5_000L
    private const val DREAM_FRAME_MS = 15_000L

    /** Starts the TV's dream now, whichever it is; false where that is not possible. */
    fun startDream(context: Context): Boolean {
        if (!dreamsOn(context)) return false
        val intent = Intent(Intent.ACTION_MAIN).setComponent(SOMNAMBULATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull() == null) return false
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not start the dream", it) }.isSuccess
    }

    /**
     * Starts the firmware's own screensaver now, for Back with "System" chosen: Xiaomi's app where it
     * is installed, else the dream. False where neither can be started.
     */
    fun startFirmware(context: Context): Boolean {
        val pm = context.packageManager
        val xiaomi = runCatching { pm.getPackageInfo(XIAOMI, 0) }.isSuccess
        if (xiaomi) {
            // Its MainActivity is the screensaver. HyperOS starts it from a protected broadcast with
            // these extras; without them it opens with its style picker over it (and an ad).
            // MiTV-MFTR0, screensaver 3.04.115.
            val saver = Intent(Intent.ACTION_MAIN).setClassName(XIAOMI, "$XIAOMI.MainActivity")
                .putExtra("show_saver_list", false)
                .putExtra("show_widget_view", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(saver) }.isSuccess) return true
            Log.w(TAG, "could not open Xiaomi's screensaver")
        }
        return startDream(context)
    }
}
