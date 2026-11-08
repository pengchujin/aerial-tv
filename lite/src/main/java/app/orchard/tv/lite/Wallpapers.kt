package app.orchard.tv.lite

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class WallpaperKind { Daily, Builtin, Photo }

class Wallpaper(val id: String, val kind: WallpaperKind, val title: String, val caption: String? = null)

/** The full app's screensaver kinds that Lite has (no system screensaver: Lite is no DreamService). */
enum class ScreensaverMode(val key: String) {
    Aerial("aerial"), Photos("photos"), Clock("clock"), Videos("videos");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Aerial
    }
}

/**
 * What is behind Home, as the full app's wallpaper/Wallpapers.kt with its ids, so the phone page
 * works the same: Bing's photo of the day ("daily:latest" follows the newest; the last eight are
 * kept), Aerial's built-in pictures ("grad:<key>"), the owner's photos from the phone
 * ("photo:<id>"; "photos:rotate" shows a different one each day). Lite has no moving wallpapers.
 *
 * Home's backdrop is baked once per picture at the screen's size, shaded, kept on disk; its frosted
 * copy makes the glass. Main thread for state; [load] and the fetches run on a background thread.
 */
object Wallpapers {
    private const val TAG = "AerialLite"
    const val LATEST_DAILY = "daily:latest"
    const val ROTATE_PHOTOS = "photos:rotate"
    const val VIDEOS = "videos:mine"
    private const val FOLLOW = "follow"
    private const val FALLBACK = "hills_dusk"

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var dailyDir: File
    private lateinit var photoDir: File

    var selectedId = LATEST_DAILY; private set
    @Volatile var items: List<Wallpaper> = emptyList(); private set
    /** Null: the screensaver follows the wallpaper. */
    private var chosenScreensaver: ScreensaverMode? = null

    val screensaverFollows: Boolean get() = chosenScreensaver == null

    /** The full app's rule: a picture wallpaper's screensaver is its photos; Lite has no wallpaper film. */
    val screensaver: ScreensaverMode get() = chosenScreensaver ?: ScreensaverMode.Photos

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)
        dailyDir = File(app.filesDir, "daily").apply { mkdirs() }
        photoDir = File(app.filesDir, "photos").apply { mkdirs() }
        selectedId = prefs.getString("selected", LATEST_DAILY) ?: LATEST_DAILY
        chosenScreensaver = when (val key = prefs.getString("screensaver", null)) {
            null, FOLLOW -> null
            else -> ScreensaverMode.of(key)
        }
        rebuildList()
    }

    fun select(id: String) {
        selectedId = id
        prefs.edit().putString("selected", id).apply()
    }

    fun chooseScreensaver(mode: ScreensaverMode) {
        chosenScreensaver = mode
        prefs.edit().putString("screensaver", mode.key).apply()
    }

    /** The concrete wallpaper the selection points at today. */
    fun resolve(id: String = selectedId): Wallpaper? = when (id) {
        LATEST_DAILY -> items.firstOrNull { it.kind == WallpaperKind.Daily }
        ROTATE_PHOTOS -> items.filter { it.kind == WallpaperKind.Photo }.sortedBy { it.id }.let { photos ->
            if (photos.isEmpty()) resolve(LATEST_DAILY) else photos[(photoSlot() % photos.size).toInt()]
        }
        else -> items.firstOrNull { it.id == id }
    }

    /** Which turn "My Photos, in turn" is on: the local day, or the count of the chosen minutes (#1314). */
    private fun photoSlot(): Long {
        val now = System.currentTimeMillis()
        return (now + TimeZone.getDefault().getOffset(now)) / 60_000L / LiteSettings.photoMinutes.coerceAtLeast(1)
    }

    /** The turn the backdrop was last baked for, as [photoSlot]. */
    private var bakedSlot = Long.MIN_VALUE

    /** Whether "My Photos, in turn" is due for its next photo; notes the turn as baked when so. */
    fun rotationDue(): Boolean {
        if (selectedId != ROTATE_PHOTOS) return false
        val slot = photoSlot()
        if (slot == bakedSlot) return false
        bakedSlot = slot
        return true
    }

    /** The key the baked backdrop is kept under: the picture, and its file's age. */
    private fun bakeKey(): String {
        val wp = resolve() ?: return "grad:$FALLBACK"
        return wp.id + "@" + (fileFor(wp)?.lastModified() ?: 0)
    }

    fun rebuildList() {
        val zh = Locale.getDefault().language == "zh"
        val daily = readDailyIndex().map { Wallpaper("daily:${it.first}", WallpaperKind.Daily, it.second, it.third) }
        val builtins = Gradients.presets.map { Wallpaper("grad:${it.key}", WallpaperKind.Builtin, if (zh) it.nameZh else it.nameEn) }
        val photos = (photoDir.listFiles { f -> f.name.endsWith(".jpg") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { Wallpaper("photo:${it.name.removeSuffix(".jpg")}", WallpaperKind.Photo, if (zh) "我的照片" else "My Photo") }
        items = daily + builtins + photos
    }

    private fun fileFor(w: Wallpaper): File? = when (w.kind) {
        WallpaperKind.Daily -> File(dailyDir, w.id.removePrefix("daily:") + ".jpg")
        WallpaperKind.Photo -> File(photoDir, w.id.removePrefix("photo:") + ".jpg")
        else -> null
    }

    /** The full picture at [w]×[h], unshaded. Off the main thread. */
    fun picture(wp: Wallpaper, w: Int, h: Int, whole: Boolean = false): Bitmap? = when (wp.kind) {
        WallpaperKind.Builtin -> Gradients.byKey(wp.id.removePrefix("grad:"))?.let { Gradients.render(it, w, h) }
        else -> fileFor(wp)?.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h, whole = whole && wp.kind == WallpaperKind.Photo) }
    }

    private val thumbs = HashMap<String, Bitmap>()

    /** A 384×216 thumbnail, for Personalize and the phone page. Off the main thread. */
    fun thumbnail(id: String): Bitmap? {
        synchronized(thumbs) { thumbs[id] }?.let { return it }
        val wp = resolve(id) ?: return null
        val b = picture(wp, 384, 216) ?: return null
        synchronized(thumbs) { thumbs[id] = b }
        return b
    }

    /** The photo screensaver's pictures: the owner's photos when there are any, else the daily ones, else the built-ins (issue #27). */
    fun saverPictures(): List<Wallpaper> {
        val photos = items.filter { it.kind == WallpaperKind.Photo }.ifEmpty { items.filter { it.kind == WallpaperKind.Daily } }
        return photos.ifEmpty { items.filter { it.kind == WallpaperKind.Builtin } }.shuffled()
    }

    // ---- the backdrop ----

    /** Home's backdrop, shaded and baked at the screen's size, and the same picture frosted (before the shading) for the glass. */
    class Backdrop(val home: Bitmap, val frost: Bitmap)

    private fun homeFile() = File(app.filesDir, "backdrop.png")
    private fun frostFile() = File(app.filesDir, "frost.png")

    /** The backdrop of the current choice. Off the main thread. */
    fun load(): Backdrop {
        bakedSlot = photoSlot()
        val key = bakeKey() + "@${Ui.screenW}x${Ui.screenH}"
        val f = homeFile()
        val ff = frostFile()
        if (prefs.getString("baked", null) == key && f.exists() && ff.exists()) {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                @Suppress("DEPRECATION")
                inDither = true
            }
            val home = BitmapFactory.decodeFile(f.path, opts)
            val frost = BitmapFactory.decodeFile(ff.path)
            if (home != null && frost != null) return Backdrop(home, frost)
        }
        val full = resolve()?.let { picture(it, Ui.screenW, Ui.screenH) }
            ?: Gradients.render(Gradients.byKey(FALLBACK)!!, Ui.screenW, Ui.screenH)
        val frost = Images.frost(full)
        Images.shadeForHome(full)
        runCatching {
            val tmp = File(f.path + ".part")
            FileOutputStream(tmp).use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val ftmp = File(ff.path + ".part")
            FileOutputStream(ftmp).use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (tmp.renameTo(f) && ftmp.renameTo(ff)) prefs.edit().putString("baked", key).apply()
        }
        val small = full.copy(Bitmap.Config.RGB_565, false)
        full.recycle()
        return Backdrop(small, frost)
    }

    // ---- daily photos ----

    private fun readDailyIndex(): List<Triple<String, String, String>> = runCatching {
        val arr = JSONArray(File(dailyDir, "index.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("date"), o.getString("title"), o.getString("caption"))
        }.filter { File(dailyDir, it.first + ".jpg").exists() }
    }.getOrDefault(emptyList())

    /** Bing's dates are China's, as yyyyMMdd. */
    private fun hasTodaysDaily(): Boolean {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }
        return File(dailyDir, fmt.format(Date()) + ".jpg").exists()
    }

    /**
     * Fetches the last eight of Bing's photos (cn.bing.com, reachable from China), as the full app:
     * at most every six hours, or every half hour until today's is out. True when something new
     * arrived. Off the main thread. Old Androids do not trust Bing's root; it answers over HTTP too.
     */
    fun refreshDaily(): Boolean {
        val now = System.currentTimeMillis()
        val since = now - prefs.getLong("daily_checked", 0)
        if (since < 6 * 60 * 60 * 1000L && (hasTodaysDaily() || since < 30 * 60 * 1000L)) return false
        return runCatching {
            val market = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
            val marketFile = File(dailyDir, "market")
            val before = marketFile.takeIf { it.exists() }?.readText() ?: market
            val json = JSONObject(bing("/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=$market"))
            if (before != market) dailyDir.listFiles { f -> f.name.endsWith(".jpg") }?.forEach { it.delete() }
            marketFile.writeText(market)
            val images = json.getJSONArray("images")
            val index = JSONArray()
            var added = false
            for (i in 0 until images.length()) {
                val img = images.getJSONObject(i)
                val date = img.getString("startdate")
                val file = File(dailyDir, "$date.jpg")
                if (!file.exists()) {
                    val path = if (Ui.screenW > 1920) img.getString("urlbase") + "_UHD.jpg" else img.getString("url")
                    val tmp = File(file.path + ".part")
                    val c = open("https://cn.bing.com$path")
                    try { c.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } } } finally { c.disconnect() }
                    if (tmp.renameTo(file)) added = true
                }
                val copyright = img.optString("copyright")
                val title = img.optString("title").ifBlank { copyright.substringBefore(" (").ifBlank { date } }
                index.put(JSONObject().put("date", date).put("title", title).put("caption", copyright))
            }
            File(dailyDir, "index.json").writeText(index.toString())
            val keep = (0 until index.length()).map { index.getJSONObject(it).getString("date") + ".jpg" }.toSet()
            dailyDir.listFiles { f -> f.name.endsWith(".jpg") && f.name !in keep }?.forEach { it.delete() }
            prefs.edit().putLong("daily_checked", now).apply()
            if (added) synchronized(thumbs) { thumbs.remove(LATEST_DAILY) }
            added
        }.onFailure { Log.w(TAG, "daily photos unavailable", it) }.getOrDefault(false)
    }

    private fun open(url: String): java.net.HttpURLConnection = try {
        Net.open(url).also { it.responseCode }
    } catch (e: javax.net.ssl.SSLException) {
        Net.open(url.replaceFirst("https://", "http://"))
    }

    private fun bing(path: String): String {
        val c = open("https://cn.bing.com$path")
        try { return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() }
    }

    // ---- photos from the phone ----

    /** Stores an uploaded picture, at most 2560 wide, and returns its id. Off the main thread. */
    fun addPhoto(input: InputStream): String? {
        val tmp = File.createTempFile("upload", ".bin", app.cacheDir)
        return try {
            FileOutputStream(tmp).use { input.copyTo(it) }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth <= 0) return null
            // Stored upright: the JPEG written below keeps no EXIF, so the turn is applied now (#647).
            val orientation = Images.exifOrientation(tmp)
            val wide = if (Images.quarterTurn(orientation)) bounds.outHeight else bounds.outWidth
            var sample = 1
            while (wide / (sample * 2) >= 2560) sample *= 2
            val decoded = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val bmp = Images.upright(decoded, orientation)
            val name = System.currentTimeMillis().toString(36)
            FileOutputStream(File(photoDir, "$name.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            synchronized(thumbs) { thumbs.remove(ROTATE_PHOTOS) }
            "photo:$name"
        } finally {
            tmp.delete()
        }
    }

    /** Only a listed photo: the id comes from the phone page (the full app's #57). */
    fun deletePhoto(id: String): Boolean {
        val photo = items.firstOrNull { it.id == id && it.kind == WallpaperKind.Photo } ?: return false
        fileFor(photo)?.delete()
        synchronized(thumbs) { thumbs.remove(id); thumbs.remove(ROTATE_PHOTOS) }
        rebuildList()
        if (selectedId == id) select(LATEST_DAILY)
        return true
    }
}
