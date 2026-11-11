package tv.qrmzi.client.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import tv.qrmzi.client.data.Diag
import tv.qrmzi.client.data.Http
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Purpose-built thumbnail loader.
 *
 * Coil/Glide would add megabytes of dependencies and their own threads; the
 * target box has 1 GB of RAM, so we use one small LRU bitmap cache, a 3-thread
 * pool, RGB_565 decoding for cards and a bounded disk cache.
 */
class ImageLoader(context: Context) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val diskDir = File(app.cacheDir, "img").apply { mkdirs() }
    private val executor = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "img").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private val maxKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    private val memory = object : LruCache<String, Bitmap>(minOf(14 * 1024, maxKb / 8)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val inFlight = HashMap<String, MutableList<ThumbView>>()

    var quality: Int = 0

    fun load(url: String?, target: ThumbView, wide: Boolean = true) {
        if (url.isNullOrBlank()) {
            target.imageTag = null
            target.setBitmap(null)
            return
        }
        target.imageTag = url
        val cached = memory.get(url)
        if (cached != null && !cached.isRecycled) {
            Diag.count("img.hit")
            target.setBitmap(cached)
            return
        }
        Diag.count("img.miss")
        target.setBitmap(null)

        val startDownload: Boolean
        synchronized(inFlight) {
            val waiting = inFlight[url]
            if (waiting != null) {
                if (waiting.none { it === target }) waiting.add(target)
                startDownload = false
            } else {
                inFlight[url] = mutableListOf(target)
                startDownload = true
            }
        }
        if (!startDownload) return

        val targetWidth = if (quality >= 1) 720 else 480
        executor.execute {
            val bitmap = try {
                decode(url, targetWidth, wide)
            } catch (t: Throwable) {
                null
            }
            if (bitmap != null) Diag.count("img.loaded")
            main.post {
                val targets = synchronized(inFlight) { inFlight.remove(url) } ?: emptyList()
                for (view in targets) {
                    if (view.imageTag == url) view.setBitmap(bitmap)
                }
            }
        }
    }

    private fun decode(url: String, targetWidth: Int, wide: Boolean): Bitmap? {
        val key = sha1(url)
        val file = File(diskDir, key)
        val bytes: ByteArray = if (file.exists() && file.length() > 512) {
            Diag.count("img.disk.hit")
            file.readBytes()
        } else {
            val downloaded = Http.bytes(url) ?: return null
            if (downloaded.size in 513..(4 * 1024 * 1024)) {
                runCatching { file.writeBytes(downloaded) }
                trimDiskIfNeeded()
            }
            downloaded
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val height = if (wide) targetWidth * 9 / 16 else targetWidth * 3 / 2
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth || bounds.outHeight / (sample * 2) >= height) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (wide) Bitmap.Config.RGB_565 else Bitmap.Config.RGB_565
            inDither = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        memory.put(url, bitmap)
        return bitmap
    }

    private fun trimDiskIfNeeded() {
        executor.execute {
            val files = diskDir.listFiles() ?: return@execute
            var total = files.sumOf { it.length() }
            val limit = 32L * 1024 * 1024
            if (total <= limit) return@execute
            Diag.count("img.disk.trim")
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= limit * 3 / 4) return@forEach
                total -= f.length()
                f.delete()
            }
        }
    }

    fun prefetch(url: String?) {
        if (url.isNullOrBlank() || memory.get(url) != null) return
        executor.execute { runCatching { decode(url, 480, true) } }
    }

    /**
     * RUNNING_LOW trims half of the bitmap cache; COMPLETE/LOW clears it. The
     * visible cards keep their bitmaps because each ThumbView holds its own
     * reference, so the UI does not flash while the cache shrinks.
     */
    fun trimMemory(evictAll: Boolean) {
        if (evictAll) {
            memory.evictAll()
            Diag.count("img.mem.clear")
        } else {
            val before = memory.size()
            memory.trimToSize(memory.maxSize() / 2)
            if (memory.size() < before) Diag.count("img.mem.trim")
        }
    }

    fun clearDisk() {
        executor.execute {
            diskDir.listFiles()?.forEach { it.delete() }
        }
        memory.evictAll()
        Diag.count("img.disk.clear")
    }

    fun diskSizeBytes(): Long = diskDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Approximate bitmap-cache usage, shown in the diagnostics summary. */
    fun memoryUsageKb(): Int = memory.size()

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
