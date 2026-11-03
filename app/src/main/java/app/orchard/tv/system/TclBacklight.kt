package app.orchard.tv.system

import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** ak30a5 / Android 9: the firmware's local command service adjusts backlight without Settings UI. */
internal object TclBacklight {
    private const val CALL_TIMEOUT_MS = 1500L

    // This private command ABI has no capability/version query. Do not probe other models with it.
    private val nativeReady by lazy {
        Build.MODEL.equals("ak30a5", ignoreCase = true) && Build.VERSION.SDK_INT == 28 &&
            Build.HARDWARE.equals("amlogic", ignoreCase = true) &&
            runCatching { System.loadLibrary("aerial_tcl_backlight") }.isSuccess
    }

    private val calls by lazy {
        Executors.newSingleThreadExecutor { action ->
            Thread(action, "aerial-tcl-backlight").apply { isDaemon = true }
        }
    }

    @Volatile private var lastLevel = -1
    @Volatile private var failed = false

    val available: Boolean get() = !failed && lastLevel in 0..100

    /** Only a parsed middleware reading enables the slider; never the AOSP brightness setting. */
    fun level(): Int? = request(-1)

    /** The native setter saves, executes, then verifies the exact requested level by reading back. */
    fun setLevel(percent: Int): Int? = request(percent.coerceIn(1, 100))

    @Synchronized
    private fun request(percent: Int): Int? {
        if (failed || !nativeReady) return null
        val task = calls.submit<Int> { nativeBacklight(percent) }
        val actual = try {
            task.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Cancelling a queued call prevents a delayed old slider target from being applied.
            // A blocked native call can keep at most this one daemon thread, never the UI thread.
            task.cancel(true)
            -1
        }
        if (actual !in 0..100) {
            failed = true
            return null
        }
        lastLevel = actual
        return actual
    }

    fun diagnostics(): String = "TCL silent backlight: available=$available lastRead=$lastLevel failed=$failed"

    /** -1 reads; 1..100 saves and executes, then checks the real reading. */
    private external fun nativeBacklight(percent: Int): Int
}
