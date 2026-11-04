package app.orchard.tv.watchnext

import android.os.CancellationSignal
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Encoded images only; independent of preview geometry and bounded separately from bitmaps. */
internal class WatchNextImageFiles(
    private val directory: File,
    private val maxBytes: Long = WatchNextDefaults.DiskCacheBytes,
    private val lifetimeMs: Long = WatchNextDefaults.DiskCacheLifetimeMs,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: (String, CancellationSignal) -> ByteArray = ::remoteArtwork,
) {
    private val diskLock = Any()
    private val requests = Array(32) { ReentrantLock() }
    private val imageName = Regex("[0-9a-f]{64}\\.img")

    fun get(address: String, signal: CancellationSignal, cachedBefore: Long? = null): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(address.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it.toInt() and 255) } + ".img"
        val file = File(directory, name)
        cached(file, signal, cachedBefore)?.let { return it }
        check(cachedBefore == null) { "Artwork was not cached on entry" }
        val lock = requests[(digest[0].toInt() and 255) % requests.size]
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) signal.throwIfCanceled()
        try {
            signal.throwIfCanceled()
            // The request ahead of us may have populated the cache while we waited.
            cached(file, signal, null)?.let { return it }
            val bytes = fetch(address, signal)
            signal.throwIfCanceled()
            require(bytes.isNotEmpty() && bytes.size <= WatchNextDefaults.RemoteMaxBytes)
            // Storage being full or unavailable must not prevent displaying a downloaded image.
            synchronized(diskLock) {
                if (bytes.size + 8L <= maxBytes) runCatching {
                    check(directory.isDirectory || directory.mkdirs())
                    val pending = File(directory, UUID.randomUUID().toString() + ".tmp")
                    try {
                        DataOutputStream(pending.outputStream().buffered()).use {
                            it.writeLong(now()); it.write(bytes)
                        }
                        signal.throwIfCanceled()
                        check(pending.renameTo(file))
                        file.setLastModified(now())
                        trim()
                    } finally { pending.delete() }
                }
            }
            signal.throwIfCanceled()
            return bytes
        } finally { lock.unlock() }
    }

    private fun cached(file: File, signal: CancellationSignal, cachedBefore: Long?): ByteArray? = synchronized(diskLock) {
        signal.throwIfCanceled()
        if (!file.isFile) return@synchronized null
        val bytes = runCatching {
            check(file.length() in 9..(WatchNextDefaults.RemoteMaxBytes + 8L))
            DataInputStream(file.inputStream().buffered()).use { input ->
                val downloadedAt = input.readLong()
                if (cachedBefore != null && downloadedAt > cachedBefore) return@use null
                check(now() - downloadedAt in 0 until lifetimeMs)
                input.readBytes()
            }
        }.getOrNull()
        if (bytes != null) {
            signal.throwIfCanceled()
            file.setLastModified(now())
        } else if (cachedBefore == null) file.delete()
        bytes
    }

    private fun trim() {
        val files = directory.listFiles()?.filter { it.isFile && imageName.matches(it.name) }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
