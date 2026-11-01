package app.orchard.tv.wallpaper

import android.content.Context
import androidx.core.content.edit
import java.time.LocalDate

/**
 * The few aerial films Aerial plays, kept on disk (AerialStream), shared by the aerial wallpaper and
 * every screensaver of aerials in every format, so going from one to the other is the same film
 * (owner, 2026-10-29: "从杜比的屏保到航拍壁纸切换的时候，最好是同一个视频").
 *
 * - [MAX] films, newest first. Both play them in this order, one after another, round and round;
 *   where each one is and how far in is Wallpapers.liveSpot, written by whichever plays.
 * - A new film joins once a day, at the day's first screensaver or Home's first film ([takeDaily]),
 *   and pressing right in the screensaver adds one at once ([addNew]); the oldest leaves either way.
 * - Left steps back through the films kept, which play from disk.
 *
 * Until 1.69 every screensaver shuffled all 114 films, and the wallpaper picked its own: the two
 * rarely matched, and every film was fetched again each time (issue #843).
 */
object AerialSet {
    const val MAX = 3

    private const val PREFS = "aerial_set"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ids(context: Context): List<String> =
        prefs(context).getString("films", null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    private fun save(context: Context, ids: List<String>, day: String? = null) = prefs(context).edit {
        putString("films", ids.joinToString(","))
        if (day != null) putString("day", day)
    }

    /** The set, newest first; on the first use, the film the wallpaper last showed, else any one. */
    @Synchronized
    fun films(context: Context): List<Aerial> {
        val all = Aerials.all(context)
        val known = ids(context).mapNotNull { id -> all.firstOrNull { it.id == id } }
        if (known.isNotEmpty()) return known
        val first = runCatching { Wallpapers.liveSpot() }.getOrNull()?.first?.let { id -> all.firstOrNull { it.id == id } } ?: all.random()
        save(context, listOf(first.id), today())
        return listOf(first)
    }

    /**
     * The films in playing order from [current]: it first, then those after it in the set, then
     * round to those before. A film not in the set (the wallpaper's from before 1.70) joins it.
     */
    @Synchronized
    fun playlist(context: Context, current: String?): List<Aerial> {
        var set = films(context)
        val all = Aerials.all(context)
        if (current != null && set.none { it.id == current }) {
            all.firstOrNull { it.id == current }?.let { film ->
                set = (listOf(film) + set).take(MAX)
                save(context, set.map { it.id })
            }
        }
        val at = set.indexOfFirst { it.id == current }.coerceAtLeast(0)
        return set.drop(at) + set.take(at)
    }

    /** The film a new day brings in, chosen ahead so the screensaver's start can be fetched first. */
    @Synchronized
    fun nextNew(context: Context): Aerial {
        val all = Aerials.all(context)
        prefs(context).getString("next", null)?.let { id -> all.firstOrNull { it.id == id } }?.let { return it }
        val kept = ids(context).toSet()
        val film = all.filter { it.id !in kept }.randomOrNull() ?: all.random()
        prefs(context).edit { putString("next", film.id) }
        return film
    }

    /** A film joins the set as its newest, the oldest leaves, and the next new one is chosen afresh. */
    @Synchronized
    fun addNew(context: Context): Aerial {
        val film = nextNew(context)
        val set = (listOf(film.id) + ids(context).filter { it != film.id }).take(MAX)
        save(context, set, today())
        prefs(context).edit { remove("next") }
        app.orchard.tv.system.DiagLog.i("aerial films now ${set.joinToString(",") { it.take(8) }}")
        return film
    }

    /** Whether today has not had its new film yet. */
    fun due(context: Context): Boolean = prefs(context).getString("day", null) != today()

    /** Today's new film, if today has not had one: it joins the set (see [addNew]). */
    fun takeDaily(context: Context): Aerial? = if (due(context)) addNew(context) else null

    /** Today's date; a test moves it on. */
    internal var today: () -> String = { LocalDate.now().toString() }
}
