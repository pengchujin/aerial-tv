package app.orchard.tv.shelf

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import app.orchard.tv.settings.AerialSettings
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What apps publish for the home screen through Android TV's TvProvider: their watch-next
 * programs and the programs on their preview channels (Android 8.0+). Aerial is not a system app,
 * so ACCESS_ALL_EPG_DATA is out of reach; READ_TV_LISTINGS, granted over adb by tools/install.sh,
 * is enough to read every app's rows (Sony BRAVIA_4K_VH2, Android 12: Emby's 49 previews and 226
 * watch-next programs). Without it, before Android 8.0, or on any error, an app simply has no shelf.
 */
object Shelf {
    private const val TAG = "Shelf"
    private const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
    private val BASE: Uri = Uri.parse("content://android.media.tv")
    private val CHANNELS: Uri = Uri.withAppendedPath(BASE, "channel")
    private val PREVIEWS: Uri = Uri.withAppendedPath(BASE, "preview_program")
    private val WATCH_NEXT: Uri = Uri.withAppendedPath(BASE, "watch_next_program")

    private lateinit var app: Context
    private var started = false
    /** Every app's shelf by package, from the last read of the provider. */
    private val cache = HashMap<String, List<ShelfItem>>()

    /** Goes up whenever the provider's data changes; anything shown from the cache reloads. */
    var version by mutableIntStateOf(0); private set

    /** Bumped on every provider change; a read that started before one is not trusted as current. */
    @Volatile private var generation = 0
    /** One read of the provider at a time: the shelf and the top-row prefetch often ask together. */
    private val reading = Mutex()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        if (Build.VERSION.SDK_INT < 26) return
        // Watched even before READ_TV_LISTINGS is granted: install.sh grants it after Aerial is
        // already running. Players write watch-next progress every few seconds while playing, so
        // a burst of changes becomes one reload a second later.
        val main = Handler(Looper.getMainLooper())
        val bump = Runnable { version++ }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                synchronized(cache) { generation++; loaded = false }
                main.removeCallbacks(bump)
                main.postDelayed(bump, 1_000)
            }
        }
        runCatching { app.contentResolver.registerContentObserver(BASE, true, observer) }
            .onFailure { Log.w(TAG, "cannot watch the TV provider", it) }
    }

    /**
     * Asks the apps that can publish to do so ([ShelfAsk]); each is asked once per version, and
     * what it publishes arrives through the observer. Does nothing without the permission to read it.
     */
    suspend fun askToPublish() {
        if (!available() || !AerialSettings.appContent) return
        withContext(Dispatchers.IO) {
            val pm = app.packageManager
            val prefs = app.getSharedPreferences("shelf", Context.MODE_PRIVATE)
            val asked = prefs.getStringSet("asked", emptySet()).orEmpty()
            val found = runCatching { pm.queryBroadcastReceivers(Intent(ShelfAsk.ACTION), 0) }.getOrDefault(emptyList()).mapNotNull {
                val info = it.activityInfo ?: return@mapNotNull null
                val stamp = runCatching { pm.getPackageInfo(info.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                ShelfAsk.Receiver(info.packageName, info.name, stamp)
            }
            val todo = ShelfAsk.pending(found, asked, app.packageName)
            todo.forEach {
                runCatching { app.sendBroadcast(Intent(ShelfAsk.ACTION).setClassName(it.pkg, it.cls)) }
                    .onFailure { e -> Log.w(TAG, "could not ask ${it.pkg}", e) }
            }
            if (todo.isNotEmpty()) {
                Log.i(TAG, "asked to publish: " + todo.joinToString { it.pkg })
                // Only the current versions are kept, so the set does not grow with every update.
                val keep = found.map(ShelfAsk::key).toSet()
                prefs.edit().putStringSet("asked", (asked + todo.map(ShelfAsk::key)).intersect(keep)).apply()
            }
        }
    }

    fun available(): Boolean = Build.VERSION.SDK_INT >= 26 &&
        app.checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    /** Already loaded: shown at once, without the settle delay. Null until the first read. */
    fun cached(pkg: String): List<ShelfItem>? = synchronized(cache) { if (loaded) cache[pkg].orEmpty() else null }

    @Volatile private var loaded = false

    suspend fun load(pkg: String): List<ShelfItem> {
        cached(pkg)?.let { return it }
        if (!available()) return emptyList()
        return reading.withLock {
            cached(pkg)?.let { return@withLock it }
            val started = generation
            val all = withContext(Dispatchers.IO) {
                runCatching { readAll() }.onFailure { Log.w(TAG, "TV provider unavailable", it) }.getOrDefault(emptyMap())
            }
            synchronized(cache) {
                cache.clear()
                cache.putAll(all)
                // Changed while reading: shown for now, read again on the coming version bump.
                loaded = generation == started
            }
            all[pkg].orEmpty()
        }
    }

    /**
     * Every app's shelf in one pass. Without ACCESS_ALL_EPG_DATA the provider refuses any
     * selection or sort order ("Selection not allowed", Android TV 12 emulator), so whole tables
     * are read and grouped here; they hold a few hundred rows at most.
     */
    private fun readAll(): Map<String, List<ShelfItem>> {
        val r = app.contentResolver
        val channels = r.query(CHANNELS, arrayOf("_id", "package_name", "browsable", "display_name"), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Channel(c.getString(1).orEmpty(), c.getLong(0), c.getInt(2) == 1, c.getString(3)?.trim().orEmpty())) }
        }.orEmpty()
        val watchNext = query(WATCH_NEXT, PROGRAM_COLUMNS + "last_engagement_time_utc_millis")
        val previews = query(PREVIEWS, PROGRAM_COLUMNS + "channel_id")
        val packages = (channels.map { it.pkg } + watchNext.map { it.first } + previews.map { it.first }).toSet()
        // One line per read, so `adb logcat -s Shelf` answers "does this app publish anything?".
        Log.i(TAG, "read " + packages.sorted().joinToString { pkg ->
            "$pkg: ${channels.count { it.pkg == pkg }} channels, ${watchNext.count { it.first == pkg }} watch next, ${previews.count { it.first == pkg }} previews"
        }.ifEmpty { "nothing published" })
        return packages.associateWith { pkg ->
            // The channels the owner chose for the home screen. Browsable is written by the
            // system's own launcher; where it never ran or never asked, every channel of the app
            // reads as hidden, so then all of them count rather than none.
            val own = channels.filter { it.pkg == pkg }.sortedBy { it.id }
            val order = own.filter { it.browsable }.ifEmpty { own }.map { it.id }
            ShelfRows.arrange(
                watchNext.filter { it.first == pkg }.map { it.second },
                previews.filter { it.first == pkg }.map { it.second },
                order,
                own.filter { it.name.isNotEmpty() }.associate { it.id to it.name },
            )
        }.filterValues { it.isNotEmpty() }
    }

    private class Channel(val pkg: String, val id: Long, val browsable: Boolean, val name: String)

    /** Only what the shelf shows: rows also carry blobs (internal_provider_data) it never reads. */
    private val PROGRAM_COLUMNS = arrayOf(
        "package_name", "browsable", "title", "episode_title", "short_description",
        "poster_art_uri", "poster_art_aspect_ratio", "thumbnail_uri", "thumbnail_aspect_ratio",
        "intent_uri", "weight", "last_playback_position_millis", "duration_millis",
        "season_display_number", "episode_display_number", "long_description",
    )

    /** A whole program table; every column if a provider does not know one of those names. */
    private fun query(uri: Uri, columns: Array<String>): List<Pair<String, RawProgram>> {
        val r = app.contentResolver
        val cursor = runCatching { r.query(uri, columns, null, null, null) }
            .getOrElse { if (it is IllegalArgumentException) r.query(uri, null, null, null, null) else throw it }
        return cursor?.use(::programs).orEmpty()
    }

    /** (package, program) for each browsable row. */
    private fun programs(c: Cursor): List<Pair<String, RawProgram>> {
        fun col(name: String) = c.getColumnIndex(name)
        val title = col("title")
        val episode = col("episode_title")
        val description = col("short_description")
        val poster = col("poster_art_uri")
        val posterAspect = col("poster_art_aspect_ratio")
        val thumb = col("thumbnail_uri")
        val thumbAspect = col("thumbnail_aspect_ratio")
        val intent = col("intent_uri")
        val channel = col("channel_id")
        val weight = col("weight")
        val engaged = col("last_engagement_time_utc_millis")
        val position = col("last_playback_position_millis")
        val duration = col("duration_millis")
        val longDescription = col("long_description")
        val seasonCol = col("season_display_number")
        val episodeCol = col("episode_display_number")
        val pkgCol = col("package_name")
        val browsable = col("browsable")
        fun str(i: Int) = if (i >= 0 && !c.isNull(i)) c.getString(i) else null
        fun long(i: Int) = if (i >= 0 && !c.isNull(i)) c.getLong(i) else 0L
        fun int(i: Int, default: Int) = if (i >= 0 && !c.isNull(i)) c.getInt(i) else default
        return buildList {
            while (c.moveToNext()) {
                if (int(browsable, 1) != 1) continue
                val posterUri = str(poster)
                add(
                    str(pkgCol).orEmpty() to RawProgram(
                        title = str(title),
                        episodeTitle = str(episode),
                        description = str(description)?.takeIf { it.isNotBlank() } ?: str(longDescription),
                        posterUri = posterUri,
                        thumbnailUri = str(thumb),
                        // The aspect of the picture the card will show.
                        aspect = if (!posterUri.isNullOrBlank()) int(posterAspect, -1) else int(thumbAspect, -1),
                        intentUri = str(intent),
                        channelId = long(channel),
                        weight = int(weight, 0),
                        lastEngagedMillis = long(engaged),
                        positionMillis = long(position),
                        durationMillis = long(duration),
                        season = str(seasonCol),
                        episodeNumber = str(episodeCol),
                    ),
                )
            }
        }
    }
}
