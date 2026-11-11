package tv.qrmzi.client.data

/**
 * Optional, conservative request filtering for the embedded player WebView.
 *
 * Rules:
 *  - only known advertising / analytics hosts are blocked by default;
 *  - every host the app itself trusts (Qrmzi, its player chain, JW Player's
 *    CDN, common JS CDNs) is *essential* and can never be blocked, even by a
 *    hand-edited config file;
 *  - user `allow` rules win over both the built-in list and user block rules;
 *  - blocking is off by default only if the user turns it off — the switch in
 *    Settings is the single source of truth.
 *
 * This module is intentionally free of Android imports so the matching rules
 * are covered by the JVM test suite.
 */
object Filter {

    /**
     * Bumped whenever [DEFAULT_BLOCK]/[ESSENTIAL] change. `App` keeps the last
     * revision it ran with and clears a previous automatic disable once per
     * revision, so a filter that gave up on an older list is not inert forever.
     */
    const val REVISION = 2

    /**
     * Well-known advertising, popunder and tracking hosts only. Nothing here is
     * used by Qrmzi's pages or by the JW Player chain. The list deliberately
     * avoids broad hosts (google.com, facebook.com, cloudflare.com, …) so a
     * mistake cannot take playback down.
     */
    val DEFAULT_BLOCK: List<String> = listOf(
        // popunders / push / adult ad networks seen on embed pages
        "pgo99.com",
        // scripts injected into the cdnplus player page itself; both were
        // captured from a live embed and only serve ads/popups there
        "tagivi.com",
        "girellaharelip.com",
        "cdn.foxpush.net",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "onclickads.net",
        "adsterra.com",
        "exoclick.com",
        "hilltopads.net",
        "juicyads.com",
        "trafficjunky.net",
        "clickadu.com",
        "adcash.com",
        "zedo.com",
        "adskeeper.com",
        "adskeeper.co.uk",
        "revcontent.com",
        "mgid.com",
        // display / programmatic
        "doubleclick.net",
        "2mdn.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "googletagservices.com",
        "adnxs.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "criteo.com",
        "criteo.net",
        "smartadserver.com",
        "serving-sys.com",
        "amazon-adsystem.com",
        "media.net",
        "33across.com",
        "sharethrough.com",
        "teads.tv",
        "undertone.com",
        // analytics / tracking
        "googletagmanager.com",
        "google-analytics.com",
        "scorecardresearch.com",
        "quantserve.com",
        "moatads.com",
        "adsafeprotected.com",
        "hotjar.com",
        "mixpanel.com",
        "segment.com",
        "amplitude.com",
        "clarity.ms",
        "histats.com",
        "chartbeat.com",
        "parsely.com",
        "connect.facebook.net",
        "taboola.com",
        "outbrain.com"
    )

    /**
     * Hosts that must keep working: Qrmzi itself, the provider chain, the JW
     * Player assets and the JS CDNs an embed may legitimately load.
     */
    val ESSENTIAL: List<String> = listOf(
        "qrmzi.tv",
        "anaplayer.online",
        "shooof.online",
        "cdnplus.sbs",
        "cdnplus.org",
        "brqz.online",
        "jwplayer.com",
        "jwpcdn.com",
        "jwplatform.com",
        "jwpsrv.com",
        "jwplayer.org",
        "jsdelivr.net",
        "cdnjs.cloudflare.com",
        "bootstrapcdn.com",
        "googleapis.com",
        "gstatic.com",
        "jquery.com",
        "fontawesome.com",
        "cloudflareinsights.com"
    )

    private val SPLIT = Regex("[\\s,;]+")
    private val DOMAIN = Regex(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$"
    )

    /** A resolved rule set: block hosts and explicit allow hosts. */
    data class Config(
        val blocks: List<String> = emptyList(),
        val allows: List<String> = emptyList()
    ) {
        val hasBlocks: Boolean get() = blocks.isNotEmpty()
        val blockCount: Int get() = blocks.size
        val allowCount: Int get() = allows.size
    }

    data class Rule(val domain: String, val allow: Boolean)

    /**
     * Combines the built-in list, the two user lists and an optional config
     * file. Later sources never override the essential whitelist.
     */
    fun build(userBlock: String, userAllow: String, fileText: String = ""): Config {
        val blocked = LinkedHashSet<String>()
        val allowed = LinkedHashSet<String>()

        DEFAULT_BLOCK.mapNotNullTo(blocked) { normalize(it) }
        parseList(userBlock).mapNotNullTo(blocked) { normalize(it) }
        parseList(userAllow).mapNotNullTo(allowed) { normalize(it) }
        parseConfig(fileText).forEach { rule ->
            val domain = normalize(rule.domain) ?: return@forEach
            if (rule.allow) allowed.add(domain) else blocked.add(domain)
        }

        // An explicit allow is stronger than a block, so drop duplicates.
        blocked.removeAll(allowed)
        return Config(blocked.toList(), allowed.toList())
    }

    /**
     * Returns the blocked domain when [host] should be refused, or `null`.
     * Trusted/essential hosts and explicit allow rules always win.
     */
    fun match(host: String, config: Config): String? {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return null
        if (essential(h)) return null
        if (config.allows.any { matches(h, it) }) return null
        return config.blocks.firstOrNull { matches(h, it) }
    }

    fun essential(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (Http.isTrustedHost(h)) return true
        return ESSENTIAL.any { matches(h, it) }
    }

    /** `example.com` matches itself and every subdomain, never a lookalike. */
    fun matches(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /** Parses a free-form multi-line list into raw (not yet normalized) entries. */
    fun parseList(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.lineSequence()
            .map { line -> line.substringBefore('#') }
            .flatMap { line -> line.split(SPLIT) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /**
     * Parses a config file where every non-empty line is either
     * `allow <domain>` or `block <domain>` (a bare domain means block).
     * `#` starts a comment. Invalid domains are ignored, never fatal.
     */
    fun parseConfig(text: String): List<Rule> {
        if (text.isBlank()) return emptyList()
        val rules = ArrayList<Rule>()
        text.lineSequence().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(SPLIT, limit = 2)
            val head = parts[0].lowercase()
            val rule = when (head) {
                "allow", "permit", "whitelist" -> Rule(parts.getOrElse(1) { "" }.trim(), true)
                "block", "deny", "blacklist" -> Rule(parts.getOrElse(1) { "" }.trim(), false)
                else -> Rule(line, false)
            }
            if (rule.domain.isNotEmpty()) rules.add(rule)
        }
        return rules
    }

    /**
     * Cleans one user entry: trims, lowercases, drops scheme/port/path/query,
     * strips `www.`/`*.` and validates the remaining domain. Returns null for
     * anything that is not a plausible hostname.
     */
    fun normalize(raw: String): String? {
        var value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        value = when {
            value.startsWith("https://") -> value.substring(8)
            value.startsWith("http://") -> value.substring(7)
            value.startsWith("//") -> value.substring(2)
            else -> value
        }
        value = value
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val at = value.lastIndexOf('@')
        if (at >= 0) value = value.substring(at + 1)
        value = value.substringBefore(':').trim('.')
        while (value.startsWith("*.")) value = value.substring(2)
        while (value.startsWith("www.")) value = value.substring(4)
        if (value.isEmpty() || value.length > 253) return null
        if (!value.contains('.')) return null
        if (!DOMAIN.matches(value)) return null
        return value
    }
}
