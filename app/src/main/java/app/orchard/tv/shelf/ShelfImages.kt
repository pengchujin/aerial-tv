package app.orchard.tv.shelf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import app.orchard.tv.wallpaper.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pictures for shelf cards and the backdrop behind them. Apps publish http(s), content: and
 * android.resource: URIs; Emby's point at the owner's media server on the LAN, so a slow or
 * unreachable server must not hold anything up: short timeouts, and a failed picture is a card
 * that shows its title instead.
 */
object ShelfImages {
    private const val TAG = "ShelfImages"
    private lateinit var app: Context

    /** Decoded pictures by URI and target size; about 24 MB at most. */
    private val cards = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Frosted backdrops by URI: (shaded for Home, plain frosted for the grid and the shelf glass). */
    private val backdrops = LruCache<String, Pair<Bitmap, Bitmap>>(12)

    /** A home media server is often a small box: a few requests at a time, not a burst of twenty. */
    private val gate = Semaphore(3)

    /** Far more than any poster; a server that sends more is not sending a picture. */
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** The picture at [uri], decoded to cover [w]×[h] px. */
    suspend fun card(uri: String, w: Int, h: Int): Bitmap? {
        val key = "$uri@${w}x$h"
        cards.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = fetch(uri) ?: return@withContext null
            decode(bytes, w, h)?.also { cards.put(key, it) }
        }
    }

    /**
     * The backdrop for a card: its picture blurred small and shaded the way Home shades a
     * wallpaper, drawn stretched in one pass. Blurred, a 300 px poster looks as good as a 4K one.
     */
    suspend fun backdrop(uri: String): Pair<Bitmap, Bitmap>? {
        backdrops.get(uri)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bytes = fetch(uri) ?: return@withContext null
            val src = decode(bytes, 640, 360) ?: return@withContext null
            val frost = Images.frost(src)
            val shaded = Images.shadeForHome(frost.copy(Bitmap.Config.ARGB_8888, true))
            (shaded to frost).also { backdrops.put(uri, it) }
        }
    }

    /** Hosts that just failed twice, until when to skip them: an unreachable server costs one wait, not twenty. */
    private val down = HashMap<String, Long>()

    /**
     * The server answered, but with no picture (Emby answers 404 for items without the kind of
     * image it published a link to). Not retried, and the host is not down: its other pictures
     * still come.
     */
    private class NoPicture(code: Int) : Exception("HTTP $code")

    /** One retry after a pause: a busy server drops a connection now and then. */
    private suspend fun fetch(uri: String): ByteArray? {
        val host = Uri.parse(uri).takeIf { it.scheme == "http" || it.scheme == "https" }?.host
        fun isDown() = host != null && synchronized(down) { (down[host] ?: 0L) > System.currentTimeMillis() }
        if (isDown()) return null
        return gate.withPermit {
            // Checked again after the wait: the cards queued behind a failing one skip it too.
            if (isDown()) return@withPermit null
            var result = read(uri)
            if (result.isFailure && result.exceptionOrNull() !is NoPicture) {
                delay(1_500)
                result = read(uri)
            }
            if (result.isFailure && result.exceptionOrNull() !is NoPicture && host != null) {
                synchronized(down) { down[host] = System.currentTimeMillis() + 60_000 }
            }
            result.getOrNull()
        }
    }

    private fun read(uri: String): Result<ByteArray?> = runCatching {
        val u = Uri.parse(uri)
        when (u.scheme) {
            "http", "https" -> {
                val c = URL(uri).openConnection() as HttpURLConnection
                c.connectTimeout = 3_000
                c.readTimeout = 5_000
                try {
                    if (c.responseCode !in 200..299) throw NoPicture(c.responseCode)
                    c.inputStream.use(::readCapped)
                } finally { c.disconnect() }
            }
            "content", "android.resource", "file" -> app.contentResolver.openInputStream(u)?.use(::readCapped)
            else -> null
        }
    }.onFailure {
        // The host only: media servers put their access token in the query.
        Log.i(TAG, "no picture from ${Uri.parse(uri).let { it.scheme + "://" + it.authority }}: ${it.message}")
    }

    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            check(out.size() <= MAX_BYTES) { "picture over ${MAX_BYTES / 1024 / 1024} MB" }
        }
    }

    private fun decode(bytes: ByteArray, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return Images.cover(src, w, h).also { if (it !== src) src.recycle() }
    }
}
