package app.orchard.tv.proxy

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL

/** One answer to a (possibly ranged) GET. [body] is closed by whoever opened the reply. */
internal class CoreReply(
    /** 200, 206 or 416; anything else is an [CoreHttpException]. */
    val status: Int,
    /** First byte the body carries: 0 for a 200, the range start for a 206. */
    val offset: Long,
    /** Size of the whole file if the server said, else -1. */
    val total: Long,
    val body: InputStream,
) : AutoCloseable {
    override fun close() { runCatching { body.close() } }
}

internal class CoreHttpException(val status: Int) : IOException("http_$status")

/** Opens [url] from byte [from] on (a Range request when [from] > 0). Real network or a test fake. */
internal fun interface CoreFetch {
    @Throws(IOException::class)
    fun open(url: String, from: Long): CoreReply
}

/** Redirect and Range rules, pure so they can be tested without a socket. */
internal object CoreHttp {
    const val MAX_REDIRECTS = 5
    /** No bytes for this long and the source is given up on (the read times out). */
    const val STALL_MS = 20_000
    const val CONNECT_MS = 15_000

    /** https only, no credentials: the first URL and every redirect target. */
    fun checkedUrl(raw: String): String {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: throw IOException("core_bad_url")
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null) {
            throw IOException("core_bad_url")
        }
        return uri.toASCIIString()
    }

    /** The redirect target of [location] seen from [current]: relative locations allowed, http never. */
    fun redirect(current: String, location: String?): String {
        if (location.isNullOrBlank()) throw IOException("core_bad_redirect")
        return checkedUrl(URI(current).resolve(location.trim()).toString())
    }

    /** `bytes 100-999/1000` -> (100, 1000); the total is -1 when the server sends `*`. Null if unusable. */
    fun parseContentRange(value: String?): Pair<Long, Long>? {
        val m = Regex("^bytes (\\d{1,18})-(\\d{1,18})/(\\d{1,18}|\\*)$").matchEntire(value?.trim().orEmpty()) ?: return null
        val start = m.groupValues[1].toLong()
        val end = m.groupValues[2].toLong()
        val total = m.groupValues[3].toLongOrNull() ?: -1L
        if (end < start || (total >= 0 && end >= total)) return null
        return start to total
    }
}

/**
 * The real fetcher. Follows redirects itself so each hop can be checked (https only, at most
 * [CoreHttp.MAX_REDIRECTS]); never uses a proxy (the core is not running yet, and Aerial's own
 * traffic is outside its VPN anyway). Asks for the identity encoding: the asset is already .gz and
 * a transparently decoded body would break Content-Length and Range.
 */
internal class HttpCoreFetch : CoreFetch {
    override fun open(url: String, from: Long): CoreReply {
        var current = CoreHttp.checkedUrl(url)
        repeat(CoreHttp.MAX_REDIRECTS + 1) { hop ->
            val connection = URL(current).openConnection(Proxy.NO_PROXY) as HttpURLConnection
            var handedOver = false
            try {
                connection.connectTimeout = CoreHttp.CONNECT_MS
                connection.readTimeout = CoreHttp.STALL_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "Aerial")
                connection.setRequestProperty("Accept-Encoding", "identity")
                if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
                val status = connection.responseCode
                when (status) {
                    in 300..399 -> {
                        if (hop >= CoreHttp.MAX_REDIRECTS) throw IOException("core_too_many_redirects")
                        current = CoreHttp.redirect(current, connection.getHeaderField("Location"))
                    }
                    200 -> {
                        handedOver = true
                        return CoreReply(200, 0, connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L,
                            Closing(connection.inputStream, connection))
                    }
                    206 -> {
                        val range = CoreHttp.parseContentRange(connection.getHeaderField("Content-Range"))
                            ?: throw IOException("core_bad_range")
                        handedOver = true
                        return CoreReply(206, range.first, range.second, Closing(connection.inputStream, connection))
                    }
                    416 -> return CoreReply(416, 0, -1L, java.io.ByteArrayInputStream(ByteArray(0)))
                    else -> throw CoreHttpException(status)
                }
            } finally {
                if (!handedOver) connection.disconnect()
            }
        }
        throw IOException("core_too_many_redirects")
    }

    /** Closing the body also drops the connection, which is what unblocks a cancelled read. */
    private class Closing(private val inner: InputStream, private val connection: HttpURLConnection) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() { runCatching { inner.close() }; connection.disconnect() }
    }
}
