package tv.qrmzi.client.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tv.qrmzi.client.util.Arabic.clean
import tv.qrmzi.client.util.Arabic.episodeNumberFromTitle
import tv.qrmzi.client.util.Arabic.stripBrand

/**
 * HTML parsers for the templates documented in WEBSITE_STRUCTURE.md.
 *
 * These functions are deliberately free of `android.*` imports so they can be
 * exercised on a desktop JVM against saved fixtures (see tools/parsertest).
 */
object Parsers {

    const val BASE = "https://www.qrmzi.tv"

    fun document(html: String, base: String = BASE): Document = Jsoup.parse(html, base)

    fun kindOf(url: String): Kind = when {
        url.contains("/episode/") -> Kind.EPISODE
        url.contains("/movies/") -> Kind.MOVIE
        url.contains("/series/") -> Kind.SERIES
        else -> Kind.UNKNOWN
    }

    /** All cards inside a container element (home grid, search grid, tabs). */
    fun cards(scope: Element): List<Card> =
        scope.select("article.post, article.postEp").mapNotNull { card(it) }

    fun card(article: Element): Card? {
        val anchor = article.selectFirst("div.block-post > a[href]")
            ?: article.selectFirst("a[href]")
            ?: return null
        val url = anchor.absUrl("href").ifBlank { anchor.attr("href") }
        if (url.isBlank() || url.startsWith("javascript")) return null

        val title = (article.selectFirst("div.title")?.text()?.clean()
            ?: anchor.attr("title").clean().stripBrand()).ifBlank { "بدون عنوان" }

        val spans = article.select("div.episodeNum span")
        val number = spans.getOrNull(1)?.text()?.trim()?.toIntOrNull()
            ?: spans.getOrNull(0)?.text()?.trim()?.toIntOrNull()
            ?: episodeNumberFromTitle(title)

        val image = article.selectFirst("img[data-src]").let { img ->
            img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        } ?: article.selectFirst("img[src]").let { img ->
            img?.attr("src")?.takeIf {
                it.isNotBlank() && !it.startsWith("data:") && !it.contains("placeholder")
            }
        }

        val ribbon = article.selectFirst("span.ribbon")?.text()?.clean()?.ifBlank { null }

        return Card(url, title, image, number, ribbon, kindOf(url))
    }

    /** Home feed: the grid is `#load-post`; `article.post` is reused elsewhere. */
    fun home(html: String): List<Card> {
        val doc = document(html, "$BASE/")
        val scope = doc.selectFirst("#load-post") ?: return emptyList()
        return cards(scope)
    }

    /** `/?s=<query>` — series + movies, no `#load-post`, no pagination. */
    fun search(html: String, query: String): List<Card> {
        val doc = document(html, "$BASE/?s=$query")
        val results = mutableListOf<Card>()
        results += doc.select("main article.post, main article.postEp").mapNotNull { card(it) }
        if (results.isEmpty()) {
            // defensive fallback for markup drift: any article carrying a content link
            results += doc.select("article.post, article.postEp").mapNotNull { card(it) }
        }
        return results.distinctBy { it.url }
    }

    /** `/all-turkish-series/` and `/all-turkish-movies/` (40 / 49 cards per page). */
    fun catalogue(html: String, page: Int = 1): Catalogue {
        val doc = document(html)
        val scope = doc.selectFirst("main") ?: doc
        val cards = cards(scope).distinctBy { it.url }
        val maxPage = doc.select("div.pagination a").mapNotNull { pageNumberOf(it.attr("href")) }
            .filter { it > 0 }.maxOrNull() ?: page
        return Catalogue(cards, page, page < maxPage)
    }

    fun pageNumberOf(href: String): Int? =
        Regex("/page/(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()

    /** `/series/<slug>/` — cover, title, synopsis and the current season's episodes. */
    fun series(html: String, url: String): SeriesInfo {
        val doc = document(html, url)
        val box = doc.selectFirst(".singleSeries")
        val title = box?.selectFirst(".info h1")?.text()?.clean()
            ?: doc.selectFirst("main h1")?.text()?.clean()
            ?: doc.title().stripBrand()
        val synopsis = (box?.selectFirst(".story") ?: doc.selectFirst(".story"))
            ?.text()?.clean().orEmpty()
        val cover = bestImage(box?.selectFirst(".cover img") ?: doc.selectFirst(".cover img"))
        val episodes = doc.select("main article.postEp, .sec-line article.postEp")
            .mapNotNull { card(it) }
            .distinctBy { it.url }
            .filter { it.kind == Kind.EPISODE }
        return SeriesInfo(url, title, synopsis, cover, episodes)
    }

    /** `/episode/<slug>/` — player iframe, description and the season's episode list. */
    fun episode(html: String, url: String): EpisodeInfo {
        val doc = document(html, url)
        val title = doc.selectFirst(".singleInfo h1")?.text()?.clean()
            ?: doc.selectFirst("main h1")?.text()?.clean()
            ?: doc.title().stripBrand()
        val seriesAnchor = doc.selectFirst(".singleSeries .info h2 a[href]")
        val episodes = doc.select("#episodes-tab article.postEp, #episodes-tab article.post")
            .mapNotNull { card(it) }
            .distinctBy { it.url }
        val related = doc.select("#relevants-tab article.postEp, #relevants-tab article.post")
            .mapNotNull { card(it) }
            .distinctBy { it.url }
        return EpisodeInfo(
            url = url,
            title = title,
            seriesTitle = seriesAnchor?.text()?.clean(),
            seriesUrl = seriesAnchor?.absUrl("href")?.ifBlank { null },
            description = doc.selectFirst(".episode-desc")?.text()?.clean()
                ?: doc.selectFirst(".series-story")?.text()?.clean()
                ?: doc.selectFirst(".singleSeries .story")?.text()?.clean()
                ?: "",
            seriesSynopsis = doc.selectFirst(".series-story")?.text()?.clean()
                ?: doc.selectFirst(".singleSeries .story")?.text()?.clean()
                ?: "",
            cover = bestImage(doc.selectFirst(".singleSeries .cover img")),
            playerUrl = playerIframe(doc),
            episodes = episodes,
            related = related
        )
    }

    /** Extracts the embedded player URL without following it. */
    fun playerIframe(doc: Document): String? {
        val direct = doc.selectFirst(".getEmbed iframe[src]")?.absUrl("src")
        if (!direct.isNullOrBlank()) return direct
        return doc.select("iframe[src]").firstNotNullOfOrNull { frame ->
            val src = frame.absUrl("src")
            when {
                src.isBlank() -> null
                src.startsWith("https://w.anaplayer.online/") -> src
                src.startsWith("https://shooof.online/") -> src
                src.startsWith("https://cdnplus.") -> src
                else -> null
            }
        }
    }

    fun playerIframe(html: String, url: String): String? =
        playerIframe(document(html, url))

    /** `img[data-src]` first (real asset), never the 1x1 placeholder in `src`. */
    fun bestImage(img: Element?): String? {
        if (img == null) return null
        val lazy = img.attr("data-src")
        if (lazy.isNotBlank() && !lazy.startsWith("data:")) return lazy
        for (attr in listOf("data-lazy-src", "data-original")) {
            val v = img.attr(attr)
            if (v.isNotBlank() && !v.startsWith("data:")) return v
        }
        val src = img.attr("src")
        return if (src.isNotBlank() && !src.startsWith("data:") && !src.contains("placeholder")) src else null
    }

    /** True when the archive page exists but has no cards (the site answers 200). */
    fun isEmptyFeed(html: String): Boolean = home(html).isEmpty()
}
