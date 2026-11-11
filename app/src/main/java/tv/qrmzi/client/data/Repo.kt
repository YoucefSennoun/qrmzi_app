package tv.qrmzi.client.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Result of one page visit: the deepest public player page and the provider
 * wrapper (server picker) that the page pointed at.
 */
data class PlayerSources(val deep: String?, val wrapper: String?)

/**
 * Single source of truth for content. Every screen talks to this object; it
 * holds a small TTL cache so moving between screens never refetches the same
 * page, which matters on a 1 GB device with a slow Wi-Fi link.
 */
class Repo(val store: Store) {

    private data class Entry<T>(val value: T, val at: Long)

    private class Lru<K, V>(private val capacity: Int) :
        LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > capacity
    }

    private val htmlCache = Lru<String, Entry<String>>(14)
    private val feedCache = Lru<String, Entry<List<Card>>>(8)
    private val catalogueCache = Lru<String, Entry<Catalogue>>(8)
    private val seriesCache = Lru<String, Entry<SeriesInfo>>(10)
    private val episodeCache = Lru<String, Entry<EpisodeInfo>>(12)
    private val playerCache = Lru<String, String>(24)

    private val feedTtl = 3 * 60_000L
    private val detailTtl = 10 * 60_000L
    private val htmlTtl = 10 * 60_000L

    // ------------------------------------------------------------------ feeds
    suspend fun home(force: Boolean = false): List<Card> = feed("$BASE/", 1, force)

    suspend fun latestPage(page: Int, force: Boolean = false): List<Card> =
        feed(if (page <= 1) "$BASE/" else "$BASE/page/$page/", page, force)

    private suspend fun feed(url: String, page: Int, force: Boolean): List<Card> =
        withContext(Dispatchers.IO) {
            val cached = synchronized(feedCache) { feedCache["h$page"] }
            if (!force && cached != null && fresh(cached)) return@withContext cached.value
            val html = html(url, force)
            val cards = Parsers.home(html)
            synchronized(feedCache) { feedCache["h$page"] = Entry(cards, now()) }
            cards
        }

    /** `/all-turkish-series/` — 40 landscape cards per page, 5 pages. */
    suspend fun seriesCatalogue(page: Int = 1, force: Boolean = false): Catalogue =
        withContext(Dispatchers.IO) {
            val key = "cat:$page"
            val cached = synchronized(catalogueCache) { catalogueCache[key] }
            if (!force && cached != null && fresh(cached)) return@withContext cached.value
            val url = if (page <= 1) "$BASE/all-turkish-series/"
            else "$BASE/all-turkish-series/page/$page/"
            val result = Parsers.catalogue(html(url, force), page)
            synchronized(catalogueCache) { catalogueCache[key] = Entry(result, now()) }
            result
        }

    /** `/all-turkish-movies/` — 49 cards, single page. */
    suspend fun movies(force: Boolean = false): List<Card> =
        withContext(Dispatchers.IO) {
            val cached = synchronized(feedCache) { feedCache["movies"] }
            if (!force && cached != null && fresh(cached)) return@withContext cached.value
            val cards = Parsers.catalogue(html("$BASE/all-turkish-movies/", force), 1).cards
                .filter { it.kind == Kind.MOVIE }
            synchronized(feedCache) { feedCache["movies"] = Entry(cards, now()) }
            cards
        }

    suspend fun search(query: String): List<Card> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val key = "q:$q"
        val cached = synchronized(feedCache) { feedCache[key] }
        if (cached != null && fresh(cached)) return@withContext cached.value
        val url = "$BASE/?s=" + java.net.URLEncoder.encode(q, "UTF-8")
        val cards = Parsers.search(html(url, false), q)
        synchronized(feedCache) { feedCache[key] = Entry(cards, now()) }
        cards
    }

    // ---------------------------------------------------------------- details
    suspend fun series(url: String, force: Boolean = false): SeriesInfo =
        withContext(Dispatchers.IO) {
            val cached = synchronized(seriesCache) { seriesCache[url] }
            if (!force && cached != null && fresh(cached, detailTtl)) return@withContext cached.value
            val info = Parsers.series(html(url, force), url)
            synchronized(seriesCache) { seriesCache[url] = Entry(info, now()) }
            info
        }

    suspend fun episode(url: String, force: Boolean = false): EpisodeInfo =
        withContext(Dispatchers.IO) {
            val cached = synchronized(episodeCache) { episodeCache[url] }
            if (!force && cached != null && fresh(cached, detailTtl)) return@withContext cached.value
            val info = Parsers.episode(html(url, force), url)
            synchronized(episodeCache) { episodeCache[url] = Entry(info, now()) }
            info
        }

    /**
     * Fetches the site page once, then returns both the provider wrapper page
     * and the deepest public player page. Doing both in one pass avoids a
     * second HTML fetch and a duplicate parse on every playback start.
     */
    suspend fun playerSources(episodeUrl: String): PlayerSources = withContext(Dispatchers.IO) {
        Diag.count("player.resolve")
        val cached = synchronized(playerCache) { playerCache[episodeUrl] }
        val pageHtml = try {
            html(episodeUrl, false)
        } catch (e: IOException) {
            return@withContext PlayerSources(cached, null)
        }
        val wrapper = Parsers.playerIframe(pageHtml, episodeUrl)
        if (cached != null) return@withContext PlayerSources(cached, wrapper)
        val first: String = wrapper ?: return@withContext PlayerSources(null, null)

        var next: String = first
        var hops = 0
        while (hops < 3) {
            val host = hostOf(next) ?: break
            if (host.contains("cdnplus")) break
            if (host.contains("qrmzi.tv")) break
            val child = try {
                Http.get(next, episodeUrl)
            } catch (e: IOException) {
                break
            }
            val childUrl = Parsers.playerIframe(child, next) ?: break
            if (childUrl == next) break
            next = childUrl
            hops++
        }
        val deep = if (next.startsWith("https://")) next else null
        if (deep != null) synchronized(playerCache) { playerCache[episodeUrl] = deep }
        PlayerSources(deep, wrapper)
    }

    // ------------------------------------------------------------------ utils
    private suspend fun html(url: String, force: Boolean): String {
        val cached = synchronized(htmlCache) { htmlCache[url] }
        if (!force && cached != null && fresh(cached, htmlTtl)) return cached.value
        return try {
            val body = Http.get(url)
            synchronized(htmlCache) { htmlCache[url] = Entry(body, now()) }
            body
        } catch (e: IOException) {
            // A page fetched a few minutes ago is better than an error screen
            // when the Wi-Fi drops mid-session.
            if (cached != null) {
                Diag.count("repo.stale")
                Diag.log("repo", "using stale copy of ${Diag.safeUrl(url)}")
                cached.value
            } else {
                throw e
            }
        }
    }

    private fun fresh(entry: Entry<*>, ttl: Long = feedTtl): Boolean =
        now() - entry.at < ttl

    fun invalidateAll() {
        synchronized(htmlCache) { htmlCache.clear() }
        synchronized(feedCache) { feedCache.clear() }
        synchronized(catalogueCache) { catalogueCache.clear() }
        synchronized(seriesCache) { seriesCache.clear() }
        synchronized(episodeCache) { episodeCache.clear() }
        synchronized(playerCache) { playerCache.clear() }
    }

    /**
     * Drops cached pages under memory pressure. The current screen keeps its
     * data in its own views, so nothing visibly disappears; the next navigation
     * may simply re-fetch.
     */
    fun trim(evictAll: Boolean) {
        if (evictAll) {
            invalidateAll()
            Diag.count("repo.trim.full")
            return
        }
        trimTo(htmlCache, 6)
        trimTo(feedCache, 4)
        trimTo(catalogueCache, 2)
        trimTo(seriesCache, 4)
        trimTo(episodeCache, 4)
        trimTo(playerCache, 8)
        Diag.count("repo.trim.partial")
    }

    private fun <K, V> trimTo(map: LinkedHashMap<K, V>, keep: Int) {
        synchronized(map) {
            val iterator = map.keys.iterator()
            while (map.size > keep && iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    fun hostOf(url: String): String? = try {
        java.net.URL(url).host?.lowercase()
    } catch (e: Exception) {
        null
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        const val BASE = Parsers.BASE
    }
}
