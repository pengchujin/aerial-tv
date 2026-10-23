package app.orchard.tv.shelf

/**
 * One row read from the TV provider's watch_next_program or preview_program table, before it is
 * arranged for the shelf. Plain values only, so the arranging can be tested off the device.
 */
data class RawProgram(
    val title: String?,
    val episodeTitle: String?,
    val description: String?,
    val posterUri: String?,
    val thumbnailUri: String?,
    /** TvContract's aspect ratio code for whichever picture is used; -1 when unknown. */
    val aspect: Int,
    val intentUri: String?,
    /** preview_program only: the channel it belongs to. */
    val channelId: Long,
    /** preview_program only: higher sorts first within its channel. */
    val weight: Int,
    /** watch_next_program only. */
    val lastEngagedMillis: Long,
    val positionMillis: Long,
    val durationMillis: Long,
    /** The season and episode as the owner numbers them (display numbers); blank or 0 for none. */
    val season: String? = null,
    val episodeNumber: String? = null,
)

/** A card on the shelf. */
data class ShelfItem(
    val title: String,
    val subtitle: String?,
    val description: String?,
    val image: String?,
    /** Width over height of the picture. */
    val aspect: Float,
    /** How far through it the owner is, 0..1, for watch-next programs that say. */
    val progress: Float?,
    val intentUri: String,
    /** Season and episode numbers of a series, as the owner numbers them. */
    val season: String? = null,
    val episode: String? = null,
    /** Time left in what is being watched, where the owner says how far it is. */
    val leftMillis: Long? = null,
    /** The channel it came from; 0 for watch next, which belongs to no channel. */
    val channel: Long = 0,
    /** That channel's name as its owner gave it; null for watch next. */
    val channelTitle: String? = null,
)

/** The cards of one channel, or of watch next (no [title]): one tab of the shelf. */
data class ShelfTab(val channel: Long, val title: String?, val items: List<ShelfItem>)

object ShelfRows {
    const val LIMIT = 20

    /**
     * The shelf for one app, the way Android TV's own home orders it: what you are in the middle
     * of (watch next, most recent first), then the app's channels in their order, each heaviest
     * first. Programs on channels the owner hid, and programs with nothing to open, are left out.
     */
    fun arrange(
        watchNext: List<RawProgram>,
        previews: List<RawProgram>,
        channelOrder: List<Long>,
        channelTitles: Map<Long, String> = emptyMap(),
    ): List<ShelfItem> {
        val rank = channelOrder.withIndex().associate { (i, id) -> id to i }
        val ordered = watchNext.sortedByDescending { it.lastEngagedMillis } +
            previews.filter { it.channelId in rank }
                .sortedWith(compareBy<RawProgram> { rank.getValue(it.channelId) }.thenByDescending { it.weight })
        val seen = HashSet<String>()
        return ordered.asSequence()
            .mapNotNull { item(it, channelTitles) }
            .filter { seen.add(it.intentUri) }
            // The limit is per channel: a long one must not crowd out the next.
            .groupBy { it.channel }.values.flatMap { it.take(LIMIT) }
    }

    /** The shelf split by channel, in shelf order: watch next first, then the channels as arranged. */
    fun tabs(items: List<ShelfItem>): List<ShelfTab> =
        items.groupBy { it.channel }.map { (channel, list) -> ShelfTab(channel, list.first().channelTitle, list) }

    private fun item(p: RawProgram, channelTitles: Map<Long, String>): ShelfItem? {
        val link = p.intentUri?.takeIf { it.isNotBlank() } ?: return null
        val title = p.title?.takeIf { it.isNotBlank() } ?: return null
        // Some owners publish an empty string for "none".
        val description = p.description?.trim()?.takeIf { it.isNotEmpty() }
        val progress = if (p.durationMillis > 0 && p.positionMillis in 1..p.durationMillis) {
            p.positionMillis.toFloat() / p.durationMillis
        } else null
        return ShelfItem(
            title = title.trim(),
            subtitle = p.episodeTitle?.trim()?.takeIf { it.isNotEmpty() } ?: description,
            description = description,
            image = p.posterUri?.takeIf { it.isNotBlank() } ?: p.thumbnailUri?.takeIf { it.isNotBlank() },
            aspect = aspect(p.aspect),
            progress = progress,
            intentUri = link,
            season = number(p.season),
            episode = number(p.episodeNumber),
            leftMillis = if (progress != null) p.durationMillis - p.positionMillis else null,
            channel = p.channelId,
            channelTitle = channelTitles[p.channelId],
        )
    }

    /** A season or episode number as shown; blank and 0 mean the program has none. */
    private fun number(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != "0" }

    /** TvContract.PreviewPrograms.ASPECT_RATIO_*; anything else reads as 16:9, the common case. */
    fun aspect(code: Int): Float = when (code) {
        1 -> 3f / 2f
        2 -> 4f / 3f
        3 -> 1f
        4 -> 2f / 3f
        5 -> 1f / 1.441f
        else -> 16f / 9f
    }
}
