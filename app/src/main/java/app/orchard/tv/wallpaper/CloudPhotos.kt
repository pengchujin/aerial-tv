package app.orchard.tv.wallpaper

import android.content.Context
import android.util.Log
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Photos from a WebDAV folder, kept in step with that folder: the pictures in it are downloaded as
 * the owner's own photos (files/photos/dav-<id>.jpg, so they are told apart from phone uploads and
 * only they are ever removed here), and so the photo screensaver and the "photos, one a day"
 * wallpaper play them with the rest, without any change to those.
 *
 * WebDAV is what NAS boxes, Nextcloud, and the bridges that turn a cloud drive into a folder
 * (Alist/OpenList for Baidu, Aliyun, Google Drive, OneDrive, ...) all speak, so one client covers
 * them. Google Photos itself has no such door: its API only gives an app the pictures that app
 * uploaded. A folder is read with PROPFIND, which HttpURLConnection refuses to send, so that one
 * request goes over a plain socket (no HTTP library: Aerial carries none); the pictures themselves
 * are ordinary GETs.
 *
 * The password is kept in this app's private preferences, as given, because it has to be sent back.
 */
object CloudPhotos {
    private const val TAG = "CloudPhotos"
    private const val PREFS = "cloud_photos"
    const val PREFIX = "dav-"
    /** Most pictures kept: each is up to 2560 px wide, and a TV has little room. */
    private const val MAX_PHOTOS = 150
    private const val MAX_FILE_BYTES = 30L * 1024 * 1024
    private const val MAX_LISTING_BYTES = 8 * 1024 * 1024
    private const val MAX_FOLDERS = 60
    private const val MAX_DEPTH = 3
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    class Config(val url: String, val user: String, val password: String) {
        val on get() = url.isNotBlank()
    }

    /** One of "idle" (not set up), "syncing", "ok", "error". */
    var state by mutableStateOf("idle"); private set
    /** Pictures kept after the last sync. */
    var count by mutableIntStateOf(0); private set
    /** Why the last sync failed: "auth", "unreachable", "bad_url", "listing", or a short message. */
    var detail by mutableStateOf(""); private set

    private val lock = Mutex()

    fun config(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(p.getString("url", "") ?: "", p.getString("user", "") ?: "", p.getString("password", "") ?: "")
    }

    /** [password] null keeps the one saved, so the phone page never has to be sent it back. */
    fun save(context: Context, url: String, user: String, password: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString("url", normalize(url))
            putString("user", user.trim())
            if (password != null) putString("password", password)
        }
        if (url.isBlank()) { state = "idle"; count = 0; detail = "" }
    }

    /** Adds http:// when the owner typed a bare address, and a closing slash: it names a folder. */
    fun normalize(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        val withScheme = if (t.contains("://")) t else "http://$t"
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    internal class Entry(val url: URL, val isFolder: Boolean)

    /** Brings the photos on the TV in step with the folder. True when the folder was read. */
    suspend fun sync(context: Context): Boolean = lock.withLock {
        val cfg = config(context)
        if (!cfg.on) { state = "idle"; return@withLock false }
        state = "syncing"
        val root = runCatching { URL(cfg.url) }.getOrNull()
        if (root == null || (root.protocol != "http" && root.protocol != "https") || root.host.isNullOrEmpty()) {
            state = "error"; detail = "bad_url"; return@withLock false
        }
        val auth = if (cfg.user.isNotEmpty()) "Basic " + android.util.Base64.encodeToString("${cfg.user}:${cfg.password}".toByteArray(), android.util.Base64.NO_WRAP) else null
        val images = try {
            withContext(Dispatchers.IO) { listImages(root, auth) }
        } catch (e: AuthFailed) {
            state = "error"; detail = "auth"; return@withLock false
        } catch (e: Exception) {
            Log.w(TAG, "listing failed", e)
            state = "error"; detail = if (e is ListingFailed) "listing" else "unreachable"; return@withLock false
        }
        val wanted = images.take(MAX_PHOTOS).associateBy { idOf(it.url) }
        val have = Wallpapers.cloudPhotoIds()
        var failed = 0
        for ((id, entry) in wanted) {
            if (id in have) continue
            val ok = withContext(Dispatchers.IO) { runCatching { download(entry.url, auth) { Wallpapers.addCloudPhoto(id, it) } }.getOrElse { Log.w(TAG, "download failed: ${entry.url}", it); false } }
            if (!ok) failed++
        }
        Wallpapers.removeCloudPhotos(have - wanted.keys)
        Wallpapers.cloudChanged()
        count = Wallpapers.cloudPhotoIds().size
        detail = if (failed > 0) "partial:$failed" else ""
        state = "ok"
        true
    }

    private fun idOf(url: URL): String =
        PREFIX + MessageDigest.getInstance("SHA-1").digest(url.path.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    internal class AuthFailed : Exception()
    internal class ListingFailed(message: String) : Exception(message)

    internal fun listImages(root: URL, auth: String?): List<Entry> {
        val images = ArrayList<Entry>()
        val seen = HashSet<String>()
        var folders = 0
        fun walk(folder: URL, depth: Int) {
            if (!seen.add(folder.path) || folders++ >= MAX_FOLDERS) return
            for (e in propfind(folder, auth)) {
                if (e.isFolder) { if (depth < MAX_DEPTH) walk(e.url, depth + 1) }
                else if (e.url.path.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS) images += e
            }
        }
        walk(root, 0)
        return images.sortedBy { it.url.path }
    }

    private fun propfind(folder: URL, auth: String?): List<Entry> {
        var target = folder
        repeat(4) {
            val headers = LinkedHashMap<String, String>()
            headers["Depth"] = "1"
            headers["Content-Type"] = "application/xml"
            if (auth != null) headers["Authorization"] = auth
            val body = """<?xml version="1.0"?><propfind xmlns="DAV:"><prop><resourcetype/></prop></propfind>""".toByteArray()
            val (status, location, bytes) = request("PROPFIND", target, headers, body)
            when (status) {
                207 -> return parse(target, bytes)
                301, 302, 307, 308 -> target = URL(target, location ?: throw ListingFailed("redirect"))
                401, 403 -> throw AuthFailed()
                else -> throw ListingFailed("status $status")
            }
        }
        throw ListingFailed("redirects")
    }

    private fun parse(base: URL, xml: ByteArray): List<Entry> {
        val parser = Xml.newPullParser()
        parser.setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(xml.inputStream(), null)
        val out = ArrayList<Entry>()
        var href: String? = null
        var folder = false
        val self = decode(base.path).trimEnd('/')
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "response" -> { href = null; folder = false }
                    "href" -> if (href == null) href = parser.nextText().trim()
                    "collection" -> folder = true
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "response") {
                href?.let { h ->
                    val url = runCatching { URL(base, h) }.getOrNull()
                    // The folder lists itself first; and an entry on another server is not ours to read.
                    if (url != null && url.host == base.host && decode(url.path).trimEnd('/') != self) out += Entry(url, folder || h.endsWith("/"))
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun decode(path: String) = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)

    private inline fun download(url: URL, auth: String?, store: (InputStream) -> Boolean): Boolean {
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        if (auth != null) c.setRequestProperty("Authorization", auth)
        try {
            if (c.responseCode != 200) return false
            if ((c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L) > MAX_FILE_BYTES) return false
            return c.inputStream.use { store(LimitedInput(it, MAX_FILE_BYTES)) }
        } finally {
            c.disconnect()
        }
    }

    private class LimitedInput(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            return inner.read().also { if (it >= 0) left-- }
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    /** One HTTP/1.1 request over a socket; returns status, Location, and body. */
    private fun request(method: String, url: URL, headers: Map<String, String>, body: ByteArray?): Triple<Int, String?, ByteArray> {
        val https = url.protocol == "https"
        val port = if (url.port != -1) url.port else if (https) 443 else 80
        val raw = Socket()
        raw.connect(InetSocketAddress(url.host, port), 10_000)
        raw.soTimeout = 20_000
        val socket: Socket = if (https) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, url.host, port, true) as SSLSocket
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(url.host, ssl.session)) throw SSLPeerUnverifiedException(url.host)
            ssl
        } else raw
        socket.use {
            val path = (if (url.path.isEmpty()) "/" else url.path) + (url.query?.let { "?$it" } ?: "")
            val host = if (url.port != -1) "${url.host}:${url.port}" else url.host
            val head = StringBuilder("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: Aerial\r\n")
            headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("Content-Length: ").append(body?.size ?: 0).append("\r\n\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray())
            if (body != null) out.write(body)
            out.flush()
            val input = socket.getInputStream().buffered()
            fun line(): String? {
                val sb = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (c == '\n'.code) return sb.toString().trimEnd('\r')
                    sb.append(c.toChar())
                }
            }
            val status = line()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw ListingFailed("no status")
            var length = -1L
            var chunked = false
            var location: String? = null
            while (true) {
                val h = line() ?: break
                if (h.isEmpty()) break
                val name = h.substringBefore(':').trim().lowercase()
                val value = h.substringAfter(':', "").trim()
                when (name) {
                    "content-length" -> length = value.toLongOrNull() ?: -1L
                    "transfer-encoding" -> chunked = value.lowercase().contains("chunked")
                    "location" -> location = value
                }
            }
            val bytes = ByteArrayOutputStream()
            fun take(n: Long) {
                var left = n
                val buf = ByteArray(8192)
                while (left > 0) {
                    val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) break
                    if (bytes.size() + r > MAX_LISTING_BYTES) throw ListingFailed("too large")
                    bytes.write(buf, 0, r)
                    left -= r
                }
            }
            if (chunked) {
                while (true) {
                    val size = line()?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: break
                    if (size == 0L) break
                    take(size)
                    line()
                }
            } else if (length >= 0) take(length)
            else take(MAX_LISTING_BYTES.toLong())
            return Triple(status, location, bytes.toByteArray())
        }
    }
}
