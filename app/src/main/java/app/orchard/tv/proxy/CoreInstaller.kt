package app.orchard.tv.proxy

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** What the downloaded file must be: pinned values, nothing is read from the network to decide. */
internal data class CoreSpec(
    val version: String,
    val assetSha256: String,
    val assetBytes: Long,
    val binarySha256: String,
    val binaryBytes: Long,
    /** Hard caps, independent of the pins: a stream or an expansion past these is cut off. */
    val maxAssetBytes: Long = 32L shl 20,
    val maxBinaryBytes: Long = 80L shl 20,
) {
    companion object {
        /** mihomo v1.19.33, official linux-armv7 asset (see tools/fetch-mihomo.sh and NOTICE.txt). */
        val PINNED = CoreSpec(
            version = "1.19.33",
            assetSha256 = "c8a8f9bcaab914269fbdca7569e4a9f024cc667ecf12eb43e8ccdc01b3731604",
            assetBytes = 21_190_617L,
            binarySha256 = "e9eaf8ad3467ca980af75abab56969ed7167b4d7a0dd63cd4a112004e4a39fcc",
            binaryBytes = 56_230_014L,
        )
    }
}

/** Fixed codes: the only text about a failed download that reaches a screen or a log. */
internal object CoreError {
    const val DOWNLOAD_FAILED = "core_download_failed"
    const val INTEGRITY = "core_integrity_failed"
    const val STORAGE = "core_storage_failed"
    const val EXEC_NOT_PERMITTED = "core_exec_not_permitted"
    const val SELFTEST_FAILED = "core_selftest_failed"
    const val UNSUPPORTED = "core_download_unsupported"
    val ALL = setOf(DOWNLOAD_FAILED, INTEGRITY, STORAGE, EXEC_NOT_PERMITTED, SELFTEST_FAILED, UNSUPPORTED)

    private fun rank(code: String) = when (code) { INTEGRITY -> 3; STORAGE -> 2; else -> 1 }

    /** The failure worth telling the user when several sources failed differently. */
    fun worse(a: String, b: String) = if (rank(b) > rank(a)) b else a

    /** A write that ran out of room or hit a read-only disk is a storage problem, not the network's. */
    fun isStorage(e: IOException): Boolean {
        val m = e.message?.lowercase().orEmpty()
        return "no space" in m || "enospc" in m || "read-only" in m || "erofs" in m || "disk quota" in m
    }
}

/** state: idle, downloading, verifying or failed. [error] is a [CoreError] code only while failed. */
internal data class CoreProgress(
    val state: String = "idle",
    val received: Long = 0,
    val total: Long = 0,
    /** Bytes per second over the last second or so. */
    val speed: Long = 0,
    val error: String? = null,
)

/**
 * Downloads the pinned proxy core into [dir] and installs it, only ever as a file whose compressed
 * and extracted SHA-256 both match [spec]. Pure JVM: the network ([fetch]), the executable bit
 * ([markExecutable]) and the exec self-test ([selfTest]) are injected, so the tests use fakes.
 *
 * Layout in [dir]: `mihomo` (the installed core), `mihomo.gz.part` (the compressed download in
 * progress, kept between attempts), `mihomo.gz.src` (the one URL those bytes came from) and
 * `mihomo.tmp` (the extraction in progress). Nothing is executable until `mihomo.tmp` has passed
 * both checks, and `mihomo` only ever appears by an atomic rename of that file.
 *
 * Resume: the part file continues only from the same URL that wrote it (the `.src` marker) with a
 * Range request the server answers 206 for at that exact offset. A server that answers 200 or a
 * different offset gets a fresh start; bytes of two sources are never joined.
 */
internal class CoreInstaller(
    private val dir: File,
    private val sources: List<String>,
    private val fetch: CoreFetch,
    private val spec: CoreSpec = CoreSpec.PINNED,
    private val selfTest: (File) -> String? = { null },
    private val markExecutable: (File) -> Boolean = { it.setExecutable(true, true) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    val file = File(dir, "mihomo")
    private val part = File(dir, "mihomo.gz.part")
    private val marker = File(dir, "mihomo.gz.src")
    private val temp = File(dir, "mihomo.tmp")

    @Volatile private var current = CoreProgress()
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    @Volatile private var active: CoreReply? = null
    private val executor by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "AerialCoreInstall").apply { isDaemon = true } }
    }

    /** Cheap on purpose (status is polled): the pinned size, not a re-hash of 56 MB. */
    fun installed(): Boolean = file.isFile && file.length() == spec.binaryBytes

    fun progress(): CoreProgress = current

    /** Starts a download on the installer's own thread; false if one is already running. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancelled.set(false)
        current = CoreProgress("downloading", total = spec.assetBytes)
        executor.execute { runBody() }
        return true
    }

    /** Blocking variant, for tests. */
    fun run(): CoreProgress {
        if (!running.compareAndSet(false, true)) return current
        cancelled.set(false)
        current = CoreProgress("downloading", total = spec.assetBytes)
        runBody()
        return current
    }

    /** Stops a running download; the part file stays, so starting again resumes it. */
    fun cancel() {
        cancelled.set(true)
        active?.close()
    }

    private fun runBody() {
        try {
            val error = try { doRun() } catch (_: Cancelled) { null } catch (e: Fail) { e.code }
                catch (e: IOException) { if (CoreError.isStorage(e)) CoreError.STORAGE else CoreError.DOWNLOAD_FAILED }
                catch (e: RuntimeException) { CoreError.DOWNLOAD_FAILED }
            current = when {
                error != null -> CoreProgress("failed", error = error).also { log("core download failed: $error") }
                cancelled.get() && !installed() -> CoreProgress()
                else -> CoreProgress()
            }
        } finally {
            active = null
            running.set(false)
        }
    }

    private class Fail(val code: String, val permanent: Boolean = false) : IOException(code)
    private class Cancelled : IOException("cancelled")

    private fun checkCancel() { if (cancelled.get()) throw Cancelled() }

    /** Returns null on success or when cancelled, else a [CoreError] code. */
    private fun doRun(): String? {
        if (installed()) return null
        if (!dir.isDirectory && !dir.mkdirs()) return CoreError.STORAGE
        temp.delete()
        // Room for the download and the extraction at once, with a margin; usableSpace is 0 on
        // some file systems that cannot say, which is not treated as "full".
        val need = spec.assetBytes + spec.binaryBytes + (8L shl 20)
        val free = dir.usableSpace
        if (free in 1 until need - (if (part.isFile) part.length() else 0L)) return CoreError.STORAGE

        var worst = CoreError.DOWNLOAD_FAILED
        val skip = HashSet<Int>()
        // Two rounds: a source that dropped mid-file is asked again after the others had their turn.
        for (round in 0 until 2) {
            for ((index, url) in sources.withIndex()) {
                if (index in skip) continue
                checkCancel()
                try {
                    if (download(url, index)) return finish()
                } catch (e: Fail) {
                    worst = CoreError.worse(worst, e.code)
                    if (e.permanent) skip += index
                } catch (e: Cancelled) {
                    throw e
                } catch (e: IOException) {
                    if (cancelled.get()) throw Cancelled()
                    worst = CoreError.worse(worst, if (CoreError.isStorage(e)) CoreError.STORAGE else CoreError.DOWNLOAD_FAILED)
                    log("core source ${index + 1} of ${sources.size}: ${e.javaClass.simpleName}")
                }
            }
            if (skip.size == sources.size) break
        }
        return worst
    }

    /**
     * One source: transfer (resuming if allowed), then verify. True when the compressed file is
     * complete and its SHA-256 matches; throws [Fail] for a source that must not be asked again.
     */
    private fun download(url: String, index: Int): Boolean {
        for (attempt in 0 until 2) {
            val resumed = transfer(url, allowResume = attempt == 0)
            current = current.copy(state = "verifying", speed = 0)
            checkCancel()
            if (sha256(part) == spec.assetSha256) return true
            // Wrong bytes: never kept. A resumed file may only have been spliced onto a changed
            // upstream file, so that gets one fresh try from byte 0 before the source is dropped.
            discardPart()
            if (!resumed) throw Fail(CoreError.INTEGRITY, permanent = true)
            current = CoreProgress("downloading", total = spec.assetBytes)
        }
        throw Fail(CoreError.INTEGRITY, permanent = true)
    }

    private fun discardPart() { part.delete(); marker.delete() }

    /** Where a transfer from [url] may continue: the part file's length if it came from [url], else 0. */
    internal fun resumeOffset(url: String): Long {
        if (!part.isFile) return 0
        val from = runCatching { marker.readText() }.getOrNull()
        val length = part.length()
        // A part that is already complete or longer is not a prefix of anything worth continuing.
        return if (from == url && length in 1 until spec.assetBytes) length else 0
    }

    /** Fills the part file from [url]; true if it continued an earlier partial file. */
    private fun transfer(url: String, allowResume: Boolean): Boolean {
        var from = if (allowResume) resumeOffset(url) else 0L
        var reply = openReply(url, from)
        if (reply.status == 416 || (from > 0 && (reply.status != 206 || reply.offset != from))) {
            // The server will not continue at that byte: start over rather than guess.
            reply.close(); active = null
            from = 0
            reply = openReply(url, 0)
        }
        reply.use {
            if (reply.status != 200 && reply.status != 206 || reply.offset != from) throw Fail(CoreError.DOWNLOAD_FAILED)
            // The pin is the size; a source that says otherwise is not serving the pinned file.
            if (reply.total >= 0 && reply.total != spec.assetBytes) throw Fail(CoreError.INTEGRITY, permanent = true)
            // Bytes of another source (or a refused resume) are replaced only now that this source
            // has answered; a source that fails to open leaves the part file for whoever wrote it.
            if (from == 0L) discardPart()
            marker.writeText(url)
            var received = from
            var windowAt = clock(); var windowFrom = received
            current = CoreProgress("downloading", received, spec.assetBytes)
            val limit = minOf(spec.assetBytes, spec.maxAssetBytes)
            val buffer = ByteArray(16 * 1024)
            var overflow = false
            FileOutputStream(part, from > 0).use { out ->
                while (true) {
                    checkCancel()
                    val n = try { reply.body.read(buffer) } catch (e: IOException) {
                        if (cancelled.get()) throw Cancelled()
                        throw e
                    }
                    if (n < 0) break
                    received += n
                    if (received > limit) { overflow = true; break }
                    try { out.write(buffer, 0, n) } catch (e: IOException) {
                        throw if (CoreError.isStorage(e)) Fail(CoreError.STORAGE) else e
                    }
                    val now = clock()
                    if (now - windowAt >= 1000) {
                        current = current.copy(received = received, speed = (received - windowFrom) * 1000 / (now - windowAt))
                        windowAt = now; windowFrom = received
                    } else current = current.copy(received = received)
                }
            }
            // Deleted only once the stream is closed (an open file cannot be deleted on every platform).
            if (overflow) { discardPart(); throw Fail(CoreError.INTEGRITY, permanent = true) }
            // Ended early: the connection closed cleanly before the pinned size. Keep the bytes
            // for a continued attempt.
            if (received < spec.assetBytes) throw EOFException("core_truncated")
        }
        return from > 0
    }

    private fun openReply(url: String, from: Long): CoreReply {
        checkCancel()
        val reply = try { fetch.open(url, from) } catch (e: CoreHttpException) {
            // 4xx other than a timeout / rate limit will not change by asking again.
            throw Fail(CoreError.DOWNLOAD_FAILED, permanent = e.status in 400..499 && e.status != 408 && e.status != 429)
        }
        active = reply
        if (cancelled.get()) { reply.close(); throw Cancelled() }
        return reply
    }

    private fun sha256(f: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancel()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The compressed file already matched its pin. Decompress to [temp] under the size cap, check
     * size and SHA-256 of the result, and only then make it executable and rename it into place.
     * Last, run the self-test; a core that cannot be executed is deleted, not left lying around.
     */
    private fun finish(): String? {
        current = current.copy(state = "verifying", speed = 0)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            GZIPInputStream(FileInputStream(part), 64 * 1024).use { input ->
                FileOutputStream(temp).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancel()
                        val n = try { input.read(buffer) } catch (e: ZipException) { throw Fail(CoreError.INTEGRITY, true) }
                            catch (e: EOFException) { throw Fail(CoreError.INTEGRITY, true) }
                        if (n < 0) break
                        size += n
                        // A compression bomb stops here, long before it fills the disk.
                        if (size > spec.binaryBytes || size > spec.maxBinaryBytes) throw Fail(CoreError.INTEGRITY, true)
                        digest.update(buffer, 0, n)
                        try { out.write(buffer, 0, n) } catch (e: IOException) {
                            throw if (CoreError.isStorage(e)) Fail(CoreError.STORAGE) else e
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (size != spec.binaryBytes || hash != spec.binarySha256) throw Fail(CoreError.INTEGRITY, true)
            // Both checks passed: only now does the file become executable.
            if (!markExecutable(temp)) throw Fail(CoreError.STORAGE)
            file.delete()
            if (!temp.renameTo(file)) throw Fail(CoreError.STORAGE)
        } catch (e: Throwable) {
            temp.delete()
            if (e is Fail && e.code == CoreError.INTEGRITY) discardPart()
            throw e
        }
        discardPart()
        val failure = selfTest(file)
        if (failure != null) {
            file.delete()
            return failure
        }
        return null
    }
}
