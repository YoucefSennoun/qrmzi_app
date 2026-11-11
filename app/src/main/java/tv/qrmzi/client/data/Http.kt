package tv.qrmzi.client.data

import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Tiny HTTP client on HttpURLConnection (no OkHttp dependency: this device
 * only has ~1 GB of RAM and the app needs a handful of GET requests).
 *
 * Two concessions to the box's slow Wi-Fi: one retry for transient failures
 * (never for 4xx) and a hard body-size cap so a broken/hostile response cannot
 * exhaust the heap.
 */
object Http {

    const val UA =
        "Mozilla/5.0 (Linux; Android 10; TX1-S Build/QT) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private const val CONNECT_TIMEOUT = 12_000
    private const val READ_TIMEOUT = 20_000
    private const val MAX_REDIRECTS = 6
    private const val MAX_TEXT_CHARS = 4 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    class HttpException(val code: Int, message: String) : IOException(message)

    fun get(url: String, referer: String? = null): String {
        var attempt = 1
        var last: IOException? = null
        while (true) {
            try {
                val response = request(url, referer)
                Diag.count("http.ok")
                return response.text
            } catch (e: HttpException) {
                last = e
                Diag.count("http.fail")
                if (!NetRules.shouldRetry(attempt, e.code)) break
            } catch (e: IOException) {
                last = e
                Diag.count("http.fail")
                if (!NetRules.shouldRetry(attempt, -1)) break
            }
            Diag.count("http.retry")
            try {
                Thread.sleep(NetRules.backoffMs(attempt))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            attempt++
        }
        // Host + path only: a query string can contain a token.
        val error = last
        val detail = when (error) {
            is HttpException -> "HTTP ${error.code}"
            else -> error?.javaClass?.simpleName ?: "error"
        }
        Diag.important("http", "GET ${Diag.safeUrl(url)} failed: $detail")
        throw last ?: IOException("request failed")
    }

    fun getOrNull(url: String, referer: String? = null): String? =
        try {
            get(url, referer)
        } catch (e: IOException) {
            null
        }

    class Response(val text: String, val finalUrl: String, val contentLength: Int)

    fun request(url: String, referer: String? = null): Response {
        var current = url
        var redirects = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = false
                requestMethod = "GET"
                useCaches = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
                setRequestProperty("Accept-Language", "ar,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Accept-Encoding", "gzip")
                if (referer != null) setRequestProperty("Referer", referer)
                setRequestProperty("Connection", "keep-alive")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw HttpException(code, "HTTP $code without Location")
                    conn.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw HttpException(code, "too many redirects")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) throw HttpException(code, "HTTP $code")

                val encoding = conn.contentEncoding.orEmpty()
                val raw = conn.inputStream
                val stream = if (encoding.contains("gzip", ignoreCase = true)) {
                    GZIPInputStream(raw, 8192)
                } else {
                    raw
                }
                val charset = charsetOf(conn.contentType)
                val body = readText(stream, charset)
                return Response(body, current, body.length)
            } catch (e: javax.net.ssl.SSLException) {
                throw HttpException(-1, "TLS error: ${e.message}")
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Raw bytes, used by the image loader. One retry, hard size cap. */
    fun bytes(url: String, referer: String? = null): ByteArray {
        var attempt = 1
        var last: IOException? = null
        while (true) {
            try {
                return bytesOnce(url, referer)
            } catch (e: HttpException) {
                last = e
                if (!NetRules.shouldRetry(attempt, e.code)) break
            } catch (e: IOException) {
                last = e
                if (!NetRules.shouldRetry(attempt, -1)) break
            }
            try {
                Thread.sleep(NetRules.backoffMs(attempt))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            attempt++
        }
        throw last ?: IOException("image request failed")
    }

    private fun bytesOnce(url: String, referer: String?): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
            setRequestProperty("Referer", referer ?: "https://www.qrmzi.tv/")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw HttpException(code, "HTTP $code for image")
            return conn.inputStream.use { readBytes(it, MAX_IMAGE_BYTES) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readText(stream: InputStream, charset: java.nio.charset.Charset): String {
        InputStreamReader(stream, charset).use { reader ->
            val out = StringBuilder(16 * 1024)
            val buffer = CharArray(16 * 1024)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                if (out.length + read > MAX_TEXT_CHARS) {
                    throw HttpException(-2, "response too large")
                }
                out.append(buffer, 0, read)
            }
            return out.toString()
        }
    }

    private fun readBytes(stream: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(32 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw HttpException(-2, "image too large")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String?): java.nio.charset.Charset {
        if (contentType != null) {
            val m = Regex("charset=([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(contentType)
            if (m != null) {
                try {
                    return java.nio.charset.Charset.forName(m.groupValues[1])
                } catch (_: Exception) {
                }
            }
        }
        return Charsets.UTF_8
    }

    /**
     * Only these hosts are ever fetched or embedded by the app.
     *
     * `tagivi.com` and `ia.girellaharelip.com` used to be listed here because
     * they are part of the cdnplus embed page. They are not part of the player:
     * both are ad networks whose scripts were injected into that page, so they
     * are now blocked (see `Filter.DEFAULT_BLOCK`).
     */
    fun isTrustedHost(host: String): Boolean {
        val h = host.lowercase()
        return h == "www.qrmzi.tv" || h == "qrmzi.tv" ||
            h == "w.anaplayer.online" || h == "shooof.online" ||
            h == "cdnplus.sbs" || h == "cdnplus.org" ||
            h.endsWith(".brqz.online")
    }
}
