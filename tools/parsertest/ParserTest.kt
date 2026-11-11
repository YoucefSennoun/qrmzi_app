import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.Diag
import tv.qrmzi.client.data.Filter
import tv.qrmzi.client.data.Http
import tv.qrmzi.client.data.Kind
import tv.qrmzi.client.data.NavPolicy
import tv.qrmzi.client.data.NetRules
import tv.qrmzi.client.data.Parsers
import tv.qrmzi.client.data.Playback

/**
 * JVM regression test for the HTML parsers, run against the fixtures saved in
 * /tmp/opencode/qrmzi-evidence. Not part of the APK.
 */
private var failures = 0
private var checks = 0

private fun check(name: String, condition: Boolean, detail: String = "") {
    checks++
    if (condition) {
        println("  ok   $name${if (detail.isEmpty()) "" else "  ($detail)"}")
    } else {
        failures++
        println("  FAIL $name  $detail")
    }
}

private fun load(name: String): String = java.io.File(DIR, name).readText(Charsets.UTF_8)

private fun kinds(cards: List<Card>) = cards.groupingBy { it.kind }.eachCount()

private fun row(label: String, cards: List<Card>) {
    println("     $label: ${cards.size} ${kinds(cards)}")
}

const val DIR = "/tmp/opencode/qrmzi-evidence"

fun main() {
    println("== home feed ==")
    run {
        val cards = Parsers.home(load("home.html"))
        check("50 cards", cards.size == 50, "got ${cards.size}")
        check("all absolute https", cards.all { it.url.startsWith("https://www.qrmzi.tv/") || it.url.startsWith("https://qrmzi.tv/") })
        check("images present", cards.count { it.image?.startsWith("https://") == true } >= 49,
            "with image=${cards.count { it.image?.startsWith("https://") == true }}")
        check("episode numbers", cards.count { it.number != null } >= 45, "numbers=${cards.count { it.number != null }}")
        check("ribbons", cards.count { it.ribbon != null } >= 8, "ribbons=${cards.count { it.ribbon != null }}")
        check("no title keeps brand suffix", cards.none { it.title.endsWith("قرمزي") })
        row("kinds", cards)
        cards.take(3).forEach { println("     - [${it.number}] ${it.title} -> ${it.url}") }
    }

    println("== home page 2 / 3 ==")
    run {
        val p2 = Parsers.home(load("page2.html"))
        check("page2 50 cards", p2.size == 50, "got ${p2.size}")
        check("page2 has a movie card", p2.any { it.kind == Kind.MOVIE },
            "movies=${p2.count { it.kind == Kind.MOVIE }}")
        val p1 = Parsers.home(load("home.html"))
        val overlap = p1.map { it.url }.intersect(p2.map { it.url }.toSet())
        check("no overlap p1/p2", overlap.isEmpty(), "overlap=${overlap.size}")
        val p3 = Parsers.home(load("p3.html"))
        check("page3 non-empty", p3.isNotEmpty(), "got ${p3.size}")
    }

    println("== last page (p20) ==")
    run {
        val p20 = Parsers.home(load("p20.html"))
        check("p20 20 cards", p20.size == 20, "got ${p20.size}")
        check("p20 no duplicate urls", p20.distinctBy { it.url }.size == p20.size)
    }

    println("== series catalogue ==")
    run {
        val c = Parsers.catalogue(load("all_series.html"), 1)
        check("40 cards", c.cards.size == 40, "got ${c.cards.size}")
        check("all series", c.cards.all { it.kind == Kind.SERIES }, "kinds=${kinds(c.cards)}")
        check("has next", c.hasNext)
        check("landscape images", c.cards.count { it.image?.contains("470x25") == true || it.image?.contains("470x26") == true } >= 39,
            "landscape=${c.cards.count { it.image?.contains("470x25") == true || it.image?.contains("470x26") == true }}")
        val c2 = Parsers.catalogue(load("all_series_p2.html"), 2)
        check("page2 40 cards", c2.cards.size == 40, "got ${c2.cards.size}")
        check("page2 current=2 parsed next", c2.hasNext)
        println("     sample: ${c.cards.first().title} ${c.cards.first().image}")
    }

    println("== search ==")
    run {
        val hab = Parsers.search(load("search_%D8%AD%D8%A8.html"), "حب")
        check("حب 23 cards", hab.size == 23, "got ${hab.size}")
        check("حب 18 series", hab.count { it.kind == Kind.SERIES } == 18,
            "series=${hab.count { it.kind == Kind.SERIES }}")
        check("حب 5 movies", hab.count { it.kind == Kind.MOVIE } == 5,
            "movies=${hab.count { it.kind == Kind.MOVIE }}")
        val khalifa = Parsers.search(load("search_%D8%A7%D9%84%D8%AE%D9%84%D9%8A%D9%81%D8%A9.html"), "الخليفة")
        check("الخليفة results", khalifa.isNotEmpty(), "got ${khalifa.size}")
        val halef = Parsers.search(load("search_halef.html"), "halef")
        check("halef empty", halef.isEmpty(), "got ${halef.size}")
        val ask = Parsers.search(load("sq_ask.html"), "ask")
        check("ask empty", ask.isEmpty(), "got ${ask.size}")
    }

    println("== series detail ==")
    run {
        val s = Parsers.series(load("series1.html"), "https://www.qrmzi.tv/series/x/")
        check("title parsed", s.title.isNotBlank() && s.title != "بدون عنوان", s.title)
        check("synopsis", s.synopsis.length > 40, "len=${s.synopsis.length}")
        check("cover 1280", s.cover?.contains("Cover") == true || s.cover.orEmpty().isNotEmpty(),
            s.cover.orEmpty())
        check("41 episodes", s.episodes.size == 41, "got ${s.episodes.size}")
        check("episodes are episodes", s.episodes.all { it.kind == Kind.EPISODE })
        check("descending numbers", s.episodes.mapNotNull { it.number } ==
            s.episodes.mapNotNull { it.number }.sortedDescending())
        println("     title=${s.title} cover=${s.cover}")
        println("     first=${s.episodes.firstOrNull()?.number} last=${s.episodes.lastOrNull()?.number}")

        val big = Parsers.series(load("big_series.html"), "https://www.qrmzi.tv/series/big/")
        check("big series 37 cards", big.episodes.size == 37, "got ${big.episodes.size}")
        check("big series 191..155", big.episodes.mapNotNull { it.number } == (191 downTo 155).toList(),
            "range=${big.episodes.firstOrNull()?.number}..${big.episodes.lastOrNull()?.number}")
        check("end-of-season ribbon", big.episodes.any { it.ribbon == "نهاية الموسم" },
            "ribbons=${big.episodes.mapNotNull { it.ribbon }.distinct()}")
    }

    println("== movie detail ==")
    run {
        val m = Parsers.series(load("movie1.html"), "https://www.qrmzi.tv/movies/x/")
        check("movie title", m.title.isNotBlank(), m.title)
        check("movie cover", m.cover.orEmpty().isNotEmpty(), m.cover.orEmpty())
        check("movie has no episode grid", m.episodes.isEmpty(), "got ${m.episodes.size}")
        val player = Parsers.playerIframe(load("movie1.html"), "https://www.qrmzi.tv/movies/x/")
        check("movie player iframe", player?.startsWith("https://") == true, player.orEmpty())
    }

    println("== episode detail ==")
    run {
        val e = Parsers.episode(load("episode1.html"), "https://www.qrmzi.tv/episode/x/")
        check("title", e.title.isNotBlank(), e.title)
        check("player iframe", e.playerUrl?.contains("anaplayer") == true, e.playerUrl.orEmpty())
        check("episode desc", e.description.length > 20, "len=${e.description.length}")
        check("series synopsis", e.seriesSynopsis.length > 40, "len=${e.seriesSynopsis.length}")
        check("series link", e.seriesUrl?.contains("/series/") == true, e.seriesUrl.orEmpty())
        check("season list 41", e.episodes.size == 41, "got ${e.episodes.size}")
        check("related cards", e.related.isNotEmpty(), "got ${e.related.size}")
        check("cover 1280", e.cover.orEmpty().isNotEmpty(), e.cover.orEmpty())
        println("     title=${e.title}")
        println("     series=${e.seriesTitle} -> ${e.seriesUrl}")
        println("     player=${e.playerUrl}")
    }

    println("== episode adjacency ==")
    run {
        val e = Parsers.episode(load("episode1.html"), "https://www.qrmzi.tv/episode/x/")
        // simulate opening the 3rd card in the season list
        val target = e.episodes[2]
        val fake = e.copy(url = target.url)
        check("index found", fake.indexInSeason == 2, "idx=${fake.indexInSeason}")
        check("next is older episode", fake.nextInSeason?.url == e.episodes[3].url,
            "next=${fake.nextInSeason?.number}")
        check("prev is newer episode", fake.previousInSeason?.url == e.episodes[1].url,
            "prev=${fake.previousInSeason?.number}")
        val last = e.copy(url = e.episodes.last().url)
        check("no next at edge", last.nextInSeason == null)
        val first = e.copy(url = e.episodes.first().url)
        check("no prev at edge", first.previousInSeason == null)
    }

    println("== player chain fixtures ==")
    run {
        val ana = Parsers.playerIframe(load("ana2.html"), "https://w.anaplayer.online/albaplayer/x")
        check("anaplayer -> cdnplus", ana?.contains("cdnplus.sbs") == true, ana.orEmpty())
        val s1 = load("shooof.html")
        val shooof = Parsers.playerIframe(s1, "https://shooof.online/albaplayer/x")
        check("shooof -> next", shooof != null, shooof.orEmpty())
        val cdn = load("cdnplus.html")
        check("cdnplus page has jwplayer", cdn.contains("jwplayer") || cdn.contains("jwplayer.js"))
        check("no m3u8 in our parsing path", Parsers.playerIframe(cdn, "https://cdnplus.sbs/embed-x.html") == null)
    }

    println("== robustness ==")
    run {
        check("empty html home", Parsers.home("").isEmpty())
        check("garbage html home", Parsers.home("<html><body><p>nothing</p></body></html>").isEmpty())
        check("empty html series", Parsers.series("", "https://www.qrmzi.tv/series/x/").episodes.isEmpty())
        check("empty html episode", Parsers.episode("", "https://www.qrmzi.tv/episode/x/").playerUrl == null)
        val unquoted = """<div id="load-post"><article class="post"><div class="block-post">
            <a href="https://www.qrmzi.tv/episode/abc/"><div class="posterThumb"><img class="imgBg lazy"
            data-src=https://www.qrmzi.tv/img/x.jpg src="data:image/gif;base64,R0lGOD"></div>
            <div class="title">مسلسل تجربة الحلقة 7</div></a></div></article></div>"""
        val c = Parsers.home(unquoted)
        check("unquoted data-src", c.size == 1 && c[0].image == "https://www.qrmzi.tv/img/x.jpg",
            c.firstOrNull()?.image.orEmpty())
        check("number from title", c.firstOrNull()?.number == 7, "${c.firstOrNull()?.number}")
    }

    println("== resume arithmetic (Playback) ==")
    run {
        check("progress without duration", Playback.progress(120_000, 0) == 0f)
        check("progress half", Playback.progress(600_000, 1_200_000) == 0.5f,
            "${Playback.progress(600_000, 1_200_000)}")
        check("progress clamps", Playback.progress(1_500_000, 1_200_000) == 1f)
        check("not resumable before intro", !Playback.isResumable(10_000, 1_200_000))
        check("resumable after intro", Playback.isResumable(120_000, 1_200_000))
        check("no duration means no resume", !Playback.isResumable(120_000, 0))
        check("finished is not resumable", !Playback.isResumable(1_150_000, 1_200_000))
        check("store ignores warm-up", !Playback.shouldStore(2_000, 1_200_000))
        check("store ignores missing duration", !Playback.shouldStore(120_000, 0))
        check("store accepts a real position", Playback.shouldStore(120_000, 1_200_000))
        check("store ignores overflow", !Playback.shouldStore(2_000_000, 1_200_000))
        check("clock under an hour", Playback.clock(83_000) == "1:23", Playback.clock(83_000))
        check("clock with hours", Playback.clock(3_723_000) == "1:02:03", Playback.clock(3_723_000))
        check("clock never negative", Playback.clock(-5_000) == "0:00", Playback.clock(-5_000))
        check("query collapsed", Playback.normalizeQuery("  حب   الخليفة ") == "حب الخليفة",
            Playback.normalizeQuery("  حب   الخليفة "))
        check("blank query dropped", Playback.normalizeQuery("   ").isEmpty())
        check("search cap is small", Playback.MAX_SEARCHES in 1..20)
        check("resume threshold sane", Playback.MIN_RESUME_MS in 5_000..120_000)
    }

    println("== request filter ==")
    run {
        val defaults = Filter.build("", "", "")
        check("default blocks popunder host", Filter.match("pgo99.com", defaults) == "pgo99.com")
        check("subdomain of blocked host", Filter.match("ads.pgo99.com", defaults) == "pgo99.com")
        check("tracker blocked", Filter.match("www.google-analytics.com", defaults) != null)
        check("qrmzi never blocked", Filter.match("www.qrmzi.tv", defaults) == null)
        check("player cdn never blocked", Filter.match("cdnplus.sbs", defaults) == null)
        check("unrelated host allowed", Filter.match("example.com", defaults) == null)
        check("lookalike not matched", Filter.match("notpgo99.com", defaults) == null)
        check("suffix lookalike not matched", Filter.match("pgo99.com.evil.net", defaults) == null)
        // The two ad scripts injected into the cdnplus player page.
        check("tagivi blocked", Filter.match("tagivi.com", defaults) == "tagivi.com")
        check("girella blocked", Filter.match("ia.girellaharelip.com", defaults) == "girellaharelip.com")
        check("tagivi not essential", !Filter.essential("tagivi.com"))
        check("girella not essential", !Filter.essential("ia.girellaharelip.com"))
        check("trust no longer covers ad hosts", !Http.isTrustedHost("tagivi.com"))
        check("trust still covers qrmzi", Http.isTrustedHost("www.qrmzi.tv"))
        check("filter revision is set", Filter.REVISION >= 2)

        val userBlock = Filter.build("example.org", "", "")
        check("user block applies", Filter.match("ads.example.org", userBlock) == "example.org")
        val userAllow = Filter.build("", "pgo99.com", "")
        check("user allow beats block", Filter.match("pgo99.com", userAllow) == null)
        val essentialBlocked = Filter.build("cdnplus.sbs", "", "")
        check("essential beats user block", Filter.match("cdnplus.sbs", essentialBlocked) == null)
        check("file allow wins", Filter.match("pgo99.com", Filter.build("", "", "allow pgo99.com")) == null)
        check("file block applies", Filter.match("a.tracker.test", Filter.build("", "", "block tracker.test")) == "tracker.test")
        check("bare file domain blocks", Filter.match("tracker.test", Filter.build("", "", "tracker.test")) == "tracker.test")
        check("comment ignored", Filter.parseList("# note\nexample.com").size == 1)
        check("invalid domain dropped", Filter.normalize("not a domain!!") == null)
        check("scheme/www stripped", Filter.normalize("https://www.Example.com/x?q=1#f") == "example.com")
        check("wildcard stripped", Filter.normalize("*.ads.example.org") == "ads.example.org")
    }

    println("== navigation policy ==")
    run {
        // The player keeps its own pages and provider chain.
        check("qrmzi main frame", NavPolicy.mainFrameAllowed("https://www.qrmzi.tv/episode/x"))
        check("deep player page", NavPolicy.mainFrameAllowed("https://cdnplus.org/hlor7c9pkore.html"))
        check("provider wrapper", NavPolicy.mainFrameAllowed("https://w.anaplayer.online/e/1"))
        check("about:blank allowed", NavPolicy.mainFrameAllowed("about:blank"))
        // Alternate servers the page's own picker can frame.
        check("alternate player allowed", NavPolicy.mainFrameAllowed("https://w.shadwo.pro/albaplayer/-12/"))
        check("ok.ru player allowed", NavPolicy.mainFrameAllowed("https://ok.ru/videoembed/12"))

        // Ad pop-ups are cancelled.
        check("ad host blocked", !NavPolicy.mainFrameAllowed("https://tagivi.com/1/abc"))
        check("ad popunder blocked", !NavPolicy.mainFrameAllowed("https://pgo99.com/1/abc"))
        check("unknown host blocked", !NavPolicy.mainFrameAllowed("https://ads.example.net/x"))
        check("http blocked", !NavPolicy.mainFrameAllowed("http://cdnplus.org/x"))
        check("userinfo trick blocked", !NavPolicy.mainFrameAllowed("https://cdnplus.org@evil.net/x"))
        check("javascript url blocked", !NavPolicy.mainFrameAllowed("javascript:alert(1)"))

        // Only a deliberate press on a share link may leave the app.
        check("script popup not external", !NavPolicy.externalAllowed("https://youtu.be/x", hasGesture = false))
        check("share with gesture", NavPolicy.externalAllowed("https://youtu.be/x", hasGesture = true))
        check("share subdomain", NavPolicy.externalAllowed("https://m.facebook.com/x", true))
        check("unknown share blocked", !NavPolicy.externalAllowed("https://ads.example.net/x", true))
        check("share lookalike blocked", !NavPolicy.externalAllowed("https://youtube.com.evil.net/x", true))
        check("ad gesture still blocked", !NavPolicy.externalAllowed("https://tagivi.com/1/x", true))

        check("host parsing", NavPolicy.hostOf("HTTPS://WWW.QRMZI.TV/a?b#c") == "www.qrmzi.tv")
        check("host parsing strips port", NavPolicy.hostOf("https://cdnplus.org:8443/x") == "cdnplus.org")
        check("host parsing rejects junk", NavPolicy.hostOf("not a url") == null)
    }

    println("== diagnostics ==")
    run {
        Diag.clear()
        Diag.setVerbose(true)
        Diag.log("test", "hello")
        check("diag remembers a line", Diag.lines().size == 1)
        Diag.count("checks", 2)
        check("counter adds", Diag.counter("checks") == 2L)
        for (i in 0 until Diag.CAPACITY + 40) Diag.log("test", "line $i")
        check("diag ring is bounded", Diag.lines().size == Diag.CAPACITY, "${Diag.lines().size}")
        check("diag keeps newest", Diag.lines().last().contains("line ${Diag.CAPACITY + 39}"))
        val before = Diag.lines().size
        Diag.setVerbose(false)
        Diag.log("test", "quiet")
        check("verbose off drops lines", Diag.lines().size == before)
        Diag.important("test", "failure")
        check("important always logs", Diag.lines().last().contains("failure"))
        val safe = Diag.safeUrl("https://www.qrmzi.tv/watch/123?token=secret#frag")
        check("safeUrl drops query", safe == "https://www.qrmzi.tv/watch/123", safe)
        check("safeUrl leaks nothing", !safe.contains("secret") && !safe.contains("token"))
        val temp = java.io.File.createTempFile("qrmzi-diag", ".txt")
        check("export writes a file", Diag.export(temp) && temp.length() > 0)
        check("export contains counters", temp.readText().contains("checks=2"))
        temp.delete()
        Diag.clear()
        check("clear empties ring and counters", Diag.lines().isEmpty() && Diag.counter("checks") == 0L)
    }

    println("== retry policy ==")
    run {
        check("503 is transient", NetRules.shouldRetry(1, 503))
        check("429 is transient", NetRules.shouldRetry(1, 429))
        check("timeout is transient", NetRules.shouldRetry(1, -1))
        check("404 is final", !NetRules.shouldRetry(1, 404))
        check("attempts are capped", !NetRules.shouldRetry(NetRules.MAX_ATTEMPTS, 503))
        check("backoff grows", NetRules.backoffMs(1) <= NetRules.backoffMs(2))
        check("offline message detected",
            NetRules.isOffline(java.io.IOException("Unable to resolve host \"www.qrmzi.tv\"")))
    }

    println()
    println("checks=$checks failures=$failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
