package tv.qrmzi.client.data

/**
 * Navigation policy for the player WebView — the fix for the pop-up ads.
 *
 * The provider page carries ad scripts (`pgo99.com`, `tagivi.com`,
 * `girellaharelip.com`) that try to move the browser to an advertising page,
 * either with `location.href = …` or with `window.open(…, "_blank")`. WebView
 * reports those as a normal main-frame navigation, so the old client forwarded
 * every untrusted URL to the system browser — which is exactly the pop-up the
 * user saw (a new Chrome page for every ad).
 *
 * Policy:
 *  * the app keeps the main frame on Qrmzi and on the provider's own player
 *    chain, including the alternate servers the page can pick from;
 *  * every other main-frame navigation is cancelled. Nothing is opened in
 *    another app by the page itself;
 *  * a *deliberate* remote/pointer press on a social/share link is the one
 *    exception, and only for a handful of explicit hosts.
 *
 * Pure Kotlin (no Android imports) so the JVM suite can cover it.
 */
object NavPolicy {

    /**
     * Alternate player servers that `core.js` can put in the player iframe
     * (captured from the live site). Allowing them keeps the provider's own
     * server picker working; none of them is an ad network.
     */
    val PLAYER_HOSTS: List<String> = listOf(
        "shadwo.pro",
        "arabveturk.com",
        "youdboox.com",
        "extreamnow.org",
        "ok.ru",
        "redload.co",
        "embedo.co",
        "dailymotion.com"
    )

    /**
     * Hosts a user gesture may hand to an external browser. Social/share links
     * are the only links on the site that are meant to leave the app.
     */
    val SHARE_HOSTS: List<String> = listOf(
        "youtube.com",
        "youtu.be",
        "facebook.com",
        "fb.watch",
        "instagram.com",
        "twitter.com",
        "x.com",
        "t.me",
        "telegram.me",
        "wa.me",
        "whatsapp.com"
    )

    /** Longest URL the policy will even look at (a hostile link, not a page). */
    private const val MAX_URL = 8192

    private val HOST = Regex(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$"
    )

    /**
     * True when the player WebView may keep [url] in its main frame: `about:`
     * URLs, Qrmzi, the provider chain, and the known alternate player servers.
     */
    fun mainFrameAllowed(url: String): Boolean {
        val t = url.trim()
        if (t == "about:blank" || t.startsWith("about:")) return true
        val host = hostOf(t) ?: return false
        if (Http.isTrustedHost(host)) return true
        return PLAYER_HOSTS.any { Filter.matches(host, it) }
    }

    /**
     * True only for a user-gesture navigation to a known share host. Pop-ups
     * triggered by a script (`hasGesture == false`) are never opened, and
     * neither is an unknown host, so a "share" button cannot become an ad.
     */
    fun externalAllowed(url: String, hasGesture: Boolean): Boolean {
        if (!hasGesture) return false
        val host = hostOf(url) ?: return false
        return SHARE_HOSTS.any { Filter.matches(host, it) }
    }

    /**
     * Lowercased host of an `http(s)` URL, or null. Strips user info, port,
     * path and fragment, so `https://cdnplus.org@evil.net/x` yields `evil.net`
     * rather than the trusted-looking part before the `@`.
     */
    fun hostOf(url: String): String? {
        val t = url.trim()
        if (t.isEmpty() || t.length > MAX_URL) return null
        val afterScheme = when {
            t.startsWith("https://", ignoreCase = true) -> t.substring(8)
            t.startsWith("http://", ignoreCase = true) -> t.substring(7)
            else -> return null
        }
        var authority = afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        val at = authority.lastIndexOf('@')
        if (at >= 0) authority = authority.substring(at + 1)
        var host = authority.substringBefore(':').trimEnd('.').lowercase()
        if (host.startsWith("[")) return null // IPv6 literals are never Qrmzi
        if (host.isEmpty() || host.length > 253) return null
        if (!HOST.matches(host)) return null
        return host
    }
}
