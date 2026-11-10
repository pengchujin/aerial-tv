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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ready-made 16:9 tile pictures for popular apps, from the website (issues #575 #429 #307: tiles
 * drawn from an app's own icon read rougher than other launchers' hand-made ones). The site keeps
 * an index of package → pictures; Aerial reads it at most twice a day and downloads only the
 * pictures of apps installed on this TV, into files/library. A picture the owner chose on the
 * phone page (CustomIcons) still wins. Every app with a picture here uses it, unless the phone page
 * picked the app's own art for it. (Personalize had a switch for all apps at once until 1.55; with
 * most apps picked one by one it seemed to do nothing, and it was dropped.)
 *
 * An app can have several pictures (index v2, #706), each credited to where it came from (a
 * contributor, the app's tvOS version, Aerial itself). The first is the default; the phone page
 * lists them all and can pick another. Index v1 had one picture per app, which v2 still carries
 * in "icons" for older versions of Aerial.
 *
 * Only this object writes files/library, and it removes a file only when the index names a newer
 * picture or drops it, or the app is gone.
 */
object IconLibrary {
    private const val TAG = "AerialIcons"
    private const val BASE = "https://aerial.qust.me/icons/"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L

    /** Bumped when pictures arrive or a pick changes, so Home redraws its tiles. */
    var version by mutableIntStateOf(0); private set

    /**
     * One picture of an app: [id] stays the same when the picture is redrawn (the phone page's
     * pick is kept by it), [path] is where it is on the site ("p/<package>-<hash>.png"), [by] the
     * key of its source in [credits].
     */
    class Choice(val id: String, val path: String, val by: String) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences

    /** Package → its pictures, the default first, from the last index read. */
    @Volatile private var index: Map<String, List<Choice>> = emptyMap()
    /** Source key → {"name": …, "<language>": …}, from the last index read. */
    @Volatile var credits: JSONObject = JSONObject(); private set
    private val refreshing = Mutex()
    @Volatile private var lastAttempt = 0L
    /** Pictures that failed to download (by file name), and when: not tried on every reload. */
    private val failedAt = HashMap<String, Long>()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "library").apply { mkdirs() }
        prefs = context.applicationContext.getSharedPreferences("icon_library", Context.MODE_PRIVATE)
        read(File(dir, "index.json").takeIf { it.exists() }?.readText())?.let { (i, c) -> index = i; credits = c }
    }

    private fun here(c: Choice): File? = File(dir, c.fileName).takeIf { it.exists() }

    /** The pictures of [pkg] that are on this TV, the default first. */
    fun choices(pkg: String): List<Choice> =
        if (::dir.isInitialized) index[pkg].orEmpty().filter { here(it) != null } else emptyList()

    /** The picture [pkg]'s tile uses from the library: the phone page's pick, else the default. */
    fun chosen(pkg: String): Choice? {
        val here = choices(pkg)
        val id = if (::prefs.isInitialized) prefs.getString("choice:$pkg", null) else null
        return here.firstOrNull { it.id == id } ?: here.firstOrNull()
    }

    /** The library has a picture of [pkg] on this TV (whether or not its tile uses it). */
    fun has(pkg: String): Boolean = chosen(pkg) != null

    /** The library's picture of [pkg] as stored, for the phone page: [id]'s, or the chosen one. */
    fun file(pkg: String, id: String? = null): File? {
        val c = if (id == null) chosen(pkg) else choices(pkg).firstOrNull { it.id == id }
        return c?.let(::here)
    }

    /** Whether [pkg]'s tile uses the library: unless the phone page picked the app's own art. */
    fun uses(pkg: String): Boolean = when (pick(pkg)) {
        Pick.Library -> true
        Pick.Own -> false
        null -> true
    }

    private fun usable(pkg: String): File? = if (uses(pkg)) file(pkg) else null

    enum class Pick { Library, Own }

    /** What the phone page picked for [pkg]; null for the library. */
    fun pick(pkg: String): Pick? = when {
        !::prefs.isInitialized -> null
        pkg in prefs.getStringSet("use_library", emptySet())!! -> Pick.Library
        pkg in prefs.getStringSet("use_own", emptySet())!! -> Pick.Own
        else -> null
    }

    /**
     * From the phone page; the caller calls [changed] on the main thread after. [choice] is the
     * library picture picked with [Pick.Library] (null: the default).
     */
    fun choose(pkg: String, pick: Pick?, choice: String? = null) {
        val library = prefs.getStringSet("use_library", emptySet())!! - pkg
        val own = prefs.getStringSet("use_own", emptySet())!! - pkg
        val default = index[pkg]?.firstOrNull()?.id
        prefs.edit {
            putStringSet("use_library", if (pick == Pick.Library) library + pkg else library)
            putStringSet("use_own", if (pick == Pick.Own) own + pkg else own)
            if (pick == Pick.Own) return@edit
            if (choice == null || choice == default) remove("choice:$pkg") else putString("choice:$pkg", choice)
        }
    }

    /**
     * Changes when the tile's picture or the pick does (a firmware app drawn as Aerial's own tile
     * takes the same picture only once picked). Part of the tile cache's key.
     */
    fun stamp(pkg: String): Long = (usable(pkg)?.let { it.lastModified() + it.name.hashCode() } ?: 0L) +
        when (pick(pkg)) { Pick.Own -> 1L; Pick.Library -> 2L; null -> 0L }

    /** The app's picture, or null. Off the main thread. */
    fun load(pkg: String): Bitmap? = usable(pkg)?.let { BitmapFactory.decodeFile(it.path) }

    /** Called on the main thread when a pick changed or [refresh] brought something new. */
    fun changed() {
        version++
    }

    /**
     * Reads the index when it is due and fetches the pictures of [installed] apps that are not
     * here yet (every one of an app's pictures, so the phone page can show them). True when a
     * tile's picture may have changed. Off the main thread; a failure is only logged and tried
     * again on a later reload.
     */
    suspend fun refresh(installed: Set<String>): Boolean = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext false
        if (!refreshing.tryLock()) return@withContext false
        try {
            val now = System.currentTimeMillis()
            var changed = false
            if (now - prefs.getLong("checked_at", 0) > CHECK_EVERY_MS && now - lastAttempt > RETRY_MS) {
                lastAttempt = now
                runCatching { fetch(BASE + "index.json") }
                    .onFailure { Log.w(TAG, "could not read the icon index", it) }
                    .getOrNull()
                    ?.let { text ->
                        read(text)?.let { (fresh, c) ->
                            File(dir, "index.json").writeText(text)
                            changed = fresh.mapValues { e -> e.value.map { it.path } } != index.mapValues { e -> e.value.map { it.path } }
                            index = fresh
                            credits = c
                            prefs.edit { putLong("checked_at", now) }
                        }
                    }
            }
            val keep = HashSet<String>()
            for (pkg in installed) {
                for (c in index[pkg].orEmpty()) {
                    val file = File(dir, c.fileName)
                    keep += file.name
                    if (file.exists() || now - (failedAt[file.name] ?: 0L) < RETRY_MS) continue
                    runCatching { download(BASE + c.path, file) }
                        .onSuccess { changed = true; failedAt -= file.name }
                        .onFailure { failedAt[file.name] = now; Log.w(TAG, "could not fetch ${c.path}", it) }
                }
            }
            // Pictures replaced by newer ones, or of apps no longer installed.
            dir.listFiles()?.forEach { if (it.name != "index.json" && it.name !in keep) it.delete() }
            changed
        } finally {
            refreshing.unlock()
        }
    }

    /** The index's pictures and credits; null when [text] is not an index. */
    @androidx.annotation.VisibleForTesting
    internal fun read(text: String?): Pair<Map<String, List<Choice>>, JSONObject>? = runCatching {
        val root = JSONObject(text ?: return null)
        val sets = root.optJSONObject("choices")
        val icons = root.getJSONObject("icons")
        val map = buildMap {
            if (sets != null) {
                sets.keys().forEach { pkg ->
                    if (!CustomIcons.isValidKey(pkg)) return@forEach
                    val list = sets.getJSONArray(pkg)
                    val choices = (0 until list.length()).mapNotNull { i ->
                        val o = list.getJSONObject(i)
                        val path = o.optString("p")
                        Choice(o.optString("id"), path, o.optString("by")).takeIf { safe(path) && it.id.isNotEmpty() }
                    }
                    if (choices.isNotEmpty()) put(pkg, choices)
                }
            } else {
                icons.keys().forEach { pkg ->
                    val path = icons.getString(pkg)
                    if (CustomIcons.isValidKey(pkg) && safe(path)) put(pkg, listOf(Choice("default", path, "")))
                }
            }
        }
        map to (root.optJSONObject("credits") ?: JSONObject())
    }.getOrNull()

    /** A path on the site under p/, with a plain file name. */
    private fun safe(path: String): Boolean = path.startsWith("p/") && path.removePrefix("p/").let { n ->
        n.isNotEmpty() && n.none { it == '/' || it == '\\' } && !n.startsWith(".")
    }

    private fun connect(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 15_000
    }

    private fun fetch(url: String): String {
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
