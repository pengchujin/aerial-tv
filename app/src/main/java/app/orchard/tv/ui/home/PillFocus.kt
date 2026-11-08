package app.orchard.tv.ui.home

/** The first or last existing tile on the first row, or null when there is nowhere to go. */
internal fun pillTile(count: Int, columns: Int, right: Boolean): Int? {
    if (count <= 0 || columns <= 0) return null
    return if (right) 0 else minOf(count, columns) - 1
}
