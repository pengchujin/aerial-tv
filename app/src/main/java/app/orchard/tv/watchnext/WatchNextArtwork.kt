package app.orchard.tv.watchnext

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.LruCache
import app.orchard.tv.system.DiagLog
import app.orchard.tv.wallpaper.Images
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.io.File
import kotlin.coroutines.resume

/** Local or bounded remote artwork. Only fully prepared images are published to the preview. */
class WatchNextArtwork(private val context: Context) : ComponentCallbacks2, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefetchExecutor = Executors.newSingleThreadExecutor()
    private val files = WatchNextImageFiles(File(context.cacheDir, "watch_next_artwork")) { address, signal ->
        remoteArtwork(address, signal).also { bytes ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            check(bounds.outWidth in 1..32768 && bounds.outHeight in 1..32768) { "Invalid artwork" }
        }
    }
    private var prefetchAddresses = emptyList<String>()
    private var prefetchSignal: CancellationSignal? = null
    private var prefetchTask: Future<*>? = null
    private val cache = object : LruCache<String, Bitmap>(WatchNextDefaults.CacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var generation = 0L
    @Volatile private var backgroundCache: WatchNextBackground? = null
    init { context.registerComponentCallbacks(this) }

    /** One background queue for all eligible records; foreground decoding has its own worker. */
    @Synchronized
    fun prefetch(programs: List<WatchNextProgram>) {
        val addresses = programs.flatMap { listOfNotNull(it.poster, it.thumbnail) }.distinct()
            .filter { Uri.parse(it).scheme in listOf("http", "https") }
        if (addresses == prefetchAddresses) return
        prefetchSignal?.cancel(); prefetchTask?.cancel(true)
        prefetchAddresses = addresses
        if (addresses.isEmpty()) return
        val signal = CancellationSignal()
        prefetchSignal = signal
        prefetchTask = prefetchExecutor.submit {
            var ready = 0
            for (address in addresses) {
                if (signal.isCanceled || Thread.currentThread().isInterrupted) break
                if (runCatching { files.get(address, signal) }.isSuccess) ready++
            }
            if (!signal.isCanceled) DiagLog.i("watch next artwork prefetch: $ready/${addresses.size} ready")
        }
    }

    suspend fun load(program: WatchNextProgram, width: Int, height: Int, density: Float): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val token = generation
            val signal = CancellationSignal()
            val future = executor.submit {
                val image = runCatching { prepare(program, width, height, density, token, signal) }.getOrNull()
                continuation.resume(image.takeIf { token == generation })
            }
            continuation.invokeOnCancellation { signal.cancel(); future.cancel(true) }
        }

    suspend fun background(program: WatchNextProgram, width: Int, height: Int): WatchNextBackground? =
        suspendCancellableCoroutine { continuation ->
            // Freeze remote eligibility for this visit; downloads may only help a later visit.
            val enteredAt = System.currentTimeMillis()
            val token = generation
            val signal = CancellationSignal()
            val future = executor.submit {
                val image = runCatching {
                    for (value in listOfNotNull(program.poster, program.thumbnail).distinct()) {
                        signal.throwIfCanceled()
                        val uri = Uri.parse(value)
                        if (uri.scheme !in listOf("content", "android.resource", "http", "https")) continue
                        val key = "${program.key}:${program.engagementMs}:$value:${width}x$height"
                        backgroundCache?.takeIf { it.sourceKey == key }?.let { return@runCatching it }
                        val ready = runCatching candidate@{
                            val started = SystemClock.elapsedRealtime()
                            val bytes = if (uri.scheme in listOf("http", "https")) files.get(value, signal, cachedBefore = enteredAt) else null
                            fun read(options: BitmapFactory.Options): Bitmap? = if (bytes != null)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) else decode(uri, options, signal)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            read(bounds)
                            val size = backgroundSize(bounds.outWidth, bounds.outHeight, width, height) ?: return@candidate null
                            var sample = 1
                            while (bounds.outWidth / (sample * 2) >= size.width && bounds.outHeight / (sample * 2) >= size.height) sample *= 2
                            val decoded = read(BitmapFactory.Options().apply {
                                inSampleSize = sample; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
                            }) ?: return@candidate null
                            val cropped = try { Images.cover(decoded, size.width, size.height) }
                                catch (error: Exception) { decoded.recycle(); throw error }
                            if (cropped !== decoded) decoded.recycle()
                            try {
                                signal.throwIfCanceled()
                                Canvas(cropped).drawColor(android.graphics.Color.BLACK, android.graphics.PorterDuff.Mode.DST_OVER)
                                val frost = Images.frost(cropped)
                                signal.throwIfCanceled()
                                val home = Images.shadeForHome(cropped)
                                if (home !== cropped) cropped.recycle()
                                DiagLog.i("watch next background: ${size.width}x${size.height}, prepare ${SystemClock.elapsedRealtime() - started} ms")
                                WatchNextBackground(key, home.asImageBitmap(), frost.asImageBitmap())
                            } catch (error: Exception) { cropped.recycle(); throw error }
                        }.getOrNull()
                        signal.throwIfCanceled()
                        if (ready != null) return@runCatching ready
                    }
                    null
                }.getOrNull()
                if (token == generation && !signal.isCanceled) {
                    if (image != null) backgroundCache = image
                    continuation.resume(image)
                } else continuation.resume(null)
            }
            continuation.invokeOnCancellation { signal.cancel(); future.cancel(true) }
        }

    private fun decode(uri: Uri, options: BitmapFactory.Options, signal: CancellationSignal): Bitmap? =
        context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
            descriptor.createInputStream().use { BitmapFactory.decodeStream(it, null, options) }
        }

    private fun prepare(program: WatchNextProgram, width: Int, height: Int, density: Float,
        token: Long, signal: CancellationSignal,
    ): Bitmap? {
        if (width <= 8 || height <= 8) return null
        for (value in listOfNotNull(program.poster, program.thumbnail).distinct()) {
            signal.throwIfCanceled()
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: continue
            if (uri.scheme !in listOf("content", "android.resource", "http", "https")) continue
            val key = "${program.key}:${program.engagementMs}:$value:$width:$height:$density"
            cache.get(key)?.let { return it }
            val image = try {
                val started = SystemClock.elapsedRealtime()
                val bytes = if (uri.scheme in listOf("http", "https")) files.get(value, signal) else null
                fun decodeImage(options: BitmapFactory.Options): Bitmap? = if (bytes != null)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) else decode(uri, options, signal)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                decodeImage(bounds)
                signal.throwIfCanceled()
                if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) continue
                val size = artworkSize(bounds.outWidth, bounds.outHeight, width, height, density) ?: continue
                val w = size.width
                val h = size.height
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val decoded = decodeImage(options) ?: continue
                val ready = SystemClock.elapsedRealtime()
                val result = try {
                    signal.throwIfCanceled()
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                        Canvas(it).drawBitmap(decoded, null, android.graphics.Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                } finally { decoded.recycle() }
                DiagLog.i("watch next artwork: read/decode ${ready - started} ms, prepare ${SystemClock.elapsedRealtime() - ready} ms")
                result
            } catch (_: Exception) { null }
            signal.throwIfCanceled()
            if (token != generation) return null
            if (image != null) {
                cache.put(key, image)
                while (cache.snapshot().size > WatchNextDefaults.CacheEntries) {
                    cache.snapshot().keys.firstOrNull()?.let { cache.remove(it) } ?: break
                }
                return image
            }
        }
        return null
    }
    fun clear() { generation++; prefetch(emptyList()); cache.evictAll(); backgroundCache = null }
    override fun onTrimMemory(level: Int) { clear() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() { clear() }
    override fun onConfigurationChanged(newConfig: Configuration) { clear() }
    override fun close() { clear(); executor.shutdownNow(); prefetchExecutor.shutdownNow(); context.unregisterComponentCallbacks(this) }
}
