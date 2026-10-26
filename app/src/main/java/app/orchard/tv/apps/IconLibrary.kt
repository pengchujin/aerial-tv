package app.orchard.tv.apps

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.orchard.tv.settings.AerialSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ready-made 16:9 tile pictures for popular apps, from the website (issues #575 #429 #307: tiles
 * drawn from an app's own icon read rougher than other launchers' hand-made ones). The site keeps
 * an index of package → picture; Aerial reads it at most twice a day and downloads only the
 * pictures of apps installed on this TV, into files/library. A picture the owner chose on the
 * phone page (CustomIcons) still wins. Personalize turns the library on or off for every app; the
 * phone page can pick the library's picture or the app's own art for one app, whatever that says.
 *
 * Only this object writes files/library, and it removes a file only when the index names a newer
 * picture for that app or drops the app.
 */
object IconLibrary {
    private const val TAG = "AerialIcons"
    private const val BASE = "https://aerial.qust.me/icons/"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L

    /** Bumped when pictures arrive or the library is turned on or off, so Home redraws its tiles. */
    var version by mutableIntStateOf(0); private set

    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences

    /** Package → path on the site ("p/<package>-<hash>.jpg"), from the last index read. */
    @Volatile private var index: Map<String, String> = emptyMap()
    private val refreshing = Mutex()
    @Volatile private var lastAttempt = 0L
    /** Pictures that failed to download, and when: not tried again on every reload. */
    private val failedAt = HashMap<String, Long>()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "library").apply { mkdirs() }
        prefs = context.applicationContext.getSharedPreferences("icon_library", Context.MODE_PRIVATE)
        index = parse(File(dir, "index.json").takeIf { it.exists() }?.readText())
    }

    private fun fileFor(pkg: String): File? {
        val path = index[pkg] ?: return null
        return File(dir, path.substringAfterLast('/'))
    }

    /** The library's picture of [pkg] is on this TV (whether or not its tile uses it). */
    fun has(pkg: String): Boolean = ::dir.isInitialized && fileFor(pkg)?.exists() == true

    /** The library's picture of [pkg] as stored, for the phone page; null without one. */
    fun file(pkg: String): File? = if (::dir.isInitialized) fileFor(pkg)?.takeIf { it.exists() } else null

    /** Whether [pkg]'s tile uses the library: its own pick on the phone page, else Personalize's. */
    fun uses(pkg: String): Boolean = when (pick(pkg)) {
        Pick.Library -> true
        Pick.Own -> false
        null -> AerialSettings.iconLibrary
    }

    private fun usable(pkg: String): File? = if (uses(pkg)) file(pkg) else null

    enum class Pick { Library, Own }

    /** What the phone page picked for [pkg]; null to follow Personalize. */
    fun pick(pkg: String): Pick? = when {
        !::prefs.isInitialized -> null
        pkg in prefs.getStringSet("use_library", emptySet())!! -> Pick.Library
        pkg in prefs.getStringSet("use_own", emptySet())!! -> Pick.Own
        else -> null
    }

    /** From the phone page; the caller calls [changed] on the main thread after. */
    fun choose(pkg: String, pick: Pick?) {
        val library = prefs.getStringSet("use_library", emptySet())!! - pkg
        val own = prefs.getStringSet("use_own", emptySet())!! - pkg
        prefs.edit {
            putStringSet("use_library", if (pick == Pick.Library) library + pkg else library)
            putStringSet("use_own", if (pick == Pick.Own) own + pkg else own)
        }
    }

    /** When the app's picture arrived; 0 without one. Part of the tile cache's key. */
    fun stamp(pkg: String): Long = usable(pkg)?.lastModified() ?: 0L

    /** The app's picture, or null. Off the main thread. */
    fun load(pkg: String): Bitmap? = usable(pkg)?.let { BitmapFactory.decodeFile(it.path) }

    /** Called on the main thread when Personalize turns the library on or off, or [refresh] brought something new. */
    fun changed() {
        version++
    }

    /**
     * Reads the index when it is due and fetches the pictures of [installed] apps that are not
     * here yet. True when a tile's picture changed. Off the main thread; a failure is only logged
     * and tried again on a later reload. Fetched with the library off too: the phone page shows
     * them, and can pick one for a single app.
     */
    suspend fun refresh(installed: Set<String>): Boolean = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext false
        if (!refreshing.tryLock()) return@withContext false
        try {
            val now = System.currentTimeMillis()
            var changed = false
            if (now - prefs.getLong("checked_at", 0) > CHECK_EVERY_MS && now - lastAttempt > RETRY_MS) {
                lastAttempt = now
                runCatching { read(BASE + "index.json") }
                    .onFailure { Log.w(TAG, "could not read the icon index", it) }
                    .getOrNull()
                    ?.let { text ->
                        val fresh = parse(text)
                        if (fresh.isNotEmpty() || text.contains("\"icons\"")) {
                            File(dir, "index.json").writeText(text)
                            changed = fresh != index
                            index = fresh
                            prefs.edit { putLong("checked_at", now) }
                        }
                    }
            }
            val keep = HashSet<String>()
            for (pkg in installed) {
                val path = index[pkg] ?: continue
                val file = fileFor(pkg) ?: continue
                keep += file.name
                if (file.exists() || now - (failedAt[pkg] ?: 0L) < RETRY_MS) continue
                runCatching { download(BASE + path, file) }
                    .onSuccess { changed = true; failedAt -= pkg }
                    .onFailure { failedAt[pkg] = now; Log.w(TAG, "could not fetch the picture of $pkg", it) }
            }
            // Pictures replaced by newer ones, or of apps no longer installed.
            dir.listFiles()?.forEach { if (it.name != "index.json" && it.name !in keep) it.delete() }
            changed
        } finally {
            refreshing.unlock()
        }
    }

    private fun parse(text: String?): Map<String, String> = runCatching {
        val icons = JSONObject(text ?: return emptyMap()).getJSONObject("icons")
        buildMap { icons.keys().forEach { pkg -> if (CustomIcons.isValidKey(pkg)) put(pkg, icons.getString(pkg)) } }
    }.getOrDefault(emptyMap())

    private fun connect(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 15_000
    }

    private fun read(url: String): String {
        val c = connect(url)
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    /** Into a temporary file first, so a broken download never stands as the picture. */
    private fun download(url: String, to: File) {
        val tmp = File(dir, to.name + ".part")
        val c = connect(url)
        try {
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        val ok = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(tmp.path, it) }.outWidth > 0
        if (!ok || !tmp.renameTo(to)) {
            tmp.delete()
            error("not a picture")
        }
    }
}
