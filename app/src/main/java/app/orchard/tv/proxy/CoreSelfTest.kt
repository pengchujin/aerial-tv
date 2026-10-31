package app.orchard.tv.proxy

import java.io.File
import java.io.IOException

/**
 * Runs `<core> -v` and checks that it names the pinned version.
 *
 * Why this exists: from Android 10, for apps that target API 29 or later (Aerial targets 34), the
 * platform's SELinux policy does not let an app execute a file it wrote itself (app_data_file),
 * which is what a downloaded core is. That is our reading of AOSP's policy and Google's guidance
 * ("W^X"), not something verified here: the only TV used for this work runs Android 9, where
 * executing from the app's own directory is allowed. If a TV refuses, the exec fails with
 * EACCES / "Permission denied" and the core is deleted with a fixed error, so the user is told
 * plainly instead of seeing a proxy that never starts. A core bundled in the APK
 * (nativeLibraryDir) is not affected by that rule.
 */
internal object CoreSelfTest {
    const val TIMEOUT_MS = 5_000L
    private const val MAX_OUTPUT = 4096

    /** Null when the core ran and said the pinned version, else a [CoreError] code. */
    fun run(core: File, version: String): String? {
        val process = try {
            ProcessBuilder(core.path, "-v").redirectErrorStream(true).start()
        } catch (e: IOException) {
            return classifyStartFailure(e.message)
        }
        val out = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    val buffer = CharArray(512)
                    while (out.length < MAX_OUTPUT) {
                        val n = r.read(buffer)
                        if (n < 0) break
                        synchronized(out) { out.append(buffer, 0, n) }
                    }
                }
            }
        }, "AerialCoreSelfTest").apply { isDaemon = true; start() }
        reader.join(TIMEOUT_MS)
        val finished = !reader.isAlive
        if (!finished) process.destroy()
        val text = synchronized(out) { out.toString() }
        return classifyOutput(text, finished, version)
    }

    /** The message of the IOException ProcessBuilder.start threw (Android: `error=13, Permission denied`). */
    fun classifyStartFailure(message: String?): String {
        val m = message?.lowercase().orEmpty()
        return if ("error=13" in m || "error=1," in m || "permission denied" in m || "eacces" in m || "eperm" in m) {
            CoreError.EXEC_NOT_PERMITTED
        } else CoreError.SELFTEST_FAILED
    }

    fun classifyOutput(output: String, finished: Boolean, version: String): String? =
        if (finished && version in output) null else CoreError.SELFTEST_FAILED

    /**
     * Only the 32-bit ARM asset is pinned, so the download is offered only where a 32-bit ARM
     * process can run: an ABI list naming armeabi-v7a (also true on arm64 TVs with 32-bit support).
     */
    fun abiSupported(abis: Array<String>?): Boolean = abis.orEmpty().any { it == "armeabi-v7a" }
}
