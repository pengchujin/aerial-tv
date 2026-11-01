package app.orchard.tv.update

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.orchard.tv.BuildConfig
import app.orchard.tv.system.DiagLog
import java.io.File

/**
 * Has the system compile an Aerial that runs uncompiled, by installing the running APK over itself
 * together with its own dex metadata (assets/aerial-dm), the way an update from Control Center does
 * (Updates.installBySession). The same version again is an ordinary replace: settings, wallpaper,
 * photos and the permissions ADB granted stay.
 *
 * Installed from a USB stick, an app store, TCL 多屏互动 or a phone's ADB tool, and on the update
 * from 1.71 to 1.72, Aerial runs uncompiled (odex about 48 KB, against about 8 MB compiled on
 * Xiaomi MiTV-MFTR0 and TCL MT5879), its main thread about twice as slow, until the next update from
 * Control Center (docs/PERF-2026-10-30.md).
 *
 * Android 12 and later: by itself, once per version, a while after another app has come in front,
 * so that Aerial's restart is not seen; no confirmation is asked there. Android 9 to 11 ask for a
 * confirmation, so there it is a row in Control Center ([offered]). Android 7 and 8 take no dex
 * metadata at install: only ADB's `cmd package compile` helps there.
 */
object SelfCompile {
    private const val TAG = "AerialUpdates"
    private const val PREFS = "self_compile"
    private const val KEY_TRIED = "tried_version"

    /** Below this the oat file holds no compiled code (only verified: tens of KB). */
    private const val COMPILED_BYTES = 1L shl 20

    /** How long another app stays in front before Aerial reinstalls itself behind it. */
    private const val AUTO_DELAY_MS = 20_000L

    /** Android 9 to 11: Control Center offers the reinstall (it needs the owner's confirmation). */
    var offered by mutableStateOf(false); private set

    private val main = Handler(Looper.getMainLooper())
    private var inFront = false
    private var checkedThisRun = false
    private var app: Context? = null

    /** The compiled code beside the APK, in bytes, or null where none is found. Only opened by name: the folder cannot be listed. */
    fun odexBytes(context: Context): Long? = runCatching {
        val dir = File(File(context.applicationInfo.sourceDir).parentFile, "oat")
        listOf("arm", "arm64", "x86", "x86_64").map { File(dir, "$it/base.odex") }
            .firstOrNull { it.exists() }?.length()
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun triedThisVersion(context: Context) = prefs(context).getLong(KEY_TRIED, 0) == BuildConfig.VERSION_CODE.toLong()

    /** Whether reinstalling would help: Android 9+, the official package, uncompiled, metadata in the APK, not tried for this version. */
    private fun worthIt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !Updates.canInstall(context) || triedThisVersion(context)) return false
        val odex = odexBytes(context) ?: return false
        return odex < COMPILED_BYTES && Updates.ownDexMetadata(context) != null
    }

    /** Once per process, off the main thread (it reads the APK): is Aerial uncompiled? */
    fun init(context: Context) {
        if (checkedThisRun) return
        checkedThisRun = true
        app = context.applicationContext
        val worth = worthIt(context)
        if (worth) DiagLog.i("self compile: uncompiled (odex ${odexBytes(context)?.div(1024)} KB)")
        offered = worth && Build.VERSION.SDK_INT < 31
    }

    /**
     * Compiled since [init] looked, by ADB's `cmd package compile` or the system's dexopt while
     * the TV idles: the row goes. The process stays, so it was still offered on TCL MT5879
     * (Android 11) after a 多屏互动 install compiled with ADB just after Aerial started.
     * Only the file's length: cheap enough for the main thread.
     */
    fun recheck(context: Context) {
        if (!offered) return
        val odex = odexBytes(context) ?: return
        if (odex < COMPILED_BYTES) return
        offered = false
        DiagLog.i("self compile: compiled since (odex ${odex / 1024} KB); no longer offered")
    }

    fun diagnostics(context: Context): String =
        "tried for this version=${triedThisVersion(context)}, offered=$offered"

    fun onHomeStarted(context: Context) {
        recheck(context)
        inFront = true
        main.removeCallbacks(auto)
    }

    /** Another app, standby or the system's screensaver in front: reinstall behind it, if it stays. */
    fun onHomeStopped() {
        inFront = false
        if (Build.VERSION.SDK_INT < 31) return
        main.removeCallbacks(auto)
        main.postDelayed(auto, AUTO_DELAY_MS)
    }

    private val auto = Runnable {
        val context = app ?: return@Runnable
        if (inFront || !worthIt(context)) return@Runnable
        run(context, auto = true)
    }

    /** From Control Center's row (Android 9 to 11), or by itself on 12+ ([auto]). */
    fun run(context: Context, auto: Boolean) {
        val app = context.applicationContext
        prefs(app).edit { putLong(KEY_TRIED, BuildConfig.VERSION_CODE.toLong()) }
        offered = false
        Log.i(TAG, "self compile: reinstalling (${if (auto) "auto" else "asked"}), odex ${odexBytes(app)} bytes")
        DiagLog.i("self compile: reinstalling (${if (auto) "auto" else "asked"})")
        if (Build.VERSION.SDK_INT >= 28) Updates.reinstallSelf(app, auto)
    }

    /** ConfigReceiver's self_compile: now, whether tried or not (for testing on a TV). */
    fun runNow(context: Context) {
        prefs(context).edit { remove(KEY_TRIED) }
        run(context, auto = Build.VERSION.SDK_INT >= 31)
    }
}
