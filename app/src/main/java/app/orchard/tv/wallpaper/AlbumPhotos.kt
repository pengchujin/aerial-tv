package app.orchard.tv.wallpaper

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pictures from a Google Photos shared album, read from the album's public link, kept as the
 * owner's own photos (files/photos/gp-<id>.jpg) like [CloudPhotos] does for a WebDAV folder.
 *
 * Google Photos has no API that reads an owner's library any more (its Library API only returns what
 * the calling app uploaded), so this reads what a browser would: the shared album's web page, which
 * lists each picture's lh3.googleusercontent.com address. That is not a documented interface and
 * Google can change it without notice; when the page stops yielding pictures the status says "no
 * pictures" and nothing is deleted. The page holds the first few hundred pictures of an album.
 *
 * A "live album" (Google Photos app: People and pets > choose who > shared album) fills itself with
 * the photos of the people picked, which is how the face groups reach the TV.
 */
object AlbumPhotos {
    private const val TAG = "AlbumPhotos"
    private const val PREFS = "album_photos"
    const val PREFIX = "gp-"
    private const val MAX_PHOTOS = 150
    private const val MAX_PAGE_BYTES = 12 * 1024 * 1024
    private const val MAX_FILE_BYTES = 30L * 1024 * 1024
    // Not a full browser string: the short link answers one of those with a page that needs JavaScript
    // to continue (a 200, no redirect), and answers this with the 302 to the album's page (checked on a
    // real album, 2026-11-08, which had 300 pictures in its page).
    private const val USER_AGENT = "Mozilla/5.0"
    /** Pictures in a shared album's page; others on it (avatars, the cover's owner) have other prefixes. */
    private val PICTURE = Regex("""\["(https://lh3\.googleusercontent\.com/pw/[A-Za-z0-9_\-]+)",(\d+),(\d+)""")

    /** One of "idle" (not set up), "syncing", "ok", "error". */
    var state by mutableStateOf("idle"); private set
    var count by mutableIntStateOf(0); private set
    /** Why the last sync failed: "bad_url", "unreachable", "no_pictures". */
    var detail by mutableStateOf(""); private set

    private val lock = Mutex()

    fun url(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", "") ?: ""

    fun save(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("url", url.trim()) }
        if (url.isBlank()) { state = "idle"; count = 0; detail = "" }
    }

    /** Only Google's own album links: the short photos.app.goo.gl one the share sheet gives, or photos.google.com/share. */
    fun isAlbumLink(raw: String): Boolean {
        val u = runCatching { URL(raw.trim()) }.getOrNull() ?: return false
        if (u.protocol != "https") return false
        return u.host == "photos.app.goo.gl" || (u.host == "photos.google.com" && u.path.contains("/share/"))
    }

    /** The picture addresses on an album page, in page order, without repeats. */
    internal fun pictures(html: String): List<String> {
        // The page carries its data JSON-escaped: "\/" and "=" for "/" and "=".
        val text = html.replace("\\/", "/").replace("\\u003d", "=").replace("\\u0026", "&")
        return PICTURE.findAll(text)
            // Width and height as given are what a picture is; thumbnails of videos and album covers are small.
            .filter { it.groupValues[2].toInt() >= 200 && it.groupValues[3].toInt() >= 200 }
            .map { it.groupValues[1] }.distinct().toList()
    }

    private fun idOf(base: String): String =
        PREFIX + MessageDigest.getInstance("SHA-1").digest(base.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** Brings the photos on the TV in step with the album. True when the album page was read and had pictures. */
    suspend fun sync(context: Context): Boolean = lock.withLock {
        val link = url(context)
        if (link.isBlank()) { state = "idle"; return@withLock false }
        if (!isAlbumLink(link)) { state = "error"; detail = "bad_url"; return@withLock false }
        state = "syncing"
        val bases = try {
            withContext(Dispatchers.IO) { pictures(page(link)) }
        } catch (e: Exception) {
            Log.w(TAG, "album page failed", e)
            state = "error"; detail = "unreachable"; return@withLock false
        }
        // Nothing found: the link is wrong or Google changed the page. Keep what is on the TV.
        if (bases.isEmpty()) { state = "error"; detail = "no_pictures"; return@withLock false }
        val wanted = bases.take(MAX_PHOTOS).associateBy(::idOf)
        val have = Wallpapers.cloudPhotoIds(PREFIX)
        var failed = 0
        for ((id, base) in wanted) {
            if (id in have) continue
            val ok = withContext(Dispatchers.IO) {
                runCatching { download("$base=w2560-h1440") { Wallpapers.addCloudPhoto(id, it) } }
                    .getOrElse { Log.w(TAG, "download failed: $base", it); false }
            }
            if (!ok) failed++
        }
        Wallpapers.removeCloudPhotos(have - wanted.keys)
        Wallpapers.cloudChanged()
        count = Wallpapers.cloudPhotoIds(PREFIX).size
        detail = if (failed > 0) "partial:$failed" else ""
        state = "ok"
        true
    }

    private fun page(link: String): String {
        val c = URL(link).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", USER_AGENT)
        c.setRequestProperty("Accept-Language", "en")
        try {
            if (c.responseCode != 200) throw java.io.IOException("status ${c.responseCode}")
            // The short link redirects to the album's page; a redirect to a sign-in or consent page is not it.
            if (c.url.host != "photos.google.com") throw java.io.IOException("redirected to ${c.url.host}")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16384)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_PAGE_BYTES) break
                    out.write(buf, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    private inline fun download(url: String, store: (java.io.InputStream) -> Boolean): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode != 200) return false
            if ((c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L) > MAX_FILE_BYTES) return false
            return c.inputStream.use { store(it) }
        } finally {
            c.disconnect()
        }
    }
}
