package app.orchard.tv.lite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The full app's icon library (apps/IconLibrary.kt): ready-made 16:9 tile pictures for popular
 * apps from the website, keyed by package, several per app with index v2 (#706), each credited to
 * its source; the first is the default and the phone page can pick another. The index is read at
 * most twice a day, and only the pictures of apps installed here are downloaded, into
 * files/library. Off the main thread. As there, the phone page can give an app another app's
 * picture ("borrow:<package>" = "<other package>/<id>", issue #1486).
 */
object IconLibrary {
    private const val TAG = "AerialLite"
    private const val BASE = "https://aerial.qust.me/icons/"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L

    /** One picture of an app, as the full app's IconLibrary.Choice. */
    class Choice(val id: String, val path: String, val by: String) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    private lateinit var dir: File
    private lateinit var context: Context
    @Volatile private var index: Map<String, List<Choice>> = emptyMap()
    @Volatile var credits: JSONObject = JSONObject(); private set
    @Volatile private var lastAttempt = 0L
    private val failedAt = HashMap<String, Long>()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        this.context = context.applicationContext
        dir = File(context.filesDir, "library").apply { mkdirs() }
        read(File(dir, "index.json").takeIf { it.exists() }?.readText())?.let { index = it.first; credits = it.second }
    }

    private fun here(c: Choice): File? = File(dir, c.fileName).takeIf { it.exists() }

    /** Every app the library has pictures of, for the phone page's whole-library picker. */
    fun catalog(): Map<String, List<Choice>> = index

    /** The site's address of the pictures. */
    const val PICTURES = BASE

    /** Another app's picture given to [pkg] on the phone page: (its app, it). */
    fun borrowed(pkg: String): Pair<String, Choice>? {
        if (!::dir.isInitialized) return null
        val parts = picks.getString("borrow:$pkg", null)?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return null
        val c = index[parts[0]]?.firstOrNull { it.id == parts[1] } ?: return null
        return Pair(parts[0], c)
    }

    /** Fetches [c] now if it is not here, for a picture borrowed from another app. Off the main thread. */
    fun fetch(c: Choice): Boolean {
        if (!::dir.isInitialized) return false
        val file = File(dir, c.fileName)
        if (file.exists()) return true
        return runCatching {
            Net.download(BASE + c.path, file)
            val ok = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.outWidth > 0
            if (!ok) { file.delete(); error("not a picture") }
        }.onFailure { Log.w(TAG, "could not fetch ${c.path}", it) }.isSuccess
    }

    /** The pictures of [pkg] that are on this TV, the default first. */
    fun choices(pkg: String): List<Choice> =
        if (::dir.isInitialized) index[pkg].orEmpty().filter { here(it) != null } else emptyList()

    /** The picture [pkg]'s tile uses from the library: the phone page's pick, else the default. */
    fun chosen(pkg: String): Choice? {
        borrowed(pkg)?.second?.takeIf { here(it) != null }?.let { return it }
        val here = choices(pkg)
        val id = if (::dir.isInitialized) picks.getString("choice:$pkg", null) else null
        return here.firstOrNull { it.id == id } ?: here.firstOrNull()
    }

    /** The library's picture of [pkg] is on this TV (whether or not its tile uses it). */
    fun has(pkg: String): Boolean = chosen(pkg) != null

    /** The library's picture of [pkg] as stored, for the phone page: [id]'s, or the chosen one. */
    fun file(pkg: String, id: String? = null): File? {
        val c = if (id == null) chosen(pkg) else choices(pkg).firstOrNull { it.id == id }
            ?: borrowed(pkg)?.second?.takeIf { it.id == id }
        return c?.let(::here)
    }

    /** Whether [pkg]'s tile uses the library: unless the phone page picked the app's own art. */
    fun uses(pkg: String): Boolean = pick(pkg) != Pick.Own

    private fun usable(pkg: String): File? = if (uses(pkg)) file(pkg) else null

    enum class Pick { Library, Own }

    private val picks get() = context.getSharedPreferences("icon_library", Context.MODE_PRIVATE)

    /** What the phone page picked for [pkg]; null for the library. */
    fun pick(pkg: String): Pick? = when {
        !::dir.isInitialized -> null
        pkg in picks.getStringSet("use_library", emptySet())!! -> Pick.Library
        pkg in picks.getStringSet("use_own", emptySet())!! -> Pick.Own
        else -> null
    }

    /** From the phone page, as the full app's IconLibrary.choose. */
    fun choose(pkg: String, pick: Pick?, choice: String? = null, from: String? = null) {
        val library = picks.getStringSet("use_library", emptySet())!! - pkg
        val own = picks.getStringSet("use_own", emptySet())!! - pkg
        val default = index[pkg]?.firstOrNull()?.id
        val edit = picks.edit()
            .putStringSet("use_library", if (pick == Pick.Library) library + pkg else library)
            .putStringSet("use_own", if (pick == Pick.Own) own + pkg else own)
        if (pick == Pick.Library && from != null && from != pkg && choice != null) {
            edit.putString("borrow:$pkg", "$from/$choice")
        } else {
            edit.remove("borrow:$pkg")
            if (pick != Pick.Own) {
                if (choice == null || choice == default) edit.remove("choice:$pkg") else edit.putString("choice:$pkg", choice)
            }
        }
        edit.apply()
    }

    /** Changes with the app's picture or the pick; 0 without one. Part of the tile cache's key. */
    fun stamp(pkg: String): Long = (usable(pkg)?.let { it.lastModified() + it.name.hashCode() } ?: 0L) +
        when (pick(pkg)) { Pick.Own -> 1L; Pick.Library -> 2L; null -> 0L }

    fun load(pkg: String): Bitmap? = usable(pkg)?.let { BitmapFactory.decodeFile(it.path) }

    /** Reads the index when due and fetches pictures for [installed] apps; true when a tile changed. */
    @Synchronized
    fun refresh(installed: Set<String>): Boolean {
        if (!::dir.isInitialized) return false
        val prefs = picks
        val now = System.currentTimeMillis()
        var changed = false
        // Read again right after an update too, as in the full app (#706).
        @Suppress("DEPRECATION")
        val code = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionCode }.getOrDefault(0)
        val due = now - prefs.getLong("checked_at", 0) > CHECK_EVERY_MS || prefs.getInt("checked_by", 0) != code
        if (due && now - lastAttempt > RETRY_MS) {
            lastAttempt = now
            runCatching { Net.text(BASE + "index.json") }
                .onFailure { Log.w(TAG, "could not read the icon index", it) }
                .getOrNull()
                ?.let { text ->
                    read(text)?.let { (fresh, c) ->
                        File(dir, "index.json").writeText(text)
                        changed = fresh.mapValues { e -> e.value.map { it.path } } != index.mapValues { e -> e.value.map { it.path } }
                        index = fresh
                        credits = c
                        prefs.edit().putLong("checked_at", now).putInt("checked_by", code).apply()
                    }
                }
        }
        val keep = HashSet<String>()
        for (pkg in installed) {
            for (c in index[pkg].orEmpty() + listOfNotNull(borrowed(pkg)?.second)) {
                val file = File(dir, c.fileName)
                keep += file.name
                if (file.exists() || now - (failedAt[file.name] ?: 0L) < RETRY_MS) continue
                runCatching {
                    Net.download(BASE + c.path, file)
                    val ok = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.outWidth > 0
                    if (!ok) { file.delete(); error("not a picture") }
                }
                    .onSuccess { changed = true; failedAt.remove(file.name) }
                    .onFailure { failedAt[file.name] = now; Log.w(TAG, "could not fetch ${c.path}", it) }
            }
        }
        // Pictures replaced by newer ones, or of apps no longer installed.
        dir.listFiles()?.forEach { if (it.name != "index.json" && it.name !in keep && !it.name.endsWith(".part")) it.delete() }
        return changed
    }

    /** The index's pictures and credits; null when [text] is not an index. */
    private fun read(text: String?): Pair<Map<String, List<Choice>>, JSONObject>? = runCatching {
        val root = JSONObject(text ?: return null)
        val sets = root.optJSONObject("choices")
        val icons = root.getJSONObject("icons")
        val out = HashMap<String, List<Choice>>()
        val keys = (sets ?: icons).keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            if (!pkg.matches(Regex("[A-Za-z0-9_.]+"))) continue
            val choices = if (sets != null) {
                val list = sets.getJSONArray(pkg)
                (0 until list.length()).mapNotNull { i ->
                    val o = list.getJSONObject(i)
                    val path = o.optString("p")
                    Choice(o.optString("id"), path, o.optString("by")).takeIf { safe(path) && it.id.isNotEmpty() }
                }
            } else {
                listOf(Choice("default", icons.getString(pkg), "")).filter { safe(it.path) }
            }
            if (choices.isNotEmpty()) out[pkg] = choices
        }
        Pair<Map<String, List<Choice>>, JSONObject>(out, root.optJSONObject("credits") ?: JSONObject())
    }.getOrNull()

    /** A path on the site under p/, with a plain file name. */
    private fun safe(path: String): Boolean = path.startsWith("p/") && path.removePrefix("p/").let { n ->
        n.isNotEmpty() && n.none { it == '/' || it == '\\' } && !n.startsWith(".")
    }
}
