package app.orchard.tv.system

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.orchard.tv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A log of its own for firmwares whose logcat shows nothing from apps (Skyworth 7T631_T60,
 * Android 13: black Home, remote did nothing, no app line in logcat). Every line also goes to
 * logcat. The last lines are kept in memory and printed by
 *   adb shell dumpsys activity app.orchard.tv/.MainActivity
 * and, in a diagnostic build (`./gradlew -Pdiag ...`) or after
 *   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez diag true
 * also written to Android/data/app.orchard.tv/files/aerial-diag.log, which `adb pull` can fetch.
 */
object DiagLog {
    private const val TAG = "AerialDiag"
    private const val KEEP = 600
    private const val FILE_LIMIT = 1 shl 20

    private val lines = ArrayDeque<String>()
    // Made on the first line written to the file, so a release build without diag has no thread.
    private val writer by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "AerialDiag").apply { isDaemon = true } }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    /** Whether lines go to the file as well; set from ConfigReceiver, on in a diagnostic build. */
    fun enabled(context: Context): Boolean = BuildConfig.DIAG || prefs(context).getBoolean("diag", false)

    fun setEnabled(context: Context, on: Boolean) {
        setOption(context, "diag", on)
        if (on) init(context) else file = null
    }

    /**
     * Experiments for a black Home, set over ADB (ConfigReceiver) and read when Home starts:
     * mode_switch lets Home change the display's refresh rate (off in a diagnostic build, the only
     * Android 12+ display change Aerial makes); soft_layer draws Home in software, past the GPU.
     */
    fun modeSwitch(context: Context): Boolean = prefs(context).getBoolean("mode_switch", !BuildConfig.DIAG)
    fun softLayer(context: Context): Boolean = prefs(context).getBoolean("soft_layer", false)

    /**
     * Whether Home's large clock is drawn from a picture made on the CPU (issue #1658): by default
     * on a GPU with OpenGL ES 2 only, where the large digits came out jagged (ZTE W152D, Mali-450);
     * soft_clock over ADB says yes or no on any TV.
     */
    fun softClock(context: Context): Boolean = prefs(context).let { p ->
        if (p.contains("soft_clock")) p.getBoolean("soft_clock", false) else glesMajor(context) in 1..2
    }

    /** The OpenGL ES major version the device declares, 0 when unknown. */
    fun glesMajor(context: Context): Int =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.deviceConfigurationInfo?.reqGlEsVersion?.let { it shr 16 } ?: 0

    /**
     * An experiment for issue #654 (Redmi X50, MiTV-NSOP0, Novatek): the apps fading into the
     * screensaver each in a buffer of their own rather than with their alpha laid on every drawing.
     * Their frame and shadow are cut to the tile while they fade.
     */
    fun saverFadeOffscreen(context: Context): Boolean = prefs(context).getBoolean("saver_fade_offscreen", false)
    fun setOption(context: Context, key: String, on: Boolean) = prefs(context).edit().putBoolean(key, on).apply()
    fun clearOption(context: Context, key: String) = prefs(context).edit().remove(key).apply()
    private fun prefs(context: Context) = context.getSharedPreferences("system", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (file != null || !enabled(context)) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, "aerial-diag.log")
        // The lines from before the file: a boot's while the user was still locked, when the
        // preference saying the file is wanted cannot be read yet. XGIMI RS Pro 2 (Android 9) stayed
        // black at boot, logcat cannot be read there, and the file began at the rescue (issue #1391).
        val earlier = synchronized(lines) { lines.toList() }
        if (earlier.isNotEmpty()) {
            writer.execute {
                runCatching {
                    val fresh = !f.exists()
                    f.appendText(earlier.joinToString("\n", prefix = "-- earlier in this process:\n", postfix = "\n"))
                    if (fresh) f.setReadable(true, false)
                }
            }
        }
        file = f
        i("log file ${f.absolutePath}")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        // SimpleDateFormat is not thread-safe; lines come from the main thread and the player's.
        val line = synchronized(lines) {
            "${stamp.format(Date())} +${SystemClock.uptimeMillis()} $message".also {
                lines.addLast(it)
                while (lines.size > KEEP) lines.removeFirst()
            }
        }
        val f = file ?: return
        writer.execute {
            runCatching {
                if (f.length() > FILE_LIMIT) f.renameTo(File(f.parentFile, "aerial-diag.old.log"))
                val fresh = !f.exists()
                f.appendText(line + "\n")
                // Created 0600 on Xiaomi MiTV-MFTR0 (Android 14), which adb pull could not read.
                if (fresh) f.setReadable(true, false)
            }
        }
    }

    fun e(message: String, error: Throwable) = i("$message: ${Log.getStackTraceString(error)}")

    /** Logs whatever kills the process before it goes. */
    fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Crashes) return
        Thread.setDefaultUncaughtExceptionHandler(Crashes(previous))
    }

    private class Crashes(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            val f = file
            val line = "${synchronized(lines) { stamp.format(Date()) }} crash on ${t.name}: ${Log.getStackTraceString(e)}"
            Log.e(TAG, line)
            if (f != null) runCatching { f.appendText(line + "\n") }
            previous?.uncaughtException(t, e)
        }
    }

    fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE}, ${Build.BOARD}), " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.FINGERPRINT}, " +
            "Aerial ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** The last [n] lines, oldest first. */
    fun recent(n: Int): List<String> = synchronized(lines) { lines.toList().takeLast(n) }

    fun dump(writer: PrintWriter) {
        writer.println("Aerial log (${device()}):")
        synchronized(lines) { lines.forEach { writer.println("  $it") } }
    }
}
