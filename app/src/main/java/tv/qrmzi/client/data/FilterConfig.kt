package tv.qrmzi.client.data

import android.content.Context
import java.io.File

/**
 * Optional filter configuration file, editable on a PC and pushed with adb:
 *
 *   adb push qrmzi-filter.txt \
 *     /sdcard/Android/data/tv.qrmzi.client/files/qrmzi-filter.txt
 *
 * Every line is `block <domain>`, `allow <domain>` or a bare domain (block).
 * `#` starts a comment. The app reads the file whenever playback starts, so no
 * restart is needed. Essential player hosts still can never be blocked.
 */
object FilterConfig {

    const val FILE_NAME = "qrmzi-filter.txt"

    fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    fun read(context: Context): String =
        runCatching { file(context).readText(Charsets.UTF_8) }.getOrDefault("")

    /** Writes a commented starter file, without overwriting a real one. */
    fun ensureSample(context: Context): File? = runCatching {
        val target = file(context)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            target.writeText(SAMPLE, Charsets.UTF_8)
        }
        target
    }.getOrNull()

    fun ruleCount(context: Context): Int =
        Filter.parseConfig(read(context)).size

    private val SAMPLE = """
        # Qrmzi TV — optional filter rules
        # block example-ads.com
        # allow example-cdn.com
        # A bare domain is treated as "block".
        # Qrmzi, its player chain and JW Player assets are always allowed.
    """.trimIndent() + "\n"
}
