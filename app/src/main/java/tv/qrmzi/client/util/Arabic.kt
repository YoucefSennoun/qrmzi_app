package tv.qrmzi.client.util

import java.util.Locale

/** Text helpers for Arabic content scraped from qrmzi.tv. */
object Arabic {

    private val WHITESPACE = Regex("[\\s\\u00A0\\u2007\\u202F\\u200B\\u200E\\u200F\\u2066\\u2067\\u2068\\u2069]+")

    /** Collapses whitespace and removes the invisible directional marks the site embeds. */
    fun String.clean(): String = WHITESPACE.replace(this, " ").trim()

    /** The site appends "قرمزي" to `a[title]` values; `div.title` does not have it. */
    fun String.stripBrand(): String {
        var s = clean()
        for (suffix in listOf("قرمزي", "موقع قرمزي")) {
            if (s.endsWith(suffix)) s = s.removeSuffix(suffix).trim()
        }
        return s.trim(' ', '-', '|', '،')
    }

    fun episodeNumberFromTitle(title: String): Int? =
        Regex("الحلقة\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull()

    /** Splits "مسلسل الخليفة الحلقة 41" into ("مسلسل الخليفة", 41). */
    fun splitEpisodeTitle(title: String): Pair<String, Int?> {
        val m = Regex("(.+?)\\s*الحلقة\\s*(\\d+)").find(title) ?: return title to null
        return m.groupValues[1].clean() to m.groupValues[2].toIntOrNull()
    }

    fun seasonOf(title: String): Int? =
        Regex("[Mm]\\s*([0-9]{1,2})|[Ss]eason\\s*([0-9]{1,2})|الموسم\\s*([0-9]{1,2})")
            .find(title)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.toIntOrNull()

    /** Latin digits as the site uses them; provided for optional cosmetics. */
    fun toArabicIndic(value: Int): String {
        val sb = StringBuilder()
        for (ch in value.toString()) {
            sb.append(if (ch in '0'..'9') ('\u0660' + (ch - '0')) else ch)
        }
        return sb.toString()
    }

    fun isProbablyArabic(text: String): Boolean =
        text.any { Character.UnicodeBlock.of(it) == Character.UnicodeBlock.ARABIC }

    fun durationLabel(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, totalSec % 60)
        else String.format(Locale.US, "%d:%02d", m, totalSec % 60)
    }
}
