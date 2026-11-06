package app.orchard.tv.watchnext

import android.media.tv.TvContract

/** Unknown scales and invalid ratings are omitted; no provider-specific attribution is invented. */
internal fun WatchNextProgram.ratingText(): String? {
    val value = reviewRating?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return when (reviewRatingStyle) {
        0 -> if (value in 0.0..5.0) "${reviewRating.trim()}/5" else null
        2 -> if (value in 0.0..100.0) "${reviewRating.trim()}%" else null
        else -> null
    }
}

internal fun WatchNextProgram.releaseYear(): String? = releaseDate?.trim()
    ?.takeIf { it.matches(Regex("\\d{4}(-\\d{2}-\\d{2})?")) }?.take(4)

// Order matches watch_next_genres in each locale; unknown provider categories are omitted.
private val canonicalGenres = listOf("ANIMAL_WILDLIFE", "ARTS", "COMEDY", "DRAMA", "EDUCATION",
    "ENTERTAINMENT", "FAMILY_KIDS", "GAMING", "LIFE_STYLE", "MOVIES", "MUSIC", "NEWS", "PREMIER",
    "SHOPPING", "SPORTS", "TECH_SCIENCE", "TRAVEL")

internal fun WatchNextProgram.genreIndices(): List<Int> = TvContract.Programs.Genres.decode(genre.orEmpty())
    .mapNotNull { canonicalGenres.indexOf(it).takeIf { index -> index >= 0 } }.distinct()
