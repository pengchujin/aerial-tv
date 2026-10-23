package app.orchard.tv.shelf

/**
 * Who to ask to publish. The TV launcher sends android.media.tv.action.INITIALIZE_PROGRAMS to the
 * apps that listen for it, after boot and after an install, and they answer by creating their
 * channels. Where the launcher is not the system's, nobody does, so Aerial does it once per app
 * version.
 */
object ShelfAsk {
    const val ACTION = "android.media.tv.action.INITIALIZE_PROGRAMS"

    /** A receiver of [ACTION]; [stamp] is when its app was last installed or updated. */
    data class Receiver(val pkg: String, val cls: String, val stamp: Long)

    fun key(r: Receiver) = "${r.pkg}/${r.cls}@${r.stamp}"

    /** The receivers not yet asked in their present version, leaving out Aerial's own. */
    fun pending(receivers: List<Receiver>, asked: Set<String>, own: String): List<Receiver> =
        receivers.filter { it.pkg != own && key(it) !in asked }
}
