package tv.qrmzi.client.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Bounded, process-wide diagnostics.
 *
 * The target box has 1 GB of RAM and no debugger attached, so the client keeps
 * a very small in-memory ring (160 lines) plus plain counters. Nothing here
 * touches the network, and URLs are reduced to `scheme://host/path` by
 * [safeUrl] so query strings and tokens never reach the log.
 *
 * `sink` is installed by [tv.qrmzi.client.App] to mirror lines to logcat; the
 * module itself has no Android imports so the JVM test suite can exercise it.
 */
object Diag {

    /** Maximum number of remembered lines. Keeps the footprint under ~20 KB. */
    const val CAPACITY = 160

    private val ring = ArrayDeque<String>()
    private val counters = HashMap<String, Long>()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var verbose: Boolean = false
        private set

    /** Optional mirror (logcat in the app, unused on the JVM). */
    @Volatile
    var sink: ((String) -> Unit)? = null

    fun setVerbose(on: Boolean) {
        verbose = on
    }

    /** Records a line when verbose logging is on. Counters are always cheap. */
    fun log(tag: String, message: String) {
        if (!verbose) return
        append("$tag: $message")
    }

    /** Records a line even when verbose logging is off (used for real failures). */
    fun important(tag: String, message: String) {
        append("$tag: $message")
    }

    private fun append(message: String) {
        val line = "${format.format(Date())} $message"
        synchronized(ring) {
            ring.addLast(line)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        runCatching { sink?.invoke(line) }
    }

    fun count(name: String, delta: Long = 1L): Long = synchronized(counters) {
        val next = (counters[name] ?: 0L) + delta
        counters[name] = next
        next
    }

    fun counter(name: String): Long = synchronized(counters) { counters[name] ?: 0L }

    fun lines(): List<String> = synchronized(ring) { ring.toList() }

    fun clear() {
        synchronized(ring) { ring.clear() }
        synchronized(counters) { counters.clear() }
    }

    /** Multi-line snapshot: counters first, then the most recent log lines. */
    fun snapshot(): String {
        val out = StringBuilder()
        out.append("Qrmzi TV diagnostics\n")
        out.append("verbose=").append(verbose).append('\n')
        synchronized(counters) {
            if (counters.isEmpty()) {
                out.append("(no counters)\n")
            } else {
                counters.entries.sortedBy { it.key }.forEach { (key, value) ->
                    out.append(key).append('=').append(value).append('\n')
                }
            }
        }
        val remembered = lines()
        out.append("-- log (").append(remembered.size).append(" lines) --\n")
        if (remembered.isEmpty()) out.append("(empty)\n")
        remembered.forEach { out.append(it).append('\n') }
        return out.toString()
    }

    /** Writes [snapshot] to [file]; returns false instead of throwing. */
    fun export(file: File): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.writeText(snapshot(), Charsets.UTF_8)
        true
    }.getOrDefault(false)

    /**
     * `scheme://host/path`, with the query and fragment removed and the path
     * truncated. Returns `-` for anything that cannot be parsed, so a broken
     * URL can never leak into a report.
     */
    fun safeUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        return try {
            val uri = java.net.URI(raw)
            val host = uri.host ?: return "-"
            val scheme = uri.scheme ?: "https"
            val path = uri.path.orEmpty().take(80)
            "$scheme://$host$path"
        } catch (e: Exception) {
            "-"
        }
    }

    /** Host only, lowercased, for counters and settings rows. */
    fun safeHost(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        return try {
            java.net.URI(raw).host?.lowercase() ?: "-"
        } catch (e: Exception) {
            "-"
        }
    }
}

/**
 * Retry policy for [Http]. Pure so it can be unit-tested without a network.
 *
 * Only clearly transient outcomes are retried: connect/TLS failures (negative
 * code), 408, 429 and 5xx. A 4xx is final: retrying a 403/404 would just waste
 * the box's slow link.
 */
object NetRules {

    const val MAX_ATTEMPTS = 2

    fun isTransientStatus(code: Int): Boolean =
        code <= 0 || code == 408 || code == 429 || code in 500..599

    /** [attempt] is 1-based. */
    fun shouldRetry(attempt: Int, code: Int): Boolean =
        attempt < MAX_ATTEMPTS && isTransientStatus(code)

    fun backoffMs(attempt: Int): Long =
        (300L * (1 shl (attempt - 1).coerceIn(0, 3))).coerceAtMost(2_000L)

    /** Tests the common "network just disappeared" failure. */
    fun isOffline(error: Throwable?): Boolean {
        if (error == null || error is Http.HttpException) return false
        val text = (error.message ?: "").lowercase()
        return text.contains("unable to resolve host") ||
            text.contains("network is unreachable") ||
            text.contains("no address associated") ||
            text.contains("enotfound") ||
            text.contains("econnrefused")
    }
}
