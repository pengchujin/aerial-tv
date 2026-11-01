package app.orchard.tv.wallpaper

import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import app.orchard.tv.ui.components.UhdCanvas
import app.orchard.tv.BuildConfig
import app.orchard.tv.guard.GuardSetup
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.system.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class WallpaperKind { Daily, Builtin, Photo, Auto, Live, Video, Source }

data class Wallpaper(
    val id: String,
    val kind: WallpaperKind,
    val title: String,
    /** Where the picture was taken, or who took it; shown in the screensaver. */
    val caption: String? = null,
)

/** System: none of Aerial's; the TV's own screensaver starts as the firmware has it (SystemScreensaver). */
enum class ScreensaverMode(val key: String) {
    Aerial("aerial"), Photos("photos"), Clock("clock"), Videos("videos"), System("system");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Aerial
    }
}

/**
 * Everything behind the home screen: which wallpaper is chosen, where pictures come from, and what
 * the screensaver shows. Sources:
 *
 * - Built-in: Aerial's own Mac-style pictures, drawn on the TV ([Gradients]).
 * - Daily: Bing's photo of the day (cn.bing.com, reachable from China without a proxy). The last
 *   eight are kept; "daily:latest" always follows the newest.
 * - Photos: pictures uploaded from a phone through the setup page ([PhoneServer]). "photos:rotate"
 *   shows a different one of them each day (issue #27).
 * - Auto: the previous behaviour, light taken from the focused app's artwork.
 * - Live: Apple's aerial films, moving once Home has sat still a while (ui/home/LiveWallpaper.kt).
 *   Its picture is the frame the film last stopped on, so the glass and the grid match it.
 * - Video: the owner's own films, sent from the phone (ScreensaverVideos), moving the same way
 *   (ui/home/VideoWallpaper.kt), with the frame they stopped on as the picture (issues #410, #583).
 *   "videos:mine" plays all of them in turn; "video:<id>" just that one, on a loop (issue #762).
 * - Source: a TV input's picture behind Home, a set-top box on HDMI (ui/home/SourceWallpaper.kt,
 *   issue #267). Only inputs Android lists can be shown; its picture is a dark built-in one.
 *
 * State is Compose snapshot state, written on the main thread only.
 */
object Wallpapers {
    private const val TAG = "AerialWallpaper"
    const val LATEST_DAILY = "daily:latest"
    private const val DAILY_RETRY_MS = 30 * 60 * 1000L
    const val AUTO = "auto"
    const val ROTATE_PHOTOS = "photos:rotate"
    const val LIVE = "aerial:live"
    const val VIDEOS = "videos:mine"
    const val VIDEO_PREFIX = "video:"
    const val SOURCE_PREFIX = "source:"
    /** The picture under a TV input's, for the glass and while the input has nothing to show. */
    private const val SOURCE_PICTURE = "hills_night"
    private const val FOLLOW = "follow"
    private const val DEFAULT_FALLBACK = "grad:aurora"

    /** Let go this close to where the picture was taken: the film did not move on from it. */
    private const val SAME_SPOT_MS = 2_000L

    /** The built-ins Personalize offers, one or two of each family; the rest stay for whoever chose them. */
    val FEATURED_BUILTINS = setOf("grad:hills_dawn", "grad:hills_night", "grad:dunes_gold", "grad:sea_sunset", "grad:waves_blue", "grad:aurora")

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()
    private val prefs by lazy { app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE) }
    private val dailyDir by lazy { File(app.filesDir, "daily").apply { mkdirs() } }
    private val photoDir by lazy { File(app.filesDir, "photos").apply { mkdirs() } }
    private val liveFrame by lazy { File(app.filesDir, "live-frame.jpg") }
    private val videoFrame by lazy { File(app.filesDir, "video-frame.jpg") }

    /** TV inputs that can play behind Home, by TvInputInfo id and name; set as Home lists inputs. */
    private var sources: List<Pair<String, String>> = emptyList()
    private val bakedDir by lazy { File(app.filesDir, "backdrop").apply { mkdirs() } }
    private val thumbs = HashMap<String, Bitmap>()

    var items by mutableStateOf<List<Wallpaper>>(emptyList()); private set
    var selectedId by mutableStateOf(LATEST_DAILY); private set
    /** The screensaver the owner chose; null while it follows the wallpaper ([screensaver]). */
    private var chosenScreensaver by mutableStateOf<ScreensaverMode?>(ScreensaverMode.Aerial)

    /**
     * The screensaver in effect. Until one is chosen it follows the wallpaper: aerial films with the
     * aerial wallpaper (and in "Follow App", which has no picture), photos with a picture, starting
     * from that picture.
     */
    val screensaver: ScreensaverMode
        get() = chosenScreensaver ?: when {
            isVideo(selectedId) -> ScreensaverMode.Videos
            selectedId == LIVE || selectedId == AUTO || isSource(selectedId) -> ScreensaverMode.Aerial
            else -> ScreensaverMode.Photos
        }

    /** A wallpaper that moves behind Home (aerial films, the owner's videos), with a frame as its picture. */
    fun moves(id: String = selectedId) = id == LIVE || isVideo(id)

    /** The owner's videos, all in turn or one of them. */
    fun isVideo(id: String = selectedId) = id == VIDEOS || id.startsWith(VIDEO_PREFIX)

    /** The films [id] plays behind Home: all the owner's videos, or the one chosen. Call off the main thread. */
    fun videoFilms(id: String = selectedId): List<ScreensaverVideo> {
        val all = ScreensaverVideos.all(app)
        return if (id.startsWith(VIDEO_PREFIX)) all.filter { it.id == id.removePrefix(VIDEO_PREFIX) } else all
    }

    /** Whether the frame the videos last stopped on is one of [id]'s films. */
    private fun videoFrameIsOf(id: String): Boolean {
        if (!videoFrame.exists()) return false
        if (!id.startsWith(VIDEO_PREFIX)) return true
        return prefs.getString("video_film", null) == id.removePrefix(VIDEO_PREFIX)
    }

    /**
     * Plays as soon as Home shows it and keys do not stop it: the aerial wallpaper always; the
     * owner's videos when set to (issue #360).
     */
    val keepsMoving: Boolean get() = selectedId == LIVE || AerialSettings.wallpaperAlwaysMoves

    fun isSource(id: String = selectedId) = id.startsWith(SOURCE_PREFIX)

    /** The TV input whose picture is the wallpaper, or null. */
    val sourceInput: String? get() = selectedId.takeIf(::isSource)?.removePrefix(SOURCE_PREFIX)

    /** No screensaver chosen: it follows the wallpaper. */
    val screensaverFollows: Boolean get() = chosenScreensaver == null

    /** The home backdrop for the selection, shading baked in; null while loading or in Auto. */
    var backdrop by mutableStateOf<ImageBitmap?>(null); private set

    /**
     * The same picture frosted: blurred small. The glass under the top row, the grid and the
     * panels sample it.
     */
    var frosted by mutableStateOf<ImageBitmap?>(null); private set

    /**
     * Whether the moving wallpaper's frames cannot be had for its glass on this TV: PixelCopy reads
     * the film too slowly to use and its file is not read (TCL MT5879), or the file's frames stuck
     * since the TV started (LiveWallpaper). Then the top row's plate is the smoke alone over the
     * film and panels are one solid colour, rather than the frosted frame of minutes before.
     */
    var filmGlassless by mutableStateOf(false)

    /** Goes up when a moving wallpaper's picture changes, so its card in Personalize is made again. */
    var pictureVersion by mutableIntStateOf(0); private set

    private var backdropJob: Job? = null
    private var started = false

    /** The UI's size in pixels. Pictures are decoded to it: 1080p on most TVs, 4K where the UI is. */
    var screenW = 1920; private set
    var screenH = 1080; private set

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        // Home drawn at the panel's size (UhdCanvas) gets pictures of that size.
        val scale = UhdCanvas.scale(context)
        context.resources.displayMetrics.let {
            screenW = (maxOf(it.widthPixels, it.heightPixels) * scale).roundToInt()
            screenH = (minOf(it.widthPixels, it.heightPixels) * scale).roundToInt()
        }
        selectedId = (prefs.getString("selected", LATEST_DAILY) ?: LATEST_DAILY)
            // A built-in wallpaper from before the Mac-style set: its nearest one now.
            .let { if (it.startsWith("grad:")) "grad:" + Gradients.currentKey(it.removePrefix("grad:")) else it }
        chosenScreensaver = when (val key = prefs.getString("screensaver", null)) {
            null -> firstScreensaver()
            // "wallpaper" was a fourth kind in test builds of 1.38, for what following now is.
            FOLLOW, "wallpaper" -> null
            else -> ScreensaverMode.of(key)
        }
        rebuildList()
        loadBaked()
        rebuildBackdrop()
        scope.launch { refreshDaily() }
        scope.launch(Dispatchers.IO) { clearStaleUploads() }
        // Installs from before 1.42 have a baked picture but no copy yet.
        scope.launch(Dispatchers.IO) { keepForLockedBoot() }
    }

    /**
     * Uploads pass through temp files in the cache folder (ours, CustomIcons', and NanoHTTPD's for
     * each part), deleted when the upload ends. One cut off midway, or the process dying, leaves
     * its file behind, and those piled up (#193). Only those, and only an hour old: the aerial
     * cache beside them keeps itself under 48 MB.
     */
    private fun clearStaleUploads() {
        val before = System.currentTimeMillis() - 60 * 60 * 1000L
        app.cacheDir.listFiles { f ->
            f.isFile && f.lastModified() < before &&
                (f.name.startsWith("upload") || f.name.startsWith("icon") || f.name.startsWith("NanoHTTPD-"))
        }?.forEach { val kb = it.length() / 1024; if (it.delete()) Log.i(TAG, "removed a stale upload, $kb KB") }
    }

    fun select(id: String) {
        selectedId = id
        prefs.edit { putString("selected", id) }
        rebuildBackdrop()
    }

    /**
     * A new install's screensaver follows the wallpaper. One that was already in use when that came
     * (1.38) keeps what it showed, aerial films, until the owner chooses. Kept either way, so it
     * does not change with a later update.
     */
    private fun firstScreensaver(): ScreensaverMode? {
        val fresh = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
        }.getOrDefault(false)
        prefs.edit { putString("screensaver", if (fresh) FOLLOW else ScreensaverMode.Aerial.key) }
        return if (fresh) null else ScreensaverMode.Aerial
    }

    /** The wallpaper's picture, when the photo screensaver starts from it: a picture wallpaper, not the aerial one. */
    fun screensaverPicture(): Wallpaper? =
        if (screensaver != ScreensaverMode.Photos || moves() || isSource() || selectedId == AUTO) null else resolve()

    fun chooseScreensaver(mode: ScreensaverMode) {
        chosenScreensaver = mode
        prefs.edit { putString("screensaver", mode.key) }
        GuardSetup.screensaverModeChanged(app)
    }

    /** The concrete wallpaper the selection points at today. */
    fun resolve(id: String = selectedId): Wallpaper? = when (id) {
        LATEST_DAILY -> items.firstOrNull { it.kind == WallpaperKind.Daily }
        ROTATE_PHOTOS -> items.filter { it.kind == WallpaperKind.Photo }.sortedBy { it.id }.let { photos ->
            if (photos.isEmpty()) resolve(LATEST_DAILY) else photos[Math.floorMod(today(), photos.size)]
        }
        // Inputs are listed after Home starts; until then the input is taken on trust.
        else -> items.firstOrNull { it.id == id }
            ?: if (isSource(id)) Wallpaper(id, WallpaperKind.Source, id.substringAfterLast('/')) else null
    }

    private fun today(): Long = java.time.LocalDate.now().toEpochDay()

    /** The day the rotating photo was last picked for; a new day picks the next one. */
    private var rotatedOn = 0L

    /** Moves "My Photos, rotating" on to today's photo; Aerial calls it whenever Home comes back. */
    fun rotateIfDue() {
        if (!started || selectedId != ROTATE_PHOTOS || rotatedOn == today()) return
        rebuildBackdrop()
    }

    // ---- pictures ----

    private fun fileFor(w: Wallpaper): File? = when (w.kind) {
        WallpaperKind.Daily -> File(dailyDir, w.id.removePrefix("daily:") + ".jpg")
        WallpaperKind.Photo -> File(photoDir, w.id.removePrefix("photo:") + ".jpg")
        else -> null
    }

    /** Full picture at [w]×[h] without shading, for the photo screensaver. Call off the main thread. */
    fun picture(wp: Wallpaper, w: Int, h: Int): Bitmap? = when (wp.kind) {
        WallpaperKind.Builtin -> Gradients.byKey(wp.id.removePrefix("grad:"))?.let { Gradients.render(it, w, h) }
        WallpaperKind.Daily, WallpaperKind.Photo -> fileFor(wp)?.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
        WallpaperKind.Auto -> null
        // Until a film has stopped on a frame, the first built-in stands in.
        WallpaperKind.Live -> liveFrame.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
            ?: Gradients.render(Gradients.presets.first(), w, h)
        // Until one of its films has stopped on a frame, the first film's opening frame.
        WallpaperKind.Video -> videoFrame.takeIf { videoFrameIsOf(wp.id) }?.let { Images.decodeCover(it, w, h) }
            ?: firstVideoFrame(wp.id, w, h) ?: Gradients.render(Gradients.presets.first(), w, h)
        WallpaperKind.Source -> Gradients.byKey(SOURCE_PICTURE)?.let { Gradients.render(it, w, h) }
    }

    private fun firstVideoFrame(id: String, w: Int, h: Int): Bitmap? {
        val film = videoFilms(id).firstOrNull() ?: return null
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(film.file.path)
            mmr.getFrameAtTime(0)?.let { Images.cover(it, w, h) }
        } catch (_: Exception) { null } finally { runCatching { mmr.release() } }
    }

    /** Drops the thumbnails (about 330 KB each); the picker makes them again when it opens. */
    fun trimMemory() = synchronized(thumbs) { thumbs.clear() }

    /** A 16:9 thumbnail for pickers and the phone page. Call off the main thread. */
    fun thumbnail(id: String): Bitmap? {
        synchronized(thumbs) { thumbs[id] }?.let { return it }
        // A frame kept meanwhile (useLiveFrame) makes this one old: shown, not kept.
        val version = pictureVersion
        val wp = (if (id == LATEST_DAILY || id == ROTATE_PHOTOS) resolve(id) else items.firstOrNull { it.id == id }) ?: return null
        val b = if (wp.kind == WallpaperKind.Auto) Gradients.render(Gradients.autoThumb, 384, 216) else picture(wp, 384, 216)
        if (b != null) synchronized(thumbs) { if (version == pictureVersion) thumbs[id] = b }
        return b
    }

    private fun rebuildBackdrop() {
        backdropJob?.cancel()
        val id = selectedId
        backdropJob = scope.launch {
            if (id == AUTO) {
                backdrop = null
                frosted = null
                return@launch
            }
            rotatedOn = today()
            val wp = resolve(id) ?: resolve(DEFAULT_FALLBACK)
            val key = wp?.let(::bakedKey)
            if (key != null && key == bakedKey && backdrop != null) return@launch
            val (bmp, frost) = withContext(Dispatchers.Default) {
                val picture = wp?.let { picture(it, screenW, screenH) }
                // Frosted from the picture before the home shading: the glass sits low on the
                // screen, where the shading is darkest, and would read as a grey slab.
                val frost = picture?.let(Images::frost)
                // A film's picture is its frame as it was, as the film is not shaded (HomeBackground).
                if (wp?.kind == WallpaperKind.Live || wp?.kind == WallpaperKind.Video) picture to frost
                else picture?.let(Images::shadeForHome) to frost
            }
            backdrop = bmp?.asImageBitmap()
            frosted = frost?.asImageBitmap()
            if (key != null && bmp != null && frost != null) saveBaked(key, bmp, frost)
        }
    }

    // ---- the baked backdrop, kept for the next start ----

    /**
     * What the backdrop on disk was baked from; null when there is none. Baking takes a cold start
     * about two seconds on Xiaomi MiTV-MFTR0 (decoding, or drawing a built-in picture pixel by pixel,
     * then shading and frosting), and Home showed black, then the focused app's light, meanwhile
     * (issues #277, #294). Reading the baked pair back takes tens of milliseconds.
     */
    private var bakedKey: String? = null

    /** Changes with the picture behind [wp], the screen size, and Aerial's version (the baking may change). */
    private fun bakedKey(wp: Wallpaper): String {
        val stamp = when (wp.kind) {
            WallpaperKind.Daily, WallpaperKind.Photo -> fileFor(wp)?.lastModified() ?: 0L
            WallpaperKind.Live -> liveFrame.lastModified()
            WallpaperKind.Video -> if (videoFrameIsOf(wp.id)) videoFrame.lastModified() else 0L
            else -> 0L
        }
        // "clear": a film's picture baked unshaded, not one kept from when it was shaded.
        val film = if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) "|clear" else ""
        return "${wp.id}|$stamp|${screenW}x$screenH|${BuildConfig.VERSION_CODE}$film"
    }

    /**
     * Puts the last baked backdrop up at once, on the main thread, so Home's first frame has it. Even
     * one that is out of date (a new day's photo): it is the picture Home last showed, and the fresh
     * one crossfades in once baked. Not in "Follow App", which has no backdrop.
     */
    private fun loadBaked() {
        if (selectedId == AUTO) return
        runCatching {
            val key = File(bakedDir, "key").takeIf { it.exists() }?.readText() ?: return
            val home = BitmapFactory.decodeFile(File(bakedDir, "home.jpg").path) ?: return
            val frost = BitmapFactory.decodeFile(File(bakedDir, "frost.png").path) ?: return
            if (home.width != screenW || home.height != screenH) return
            backdrop = home.asImageBitmap()
            frosted = frost.asImageBitmap()
            bakedKey = key
        }.onFailure { Log.w(TAG, "could not read the baked backdrop", it) }
    }

    private suspend fun saveBaked(key: String, home: Bitmap, frost: Bitmap) {
        bakedKey = key
        withContext(Dispatchers.IO) {
            runCatching {
                // The key last, so a write cut short is never taken for a good one.
                File(bakedDir, "key").delete()
                File(bakedDir, "home.jpg").outputStream().use { home.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                File(bakedDir, "frost.png").outputStream().use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(bakedDir, "key").writeText(key)
            }.onFailure { Log.w(TAG, "could not keep the baked backdrop", it) }
            keepForLockedBoot()
        }
    }

    /**
     * Before the user is unlocked at power-on, Home can read only device-protected storage, so the
     * baked picture is copied there and MainActivity shows it until the rest can be read. Only
     * that picture: settings, the parental PIN, photos and icons stay in credential storage. Not
     * in "Follow App", which has no picture of its own.
     */
    fun lockedBootBackdrop(context: Context): File? =
        if (Build.VERSION.SDK_INT >= 24) File(context.createDeviceProtectedStorageContext().filesDir, "boot-backdrop.jpg") else null

    private fun keepForLockedBoot() {
        val copy = lockedBootBackdrop(app) ?: return
        runCatching {
            val home = File(bakedDir, "home.jpg")
            if (selectedId == AUTO || !home.exists()) { copy.delete(); return }
            if (copy.exists() && copy.lastModified() >= home.lastModified()) return
            val part = File(copy.parentFile, copy.name + ".part")
            home.copyTo(part, overwrite = true)
            check(part.renameTo(copy))
        }.onFailure { Log.w(TAG, "could not keep the backdrop for a locked boot", it) }
    }

    /**
     * The frame a moving wallpaper film stopped on becomes the wallpaper's picture: frosted
     * and kept for the next start. Returns once Home has it. [frame] is screen-sized and is used up.
     * It was [atMs] into [film], where the film picks up when it next moves ([liveSpot]).
     */
    /**
     * [frame] shows as the picture at once, before [useLiveFrame] has kept and frosted it: the
     * aerial film let go into an app shows its last frame rather than black (LiveWallpaper).
     */
    fun showLiveFrame(frame: Bitmap) {
        if (!moves()) return
        backdropJob?.cancel()
        backdrop = frame.asImageBitmap()
    }

    suspend fun useLiveFrame(frame: Bitmap, film: String, atMs: Long) {
        if (!moves()) return
        val id = selectedId
        backdropJob?.cancel()
        val key = if (isVideo(id)) "video" else "live"
        if (!isVideo(id)) leftHere = null
        prefs.edit { putString("${key}_film", film).putLong("${key}_at", atMs).remove("${key}_left_film").remove("${key}_left_at") }
        val (bmp, frost) = withContext(Dispatchers.Default) {
            runCatching { (if (isVideo(id)) videoFrame else liveFrame).outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                .onFailure { Log.w(TAG, "could not keep the film's frame", it) }
            // Not shaded, as the film is not (HomeBackground).
            Images.frost(frame).let { frost -> frame to frost }
        }
        // The frame is both the film's own card and the card that plays them all in turn.
        synchronized(thumbs) { thumbs.remove(id); if (isVideo(id)) { thumbs.remove(VIDEOS); thumbs.remove(VIDEO_PREFIX + film) } }
        backdrop = bmp.asImageBitmap()
        frosted = frost.asImageBitmap()
        pictureVersion++
        resolve(id)?.let { saveBaked(bakedKey(it), bmp, frost) }
    }

    /**
     * Where the aerial film goes on from: where it was let go without a frame taken ([leftLiveSpot]),
     * else the moment the wallpaper's picture was taken from, so the film starts again on the
     * picture on screen rather than cutting to another place. Null until one has stopped on a frame.
     */
    fun liveSpot(): Pair<String, Long>? = leftLiveSpot() ?: livePictureSpot()

    /** The film and moment the wallpaper's picture was taken from. */
    fun livePictureSpot(): Pair<String, Long>? {
        val film = prefs.getString("live_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("live_at", 0L)
    }

    /**
     * Where the aerial film was let go with no frame taken, past the picture: Home went behind an
     * app, or a screensaver of aerials played on from the wallpaper's film. Without it the film
     * started again from the same picture every time, and the wallpaper never changed (Weibo
     * report, 2026-10-28). Null once the picture has caught up ([useLiveFrame]).
     */
    fun leftLiveSpot(): Pair<String, Long>? {
        leftHere?.let { return it }
        val film = prefs.getString("live_left_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("live_left_at", 0L)
    }

    /**
     * The newest left spot, ahead of prefs: the screensaver moves it on every second, so Home comes
     * back on its film and moment, and writes it to prefs only now and then ([leaveLiveFilm]).
     */
    @Volatile
    private var leftHere: Pair<String, Long>? = null

    /**
     * The aerial film was let go at [atMs] into [film] with no frame taken: it goes on from there.
     * Not [persist]ed: kept in memory only, for a screensaver that reports where it is every second.
     */
    fun leaveLiveFilm(film: String, atMs: Long, persist: Boolean = true) {
        // Kept whatever the wallpaper: a screensaver of aerials over another one leaves its film
        // here too, so the aerial wallpaper chosen later begins on it (AerialSet).
        if (!persist) { leftHere = film to atMs; return }
        val picture = livePictureSpot()
        // Let go where the picture is (stopped there, or never moved): nothing to catch up.
        if (picture != null && picture.first == film && abs(picture.second - atMs) < SAME_SPOT_MS) {
            leftHere = null
            prefs.edit { remove("live_left_film").remove("live_left_at") }
        } else {
            leftHere = film to atMs
            prefs.edit { putString("live_left_film", film).putLong("live_left_at", atMs) }
        }
    }

    /** The same for the owner's videos: which one and where it last stopped. */
    fun videoSpot(): Pair<String, Long>? {
        val film = prefs.getString("video_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("video_at", 0L)
    }

    /** The owner's videos changed (sent or deleted from the phone): their cards come or go. */
    fun videosChanged() {
        synchronized(thumbs) { thumbs.keys.removeAll { isVideo(it) } }
        rebuildList()
        // The film the frame was taken from gone: so is the frame.
        val spot = prefs.getString("video_film", null)
        if (spot != null && items.none { it.id == VIDEO_PREFIX + spot }) {
            videoFrame.delete()
            prefs.edit { remove("video_film").remove("video_at") }
        }
        // The chosen one deleted: the others in turn, if any are left.
        if (isVideo() && items.none { it.id == selectedId }) select(if (items.any { it.id == VIDEOS }) VIDEOS else LATEST_DAILY)
        else if (isVideo()) rebuildBackdrop()
    }

    /** The TV inputs Home lists that can play behind it, as (TvInputInfo id, name). */
    fun setSources(list: List<Pair<String, String>>) {
        if (list == sources) return
        sources = list
        rebuildList()
    }

    /**
     * The glass for a moving wallpaper film that has just stopped, from a small frame near it: the
     * plate can stand over the paused film at once, before the exact frame is decoded for
     * [useLiveFrame]. [frame] is used up.
     */
    suspend fun useLiveFrost(frame: Bitmap) {
        if (selectedId != LIVE) return
        backdropJob?.cancel()
        // The frame the plate's glass was made from while the film moved: that glass, at once.
        frosted = filmFrost?.takeIf { frame === filmFrostOf }
            ?: withContext(Dispatchers.Default) { Images.frost(frame) }.asImageBitmap()
    }

    /**
     * The glass for a moving film: a small frame of it taken every few seconds while it plays
     * (LiveWallpaper, VideoWallpaper), frosted. Null until the first, when [frosted] (the frame
     * the film started from) stands in.
     */
    var filmFrost by mutableStateOf<ImageBitmap?>(null); private set
    private var filmFrostOf: Bitmap? = null

    suspend fun useFilmFrost(frame: Bitmap) {
        if (!moves()) return
        filmFrost = withContext(Dispatchers.Default) { Images.frost(frame) }.asImageBitmap()
        filmFrostOf = frame
    }

    /**
     * Where no frame can be taken while the film moves (LiveWallpaper.sampleGlass): the glass of the
     * frame it started from ([frosted]), blurred down to its broad colours, so the shapes that move
     * on behind the plate do not show twice through it.
     */
    suspend fun useStillFilmFrost() {
        val start = frosted ?: return
        if (!moves()) return
        filmFrostOf = null
        filmFrost = withContext(Dispatchers.Default) {
            val src = start.asAndroidBitmap()
            val quarter = Bitmap.createScaledBitmap(src, src.width / 4, src.height / 4, true)
            Images.frost(Bitmap.createScaledBitmap(quarter, 16, 9, true))
        }.asImageBitmap()
    }

    fun dropFilmFrost() {
        filmFrost = null
        filmFrostOf = null
    }

    private val clipGlassDir by lazy { File(app.filesDir, "clip-glass").apply { mkdirs() } }
    private val clipGlassMade = mutableSetOf<String>()

    /**
     * The grid's glass while the aerial wallpaper shows [clip][showClipGlass]: one for each film,
     * made once from the first frame taken of it ([keepClipGlass]) and kept on disk, so the grid
     * comes in on the same glass wherever the film stopped, and a film met again has it at once
     * (issue #727: a glass made where the film stopped came in seconds late, or changed in sight).
     */
    var clipGlass by mutableStateOf<Pair<String, ImageBitmap>?>(null); private set

    /** Shows [clip]'s glass if it has one: from memory, else from disk. */
    suspend fun showClipGlass(clip: String?) {
        if (clip == null || clipGlass?.first == clip) return
        val file = File(clipGlassDir, "$clip.png")
        val bmp = withContext(Dispatchers.IO) { if (file.exists()) BitmapFactory.decodeFile(file.path) else null }
        if (bmp != null) clipGlass = clip to bmp.asImageBitmap()
    }

    /** [frame], a small frame of [clip], becomes its glass unless it has one already. */
    suspend fun keepClipGlass(clip: String, frame: Bitmap, showing: () -> String?) {
        if (clip in clipGlassMade) return
        clipGlassMade += clip
        val file = File(clipGlassDir, "$clip.png")
        val bmp = withContext(Dispatchers.IO) {
            if (file.exists()) return@withContext BitmapFactory.decodeFile(file.path)
            val started = System.nanoTime()
            val frost = Images.frost(frame)
            DiagLog.i("clip glass made in ${(System.nanoTime() - started) / 1_000_000} ms")
            runCatching { file.outputStream().use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                .onFailure { Log.w(TAG, "could not keep the clip's glass", it) }
            frost
        } ?: return
        if (showing() == clip) clipGlass = clip to bmp.asImageBitmap()
    }

    /** Puts back the glass from before [useLiveFrost] when the exact frame could not be had. */
    fun restoreFrost(before: ImageBitmap?) {
        if (selectedId == LIVE && before != null) frosted = before
    }

    private fun rebuildList() {
        val daily = readDailyIndex().map { Wallpaper("daily:${it.first}", WallpaperKind.Daily, it.second, it.third) }
        val zh = Locale.getDefault().language == "zh"
        val builtins = Gradients.presets.map { Wallpaper("grad:${it.key}", WallpaperKind.Builtin, if (zh) it.nameZh else it.nameEn) }
        val photos = (photoDir.listFiles { f -> f.extension == "jpg" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { Wallpaper("photo:${it.nameWithoutExtension}", WallpaperKind.Photo, if (zh) "我的照片" else "My Photo") }
        val auto = Wallpaper(AUTO, WallpaperKind.Auto, if (zh) "跟随 App" else "Follow App")
        val live = Wallpaper(LIVE, WallpaperKind.Live, if (zh) "航拍" else "Aerial")
        // All of them in turn, then each on its own (#762); the pickers show the first only for two or more.
        val films = ScreensaverVideos.all(app)
        val videos = if (films.isEmpty()) emptyList() else
            listOf(Wallpaper(VIDEOS, WallpaperKind.Video, if (zh) "我的视频 · 轮播" else "My Videos, In Turn")) +
                films.map { Wallpaper(VIDEO_PREFIX + it.id, WallpaperKind.Video, it.name.substringBeforeLast('.').ifBlank { it.name }) }
        val inputs = sources.map { (id, name) -> Wallpaper(SOURCE_PREFIX + id, WallpaperKind.Source, name) }
        items = listOf(live) + videos + daily + builtins + photos + auto + inputs
    }

    // ---- daily photos ----

    private fun readDailyIndex(): List<Triple<String, String, String>> = runCatching {
        val arr = JSONArray(File(dailyDir, "index.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("date"), o.getString("title"), o.getString("caption"))
        }.filter { File(dailyDir, it.first + ".jpg").exists() }
    }.getOrDefault(emptyList())

    private var lastDailyRefresh = 0L
    private var dailyFailed = false

    /**
     * Bing publishes one photo a day, at midnight in China; checking a few times a day is plenty,
     * plus once the day has turned (the date-change broadcast, or the first look after it), retried
     * every half hour until the new photo is out: a TV left on Home overnight kept yesterday's
     * photo for up to six hours (issue #286). A check that failed, usually because the TV was on
     * before its network was, is retried as soon as the network connects ([networkCameUp]), or
     * else a few minutes later (issue #129).
     */
    fun refreshDailyIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastDailyRefresh
        val due = since >= 6 * 60 * 60 * 1000L ||
            (dailyFailed && (networkCameUp || since >= 10 * 60 * 1000L)) ||
            (!hasTodaysDaily() && since >= DAILY_RETRY_MS)
        if (due) scope.launch { refreshDaily() }
    }

    /** Bing's dates are China's, as yyyyMMdd. */
    private fun hasTodaysDaily(): Boolean {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        return readDailyIndex().any { it.first >= today }
    }

    private var dailyRetry: Job? = null

    /** Looks again in half an hour while today's photo is not out; Home may stay up all night. */
    private fun retryDailyLater() {
        dailyRetry?.cancel()
        if (hasTodaysDaily()) return
        dailyRetry = scope.launch {
            kotlinx.coroutines.delay(DAILY_RETRY_MS)
            refreshDailyIfStale()
        }
    }

    suspend fun refreshDaily() = io.withLock {
        lastDailyRefresh = System.currentTimeMillis()
        val added = withContext(Dispatchers.IO) {
            runCatching {
                // The market sets the language of the captions; Bing has no Russian or Ukrainian ones
                // (it answers those markets in English), so everything but Chinese asks for English.
                val market = if (Locale.getDefault().language == "zh") "zh-CN" else "en-US"
                // Each market has its own photo for a given date, and the files are named by date.
                val marketFile = File(dailyDir, "market")
                // No file: kept before markets were told apart, which was always zh-CN.
                val before = marketFile.takeIf { it.exists() }?.readText() ?: "zh-CN"
                val json = JSONObject(get("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=$market").decodeToString())
                // Only once the new market's list is here: offline, the photos on hand stay.
                if (before != market) dailyDir.listFiles { f -> f.extension == "jpg" }?.forEach { it.delete() }
                if (before != market || !marketFile.exists()) marketFile.writeText(market)
                val images = json.getJSONArray("images")
                val index = JSONArray()
                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    val date = img.getString("startdate")
                    val file = File(dailyDir, "$date.jpg")
                    if (!file.exists()) {
                        // url is the 1920×1080 picture; a UI drawn above 1080p gets the UHD one.
                        val path = if (screenW > 1920) img.getString("urlbase") + "_UHD.jpg" else img.getString("url")
                        val bytes = get("https://cn.bing.com" + path)
                        file.writeBytes(bytes)
                    }
                    val copyright = img.optString("copyright")
                    val title = img.optString("title").ifBlank { copyright.substringBefore(" (").ifBlank { date } }
                    index.put(JSONObject().put("date", date).put("title", title).put("caption", copyright))
                }
                File(dailyDir, "index.json").writeText(index.toString())
                // Keep only what Bing still lists.
                val keep = (0 until index.length()).map { index.getJSONObject(it).getString("date") + ".jpg" }.toSet()
                dailyDir.listFiles { f -> f.extension == "jpg" && f.name !in keep }?.forEach { it.delete() }
                true
            }.onFailure { Log.w(TAG, "daily photos unavailable", it) }.isSuccess.also { dailyFailed = !it }
        }
        retryDailyLater()
        if (added) {
            synchronized(thumbs) { thumbs.remove(LATEST_DAILY) }
            rebuildList()
            if (selectedId.startsWith("daily:")) rebuildBackdrop()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        return c.inputStream.use { it.readBytes() }.also { c.disconnect() }
    }

    // ---- uploads ----

    /** Stores an uploaded picture, downscaled to at most 2560 px wide, and returns its id. */
    suspend fun addPhoto(input: InputStream): String? = io.withLock {
        val id = withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("upload", ".bin", app.cacheDir)
            try {
                tmp.outputStream().use { input.copyTo(it) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.path, bounds)
                if (bounds.outWidth <= 0) return@withContext null
                // Stored upright: the JPEG written below keeps no EXIF, so the turn is applied now.
                val orientation = Images.exifOrientation(tmp)
                val wide = if (Images.quarterTurn(orientation)) bounds.outHeight else bounds.outWidth
                var sample = 1
                while (wide / (sample * 2) >= 2560) sample *= 2
                val decoded = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
                val bmp = Images.upright(decoded, orientation)
                val name = System.currentTimeMillis().toString(36)
                File(photoDir, "$name.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                "photo:$name"
            } finally {
                tmp.delete()
            }
        }
        if (id != null) {
            synchronized(thumbs) { thumbs.remove(ROTATE_PHOTOS) }
            rebuildList()
            select(id)
        }
        id
    }

    fun deletePhoto(id: String) {
        // Only photos that are listed: the id comes from the phone page, and "photo:../daily/..."
        // would otherwise reach other files (#57).
        val photo = items.firstOrNull { it.id == id && it.kind == WallpaperKind.Photo } ?: return
        fileFor(photo)?.delete()
        synchronized(thumbs) { thumbs.remove(id); thumbs.remove(ROTATE_PHOTOS) }
        rebuildList()
        if (selectedId == id) select(LATEST_DAILY)
        else if (selectedId == ROTATE_PHOTOS) rebuildBackdrop()
    }
}
