package tv.qrmzi.client.data

import java.util.Locale

/**
 * Playback/resume arithmetic shared by the store, the screens and the player.
 *
 * It is plain Kotlin with no Android imports so the exact thresholds can be
 * unit-tested on the JVM (`tools/parsertest/run.sh`) instead of guessed at.
 *
 * The resume feature is deliberately conservative: a position is only treated
 * as resumable when the player reported a real duration, the user watched past
 * the intro and the episode is not effectively finished. Anything else is
 * ignored — better no resume than a wrong resume.
 */
object Playback {

    /** Below this the episode is treated as "just started", not resumable. */
    const val MIN_RESUME_MS: Long = 30_000L

    /** A player state below this is not written at all (page still warming up). */
    const val MIN_STORE_MS: Long = 5_000L

    /** Progress at or above this fraction counts as finished. */
    const val FINISHED_FRACTION: Float = 0.95f

    /** Search history cap; keeps the database tiny on a 1 GB device. */
    const val MAX_SEARCHES: Int = 12

    /** Progress of [positionMs] inside [durationMs] as 0f..1f. */
    fun progress(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L || positionMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** True when the episode has effectively been watched to the end. */
    fun isFinished(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && progress(positionMs, durationMs) >= FINISHED_FRACTION

    /** True when "متابعة من …" should be offered for this position. */
    fun isResumable(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs >= MIN_RESUME_MS && !isFinished(positionMs, durationMs)

    /**
     * True when a state reported by the player is trustworthy enough to store:
     * a real duration, a position past the warm-up and no position beyond the
     * end (which some players report while switching sources).
     */
    fun shouldStore(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L &&
            positionMs >= MIN_STORE_MS &&
            positionMs <= durationMs + 5_000L

    /** `1:23` or `1:02:03` — Western digits, matching the rest of the UI. */
    fun clock(ms: Long): String {
        val total = (ms / 1_000L).coerceAtLeast(0L)
        val hours = total / 3_600L
        val minutes = (total % 3_600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * Search queries are stored collapsed and trimmed so that "  حب  " and
     * "حب" are the same history entry. An empty result means "do not store".
     */
    fun normalizeQuery(raw: String): String =
        raw.trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private val WHITESPACE = Regex("\\s+")
}
