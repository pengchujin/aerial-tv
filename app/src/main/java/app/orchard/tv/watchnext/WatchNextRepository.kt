package app.orchard.tv.watchnext

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One shared-table query and one observer for the foreground session, including empty results. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
open class WatchNextRepository(private val context: Context) : AutoCloseable {
    companion object {
        const val Permission = "android.permission.READ_TV_LISTINGS"
        val Uri: Uri = android.net.Uri.parse("content://android.media.tv/watch_next_program")
        private val columns = arrayOf("_id", "package_name", "title", "episode_title", "season_display_number",
            "episode_display_number", "type", "watch_next_type", "poster_art_uri", "thumbnail_uri",
            "poster_art_aspect_ratio", "poster_thumbnail_aspect_ratio", "intent_uri", "last_playback_position_millis",
            "duration_millis", "last_engagement_time_utc_millis", "browsable", "short_description",
            "long_description", "canonical_genre", "release_date", "review_rating", "review_rating_style")
    }
    private val executor = Executors.newSingleThreadExecutor()
    open fun capability(): WatchNextCapability {
        if (Build.VERSION.SDK_INT < 26) return WatchNextCapability(false, false, false)
        val pm = context.packageManager
        if (pm.resolveContentProvider(Uri.authority!!, 0) == null) return WatchNextCapability(false, false, false)
        val info = try { pm.getPermissionInfo(Permission, 0) } catch (_: PackageManager.NameNotFoundException) {
            return WatchNextCapability(false, false, false)
        }
        val granted = context.checkSelfPermission(Permission) == PackageManager.PERMISSION_GRANTED
        return WatchNextCapability(true, granted, info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS)
    }

    open fun observe(): kotlinx.coroutines.flow.Flow<Result<List<WatchNextProgram>>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        try {
            context.contentResolver.registerContentObserver(Uri, true, observer)
            trySend(Unit)
        } catch (e: Exception) { close(e) }
        awaitClose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }.conflate().mapLatest {
        delay(150)
        query()
    }

    internal suspend fun query(): Result<List<WatchNextProgram>> = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val future = executor.submit {
            val started = android.os.SystemClock.elapsedRealtime()
            val result = runCatching {
                check(capability().let { it.supported && it.granted }) { "Watch Next permission unavailable" }
                val resolver = context.contentResolver
                // Some vendor projections omit optional columns. Exactly one bounded fallback.
                val cursor = try { resolver.query(Uri, columns, null, null, null, signal) }
                    catch (_: IllegalArgumentException) { resolver.query(Uri, null, null, null, null, signal) }
                checkNotNull(cursor) { "Watch Next provider returned no cursor" }.use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            signal.throwIfCanceled()
                            val id = c.number("_id") ?: continue
                            val pkg = c.text("package_name")?.takeIf { it.isNotBlank() } ?: continue
                            add(WatchNextProgram(ProgramKey(pkg, id), c.text("title"), c.text("episode_title"),
                                c.text("season_display_number"), c.text("episode_display_number"), c.number("type")?.toInt(),
                                c.number("watch_next_type")?.toInt(), c.text("poster_art_uri"), c.text("thumbnail_uri"),
                                c.number("poster_art_aspect_ratio")?.toInt(), c.number("poster_thumbnail_aspect_ratio")?.toInt(),
                                c.text("intent_uri"), c.number("last_playback_position_millis"), c.number("duration_millis"),
                                c.number("last_engagement_time_utc_millis"), c.number("browsable")?.let { it != 0L },
                                description = c.nonBlank("short_description") ?: c.nonBlank("long_description"),
                                genre = c.nonBlank("canonical_genre"), releaseDate = c.nonBlank("release_date"),
                                reviewRating = c.nonBlank("review_rating"), reviewRatingStyle = c.number("review_rating_style")?.toInt()))
                        }
                    }
                }
            }
            app.orchard.tv.system.DiagLog.i("watch next query: ${result.getOrNull()?.size ?: -1} rows, ${android.os.SystemClock.elapsedRealtime() - started} ms")
            continuation.resume(result)
        }
        continuation.invokeOnCancellation { signal.cancel(); future.cancel(true) }
    }
    override fun close() { executor.shutdownNow() }
}
private fun Cursor.text(name: String): String? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
private fun Cursor.nonBlank(name: String): String? = text(name)?.trim()?.takeIf { it.isNotEmpty() }
private fun Cursor.number(name: String): Long? = getColumnIndex(name).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
