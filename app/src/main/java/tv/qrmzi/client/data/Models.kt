package tv.qrmzi.client.data

/** Content kinds the website links to. */
enum class Kind { EPISODE, SERIES, MOVIE, UNKNOWN }

/**
 * One card as it appears on qrmzi.tv. The URL is the primary key: the site
 * exposes no shared numeric id between series and episode pages.
 */
data class Card(
    val url: String,
    val title: String,
    val image: String?,
    val number: Int?,
    val ribbon: String?,
    val kind: Kind
) {
    val isPlayable: Boolean get() = kind == Kind.EPISODE || kind == Kind.MOVIE
}

data class SeriesInfo(
    val url: String,
    val title: String,
    val synopsis: String,
    val cover: String?,
    val episodes: List<Card>
)

data class EpisodeInfo(
    val url: String,
    val title: String,
    val seriesTitle: String?,
    val seriesUrl: String?,
    val description: String,
    val seriesSynopsis: String,
    val cover: String?,
    val playerUrl: String?,
    val episodes: List<Card>,
    val related: List<Card>
) {
    /** Index of this episode inside the series' currently published season. */
    val indexInSeason: Int
        get() = episodes.indexOfFirst { samePage(it.url, url) }

    val nextInSeason: Card?
        get() {
            val i = indexInSeason
            return if (i >= 0 && i + 1 < episodes.size) episodes[i + 1] else null
        }

    val previousInSeason: Card?
        get() {
            val i = indexInSeason
            return if (i > 0) episodes[i - 1] else null
        }

    companion object {
        fun samePage(a: String, b: String): Boolean =
            a.trimEnd('/').equals(b.trimEnd('/'), ignoreCase = true)
    }
}

data class Catalogue(
    val cards: List<Card>,
    val page: Int,
    val hasNext: Boolean
)
