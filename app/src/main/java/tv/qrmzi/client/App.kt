package tv.qrmzi.client

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import tv.qrmzi.client.data.Diag
import tv.qrmzi.client.data.Filter
import tv.qrmzi.client.data.Prefs
import tv.qrmzi.client.data.Repo
import tv.qrmzi.client.data.Store
import tv.qrmzi.client.ui.ImageLoader
import tv.qrmzi.client.ui.Theme

/**
 * Process-wide singletons. The app owns exactly one HTTP cache, one SQLite
 * helper and one image loader, which is what keeps the memory footprint low
 * enough for a 1 GB Android TV box.
 */
class App : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var store: Store
        private set
    lateinit var repo: Repo
        private set
    lateinit var images: ImageLoader
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = Store(this)
        repo = Repo(store)
        images = ImageLoader(this)
        Theme.animationsEnabled = !prefs.reducedMotion
        images.quality = prefs.imageQuality
        Diag.setVerbose(prefs.verboseLogging)
        Diag.sink = { line -> Log.d("QrmziTV", line) }
        // A new host list deserves one fresh chance. Without this, a filter that
        // auto-disabled itself on an older build stays off forever and every
        // later version looks like it "does nothing" about ads. The manual
        // switch (adFilterEnabled) is deliberately left alone.
        if (prefs.filterRev != Filter.REVISION) {
            if (prefs.adFilterAutoDisabled) {
                prefs.adFilterAutoDisabled = false
                Diag.count("filter.revived")
            }
            prefs.filterRev = Filter.REVISION
        }
        Diag.important("app", "started")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Diag.count("mem.trim")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val full = level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            images.trimMemory(full)
            repo.trim(full)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Diag.count("mem.low")
        images.trimMemory(true)
        repo.trim(true)
    }
}
