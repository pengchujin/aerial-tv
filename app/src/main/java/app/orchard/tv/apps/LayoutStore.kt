package app.orchard.tv.apps

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** A folder on Home, as on tvOS: a tile in the grid that opens to show the apps put in it. */
data class Folder(val id: String, val name: String, val apps: List<String>) {
    val key: String get() = FOLDER_KEY_PREFIX + id
}

const val FOLDER_KEY_PREFIX = "folder:"
private const val FIRST_ROW_GAP_PREFIX = "first-row-gap:"

/** What Home shows: its tiles in order (apps and folders), each folder's apps, and the hidden apps. */
class Arrangement(
    val home: List<AppEntry>,
    val folders: Map<String, List<AppEntry>>,
    val hidden: List<AppEntry>,
    /** Home order with optional empty slots retained in the first row. */
    val homeSlots: List<AppEntry> = home,
)

/**
 * The user's arrangement, keyed by package name: tile order, folders and hidden apps. A folder
 * holds its place in the order by its key; an app in a folder is not in the order itself.
 */
class LayoutStore(context: Context) {
    private val prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE)

    private var order: List<String> =
        prefs.getString("order", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    private var hidden: Set<String> = prefs.getStringSet("hidden", null)?.toSet() ?: emptySet()
    private var folders: Map<String, Folder> = readFolders(prefs.getString("folders", null))
    private var initialized = prefs.getBoolean("initialized", false)

    /**
     * Home in the user's order. Newly installed apps join at the end; [folderTile] makes the tile
     * for a folder from the apps in it that are installed and not hidden. A folder with none of
     * those shows no tile, but keeps its apps for when they come back.
     */
    fun arrange(
        all: List<AppEntry>,
        preserveHiddenSlots: Int = 0,
        folderTile: (Folder, List<AppEntry>) -> AppEntry,
    ): Arrangement {
        val byKey = all.associateBy { it.key }
        if (!initialized) {
            order = all.sortedWith(Defaults.comparator).map { it.key }
            hidden = Defaults.hidden(byKey.keys)
            initialized = true
        }
        val known = order.toHashSet().apply { folders.values.forEach { addAll(it.apps) } }
        val fresh = all.filter { it.key !in known }.sortedWith(Defaults.comparator).map { it.key }
        // Keep keys of apps that are only temporarily missing (e.g. updating) so they return in place.
        order = order + fresh
        save()
        val contents = folders.mapValues { (_, f) -> f.apps.mapNotNull(byKey::get).filter { it.key !in hidden } }
        val home = mutableListOf<AppEntry>()
        val homeSlots = mutableListOf<AppEntry>()
        for (key in order) {
            if (key.startsWith(FIRST_ROW_GAP_PREFIX)) {
                if (homeSlots.size < preserveHiddenSlots) {
                    val originalKey = key.removePrefix(FIRST_ROW_GAP_PREFIX)
                    val movedApp = byKey[originalKey] ?: folderOf(originalKey)?.let { folder ->
                        contents[folder.id]?.takeIf { it.isNotEmpty() }?.let { folderTile(folder, it) }
                    }
                    movedApp?.let { app ->
                        homeSlots += app.copy(
                            packageName = "hidden-slot:${homeSlots.size}",
                            label = "",
                            emptySlot = true,
                        )
                    }
                }
                continue
            }
            val folder = folderOf(key)
            val tile = if (folder != null) {
                contents[folder.id]?.takeIf { it.isNotEmpty() }?.let { folderTile(folder, it) }
            } else {
                byKey[key]?.takeIf { key !in hidden }
            }
            if (tile != null) {
                home += tile
                homeSlots += tile
            } else if (homeSlots.size < preserveHiddenSlots && key in hidden) {
                // Keep only hidden apps that originally occupied a first-row position. Later
                // rows continue to compact normally; the blank tile has no focus or click target.
                byKey[key]?.let { hiddenApp ->
                    homeSlots += hiddenApp.copy(
                        packageName = "hidden-slot:${homeSlots.size}",
                        label = "",
                        emptySlot = true,
                    )
                }
            }
        }
        val inAnyOrder = order + folders.values.flatMap { it.apps }
        return Arrangement(home, contents, inAnyOrder.mapNotNull(byKey::get).filter { it.key in hidden }, homeSlots)
    }

    /** Backwards-compatible overload for callers that do not preserve first-row gaps. */
    fun arrange(all: List<AppEntry>, folderTile: (Folder, List<AppEntry>) -> AppEntry): Arrangement =
        arrange(all, 0, folderTile)

    fun folders(): List<Folder> = order.mapNotNull(::folderOf)

    /**
     * The apps behind Home's first [tiles] tiles in the saved order, a folder's apps (those its
     * picture shows) for a folder: what a cold start loads before the rest. Apps not [installed]
     * hold no place, as on Home; a pinned input does. Empty before Home was first arranged.
     */
    fun leading(tiles: Int, installed: Set<String>): Set<String> = buildSet {
        var n = 0
        for (key in order) {
            if (n >= tiles) break
            if (key in hidden) continue
            val folder = folderOf(key)
            if (folder != null) {
                val inside = folder.apps.filter { it !in hidden && it in installed }
                if (inside.isEmpty()) continue
                addAll(inside.take(9))
            } else if (key in installed) add(key)
            else if (!key.startsWith(SOURCE_KEY_PREFIX)) continue
            n++
        }
    }

    /** The folder [key] is in, if any. */
    fun folderHolding(key: String): Folder? = folders.values.firstOrNull { key in it.apps }

    /**
     * Puts [key] into folder [id], at its end, or into a new folder called [newName] that takes
     * the app's place on Home. Returns the folder's id.
     */
    fun putInFolder(key: String, id: String?, newName: String): String {
        val list = order.toMutableList()
        // A new folder takes the app's place, or sits beside the folder the app comes from.
        val at = list.indexOf(key).takeIf { it >= 0 }
            ?: folderHolding(key)?.let { list.indexOf(it.key) + 1 }?.takeIf { it > 0 }
            ?: list.size
        takeOut(key, list)
        val folder = id?.let(folders::get)
        val into = if (folder != null) {
            folder.copy(apps = folder.apps + key)
        } else {
            val newId = ((folders.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
            Folder(newId, newName, listOf(key)).also { list.add(at.coerceAtMost(list.size), it.key) }
        }
        folders = folders + (into.id to into)
        dropEmpty(list)
        order = list
        save()
        return into.id
    }

    /** Takes [key] out of its folder and puts it on Home just after the folder. */
    fun takeOutOfFolder(key: String) {
        val folder = folderHolding(key) ?: return
        val list = order.toMutableList()
        takeOut(key, list)
        val at = list.indexOf(folder.key)
        list.add(if (at >= 0) at + 1 else list.size, key)
        dropEmpty(list)
        order = list
        save()
    }

    /** Moves [key] onto the slot of [target] inside the folder that holds them both. */
    fun moveInFolder(key: String, target: String) {
        if (key == target) return
        val folder = folderHolding(key)?.takeIf { target in it.apps } ?: return
        val list = folder.apps.toMutableList()
        val from = list.indexOf(key)
        val to = list.indexOf(target)
        list.removeAt(from)
        list.add(list.indexOf(target).let { if (from < to) it + 1 else it }, key)
        folders = folders + (folder.id to folder.copy(apps = list))
        save()
    }

    fun renameFolder(id: String, name: String) {
        val folder = folders[id] ?: return
        folders = folders + (id to folder.copy(name = name))
        save()
    }

    /** Ungroups folder [id]: its apps take its place on Home, in the folder's order. */
    fun ungroup(id: String) {
        val folder = folders[id] ?: return
        val list = order.toMutableList()
        val at = list.indexOf(folder.key)
        list.remove(folder.key)
        list.addAll(if (at >= 0) at else list.size, folder.apps.filter { it !in list })
        folders = folders - id
        order = list
        save()
    }

    private fun folderOf(key: String): Folder? =
        if (key.startsWith(FOLDER_KEY_PREFIX)) folders[key.removePrefix(FOLDER_KEY_PREFIX)] else null

    /** Removes [key] from Home's [list] and from whatever folder holds it. */
    private fun takeOut(key: String, list: MutableList<String>) {
        list.remove(key)
        folders = folders.mapValues { (_, f) -> if (key in f.apps) f.copy(apps = f.apps - key) else f }
    }

    /** A folder whose last app left goes away, tile and all. */
    private fun dropEmpty(list: MutableList<String>) {
        val empty = folders.values.filter { it.apps.isEmpty() }
        empty.forEach { list.remove(it.key) }
        folders = folders - empty.map { it.id }.toSet()
    }

    fun hide(key: String) { hidden = hidden + key; save() }
    fun unhide(key: String) { hidden = hidden - key; save() }

    /**
     * Moves [key] onto the slot of [target] among the visible tiles, shifting the ones in between,
     * the way tvOS reflows its grid while an app is being moved.
     */
    /** Moves a tile to the first available first-row gap, or inserts it at the row end. */
    fun moveToFirstRow(key: String, columns: Int, preserveGaps: Boolean) {
        if (columns <= 0) return
        val list = order.toMutableList()
        val from = list.indexOf(key)
        if (from < 0 || from < columns) return
        list.removeAt(from)
        val gap = if (preserveGaps) list.indices.firstOrNull {
            it < columns && (list[it].startsWith(FIRST_ROW_GAP_PREFIX) || list[it] in hidden)
        } else null
        if (gap != null) {
            val oldSlot = list[gap]
            val hiddenKey = oldSlot.removePrefix(FIRST_ROW_GAP_PREFIX)
            list[gap] = key
            if (hiddenKey != key && hiddenKey !in list) list.add(hiddenKey)
        } else list.add((columns - 1).coerceAtMost(list.size), key)
        order = list
        save()
    }

    fun move(key: String, target: String) {
        if (key == target) return
        val list = order.toMutableList()
        list.remove(FIRST_ROW_GAP_PREFIX + key)
        val from = list.indexOf(key)
        val to = list.indexOf(target)
        if (from < 0 || to < 0) return
        list.removeAt(from)
        val insertAt = list.indexOf(target).let { if (from < to) it + 1 else it }
        list.add(insertAt, key)
        order = list
        save()
    }

    /** Moves an app below the first row while retaining its old slot as a non-interactive gap. */
    fun moveOutOfFirstRow(key: String, target: String) {
        if (key == target) return
        val list = order.toMutableList()
        val from = list.indexOf(key)
        val to = list.indexOf(target)
        if (from < 0 || to < 0 || from >= to) return
        list[from] = FIRST_ROW_GAP_PREFIX + key
        val targetIndex = list.indexOf(target)
        list.add(targetIndex + 1, key)
        order = list
        save()
    }

    private fun save() = prefs.edit {
        putString("order", order.joinToString(","))
        putStringSet("hidden", hidden)
        putString("folders", JSONArray().apply {
            folders.values.forEach { f ->
                put(JSONObject().put("id", f.id).put("name", f.name).put("apps", JSONArray(f.apps)))
            }
        }.toString())
        putBoolean("initialized", true)
    }

    private fun readFolders(json: String?): Map<String, Folder> = runCatching {
        val array = JSONArray(json ?: return@runCatching emptyMap())
        (0 until array.length()).associate { i ->
            val o = array.getJSONObject(i)
            val apps = o.getJSONArray("apps").let { a -> (0 until a.length()).map(a::getString) }
            o.getString("id") to Folder(o.getString("id"), o.getString("name"), apps)
        }
    }.getOrDefault(emptyMap())
}

/** First-run arrangement: streaming apps first, the TV's own utilities last. */
private object Defaults {
    private val preferred = listOf(
        "com.ktcp.video",                  // 云视听极光
        "com.gitvdemo.video",              // 银河奇异果
        "com.cibn.tv",                     // CIBN 酷喵
        "com.hunantv.license",             // 芒果 TV
        "com.xiaodianshi.tv.yst",          // 云视听小电视
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.apple.atve.androidtv.appletv",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom",
        "com.hbo.hbonow",
        "com.spotify.tv.android",
        "com.mitv.mivideoplayer",
        "com.quark.yun.tv",
    )

    private val utilities = listOf(
        "com.xiaomi.mitv.settings",
        "com.android.tv.settings",
        "com.xiaomi.mitv.tvmanager",
        "com.mitv.tvhome",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
    )

    val comparator = compareBy<AppEntry>(
        { preferred.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } },
        { if (it.key in utilities || it.key.startsWith("com.android.")) 1 else 0 },
        { utilities.indexOf(it.key) },
        { it.label.lowercase() },
    )

    fun hidden(installed: Set<String>): Set<String> = buildSet {
        // Xiaomi's own settings app covers everything AOSP settings does, and more.
        if ("com.xiaomi.mitv.settings" in installed) add("com.android.tv.settings")
    }
}
