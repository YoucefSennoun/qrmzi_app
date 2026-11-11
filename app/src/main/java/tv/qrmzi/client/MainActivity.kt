package tv.qrmzi.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.Toast
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.Kind
import tv.qrmzi.client.data.Repo
import tv.qrmzi.client.data.Store
import tv.qrmzi.client.data.Prefs
import tv.qrmzi.client.player.PlayerActivity
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.ImageLoader
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.Theme
import tv.qrmzi.client.ui.TopBar
import tv.qrmzi.client.ui.screens.CatalogueScreen
import tv.qrmzi.client.ui.screens.EpisodeScreen
import tv.qrmzi.client.ui.screens.EpisodesScreen
import tv.qrmzi.client.ui.screens.FavoritesScreen
import tv.qrmzi.client.ui.screens.HistoryScreen
import tv.qrmzi.client.ui.screens.HomeScreen
import tv.qrmzi.client.ui.screens.LatestScreen
import tv.qrmzi.client.ui.screens.SearchScreen
import tv.qrmzi.client.ui.screens.SeriesScreen
import tv.qrmzi.client.ui.screens.SettingsScreen

/**
 * The single activity of the app. It hosts a persistent top bar and a small
 * stack of screens (no Fragments, no Navigation component: one activity with
 * plain views is the cheapest option on a 1 GB device and keeps D-pad focus
 * restoration predictable).
 */
class MainActivity : Activity(), Host {

    private lateinit var content: FrameLayout
    private lateinit var topBar: TopBar
    private val stack = ArrayList<Screen>()
    private var section: TopBar.Section = TopBar.Section.HOME

    private val app: App get() = application as App

    override val ctx: Context get() = this
    override val repo: Repo get() = app.repo
    override val store: Store get() = app.store
    override val prefs: Prefs get() = app.prefs
    override val images: ImageLoader get() = app.images

    // ------------------------------------------------------------------ setup
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Theme.animationsEnabled = !prefs.reducedMotion
        images.quality = prefs.imageQuality
        Theme.immersive(this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Theme.BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        topBar = TopBar(this).apply { onSection = { switchTo(it) } }
        val barHolder = FrameLayout(this).apply {
            setPadding(0, Theme.dp(this@MainActivity, 12), 0, 0)
            isFocusable = false
        }
        barHolder.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(
            barHolder,
            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP)
        )

        setContentView(root)
        switchTo(TopBar.Section.HOME, force = true)
    }

    // --------------------------------------------------------------- sections
    private fun create(target: TopBar.Section): Screen = when (target) {
        TopBar.Section.HOME -> HomeScreen(this)
        TopBar.Section.LATEST -> LatestScreen(this)
        TopBar.Section.SERIES -> CatalogueScreen(this)
        TopBar.Section.SEARCH -> SearchScreen(this)
        TopBar.Section.FAVORITES -> FavoritesScreen(this)
        TopBar.Section.HISTORY -> HistoryScreen(this)
        TopBar.Section.SETTINGS -> SettingsScreen(this)
    }

    private fun switchTo(target: TopBar.Section, force: Boolean = false) {
        if (!force && target == section && stack.isNotEmpty()) {
            stack.last().restoreFocus()
            return
        }
        clearStack()
        section = target
        topBar.active = target
        push(create(target))
    }

    private fun clearStack() {
        content.removeAllViews()
        stack.forEach { it.destroy() }
        stack.clear()
    }

    private fun push(screen: Screen) {
        stack.lastOrNull()?.onHide()
        content.removeAllViews()
        content.addView(
            screen.view(),
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        )
        stack.add(screen)
        screen.ensureLoaded()
        screen.onShow()
        content.post {
            if (stack.lastOrNull() === screen) screen.restoreFocus()
        }
    }

    // ------------------------------------------------------------------- host
    override fun openCard(card: Card) {
        if (!isQrmziPage(card.url)) {
            toast("رابط غير معروف، تم تجاهله")
            return
        }
        when {
            card.url.contains("/movies/") -> openSeries(card.url)
            card.kind == Kind.SERIES || card.kind == Kind.MOVIE -> openSeries(card.url)
            else -> openEpisode(card.url, null, card.title)
        }
    }

    override fun openSeries(url: String) {
        if (!isQrmziPage(url)) {
            toast("رابط غير معروف، تم تجاهله")
            return
        }
        push(SeriesScreen(this, url))
    }

    override fun openEpisode(url: String, seriesUrl: String?, title: String) {
        if (!isQrmziPage(url)) {
            toast("رابط غير معروف، تم تجاهله")
            return
        }
        push(EpisodeScreen(this, url, title))
    }

    override fun openEpisodes(seriesUrl: String, title: String) {
        if (!isQrmziPage(seriesUrl)) {
            toast("رابط غير معروف، تم تجاهله")
            return
        }
        push(EpisodesScreen(this, seriesUrl, title))
    }

    override fun play(pageUrl: String, title: String, seriesUrl: String?, startMs: Long) {
        if (!isQrmziPage(pageUrl)) {
            toast("رابط غير معروف، تم تجاهله")
            return
        }
        // Remember what was opened (rich entries come from the detail screen and
        // keep their artwork + saved position).
        store.recordHistory(
            Card(pageUrl, title, null, null, null, Kind.EPISODE),
            seriesUrl
        )
        // -1 asks for the saved position; 0 forces playback from the start.
        val resumeAt = if (startMs >= 0L) startMs else store.resumePosition(pageUrl)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_PAGE, pageUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
            putExtra(PlayerActivity.EXTRA_START_MS, resumeAt)
            if (seriesUrl != null) putExtra(PlayerActivity.EXTRA_SERIES, seriesUrl)
        }
        startActivity(intent)
    }

    override fun section(section: TopBar.Section) {
        switchTo(section)
    }

    override fun goBack() {
        if (stack.size > 1) {
            val top = stack.removeAt(stack.size - 1)
            content.removeAllViews()
            top.destroy()
            val back = stack.last()
            content.addView(
                back.view(),
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )
            back.onShow()
            back.restoreFocus()
        } else {
            finish()
        }
    }

    override fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ input
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    switchTo(TopBar.Section.SETTINGS)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val top = stack.lastOrNull()
        if (top != null && top.onBack()) return
        goBack()
    }

    // -------------------------------------------------------------- lifecycle
    override fun onResume() {
        super.onResume()
        Theme.hideSystemUi(this)
        // Returning from the player (or from a system dialog) is the moment the
        // local rows can have changed: let the visible screen refresh itself,
        // then restore the remote where it was.
        stack.lastOrNull()?.let { screen ->
            screen.onShow()
            content.post { screen.restoreFocus() }
        }
    }

    override fun onDestroy() {
        clearStack()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ utils
    private fun isQrmziPage(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = runCatching { java.net.URL(url).host }.getOrNull() ?: return false
        return host.equals("www.qrmzi.tv", ignoreCase = true) ||
            host.equals("qrmzi.tv", ignoreCase = true)
    }
}
