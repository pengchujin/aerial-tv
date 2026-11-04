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
 * so that Aerial's restart is not seen; no confirmation is asked there. Android 7 to 11: first,
 * at the same moment, the package manager is asked to compile Aerial where it is (PackageCompile):
 * no confirmation, no reinstall, the compiled code from the next start. Only where that fails do
 * Android 9 to 11 offer the reinstall as a row in Control Center ([offered]), which asks for a
 * confirmation; Android 7 and 8 take no dex metadata at install, so there nothing else helps.
 */
object SelfCompile {
    private const val TAG = "AerialUpdates"
    private const val PREFS = "self_compile"
    private const val KEY_TRIED = "tried_version"
    private const val KEY_ASKED_SYSTEM = "system_install"
    private const val KEY_SYSTEM_RESULT = "system_result"

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

    /**
     * This install's time: the package manager is asked once per install, not per version, as an
     * install of the same version again (TCL 多屏互动 takes one) is uncompiled again.
     */
    private fun installedAt(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    private fun askedSystemThisInstall(context: Context) = prefs(context).getLong(KEY_ASKED_SYSTEM, -1) == installedAt(context)

    private fun uncompiled(context: Context) = odexBytes(context)?.let { it < COMPILED_BYTES } == true

    /** Whether to ask the package manager (PackageCompile): Android 7 to 11, uncompiled, not asked for this install. */
    private fun systemWorthIt(context: Context) =
        Build.VERSION.SDK_INT in 24..30 && !askedSystemThisInstall(context) && uncompiled(context)

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
        val system = systemWorthIt(context)
        if (worth || system) DiagLog.i("self compile: uncompiled (odex ${odexBytes(context)?.div(1024)} KB)")
        // Not while the package manager is still to be asked: it needs nobody.
        offered = worth && Build.VERSION.SDK_INT < 31 && !system
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
        "tried for this version=${triedThisVersion(context)}, offered=$offered" +
            (prefs(context).getString(KEY_SYSTEM_RESULT, null)?.let { ", package manager: $it" } ?: "")

    fun onHomeStarted(context: Context) {
        recheck(context)
        inFront = true
        main.removeCallbacks(auto)
    }

    /** Another app, standby or the system's screensaver in front: compile or reinstall behind it, if it stays. */
    fun onHomeStopped() {
        inFront = false
        main.removeCallbacks(auto)
        if (Build.VERSION.SDK_INT >= 24) main.postDelayed(auto, AUTO_DELAY_MS)
    }

    private val auto = Runnable {
        val context = app ?: return@Runnable
        if (inFront) return@Runnable
        if (Build.VERSION.SDK_INT < 31) {
            if (systemWorthIt(context)) Thread({ askSystem(context) }, "self-compile").start()
            return@Runnable
        }
        if (worthIt(context)) run(context, auto = true)
    }

    /**
     * Android 7 to 11: the package manager compiles Aerial where it is (PackageCompile), with the
     * Baseline Profile that ProfileInstaller wrote at the first start; with no profile there,
     * everything ("speed"). Once per install; if Aerial is still uncompiled after it, Android 9 to
     * 11 offer the reinstall in Control Center. Off the main thread: it takes tens of seconds.
     */
    private fun askSystem(context: Context) {
        val app = context.applicationContext
        prefs(app).edit { putLong(KEY_ASKED_SYSTEM, installedAt(app)) }
        val before = odexBytes(app)
        val started = android.os.SystemClock.elapsedRealtime()
        var said = runCatching { PackageCompile.compile(app, "speed-profile") }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        if (said.contains("Success") && uncompiled(app)) {
            said = runCatching { PackageCompile.compile(app, "speed") }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        }
        val after = odexBytes(app)
        val done = after != null && after >= COMPILED_BYTES
        val took = android.os.SystemClock.elapsedRealtime() - started
        val result = if (done) "compiled, odex ${after!! / 1024} KB in ${took / 1000} s" else "not compiled (${said.take(120)}), odex ${after?.div(1024)} KB"
        prefs(app).edit { putString(KEY_SYSTEM_RESULT, result) }
        Log.i(TAG, "self compile: package manager asked, odex $before -> $after bytes in $took ms: $said")
        DiagLog.i("self compile: package manager: $result")
        if (!done) main.post { offered = worthIt(app) }
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

    /** ConfigReceiver's system_compile: ask the package manager now, whether asked or not (for testing on a TV). */
    fun askSystemNow(context: Context) {
        prefs(context).edit { remove(KEY_ASKED_SYSTEM) }
        Thread({ askSystem(context) }, "self-compile").start()
    }

    /** ConfigReceiver's self_compile: now, whether tried or not (for testing on a TV). */
    fun runNow(context: Context) {
        prefs(context).edit { remove(KEY_TRIED) }
        run(context, auto = Build.VERSION.SDK_INT >= 31)
    }
}
