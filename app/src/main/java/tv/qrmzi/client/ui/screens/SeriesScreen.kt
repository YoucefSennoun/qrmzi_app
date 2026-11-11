package tv.qrmzi.client.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.Kind
import tv.qrmzi.client.data.SeriesInfo
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.Scrim
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.Theme
import tv.qrmzi.client.ui.ThumbView
import tv.qrmzi.client.ui.Ui

/**
 * Series details (`/series/<slug>/`) and movie details (`/movies/<slug>/`).
 * Shows the original cover, the site's synopsis, the current season's
 * episodes and the primary actions.
 */
class SeriesScreen(host: Host, private val url: String) : Screen(host) {

    private lateinit var page: Scaffold
    private lateinit var cover: ThumbView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var storyView: TextView
    private lateinit var actions: LinearLayout
    private lateinit var episodesHolder: LinearLayout

    private var info: SeriesInfo? = null

    private val isMovie: Boolean get() = url.contains("/movies/")

    override fun build(): View {
        page = scaffold(topPaddingDp = 0)

        val header = FrameLayout(ctx).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        cover = ThumbView(ctx).apply {
            aspect = 16f / 9f
            cornerRadius = 0f
            showPlayGlyph = false
        }
        header.addView(
            cover,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        header.addView(
            Scrim(ctx),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), 0,
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 26)
            )
        }

        titleView = Ui.text(ctx, "", 34f, Theme.TEXT, maxLines = 2).apply {
            typeface = Theme.bold
            setLineSpacing(0f, 1.05f)
            setShadowLayer(Theme.dpf(ctx, 8f), 0f, Theme.dpf(ctx, 2f), 0xCC000000.toInt())
        }
        bottom.addView(titleView)

        metaView = Ui.text(ctx, "", Theme.SMALL, Theme.TEXT_DIM, maxLines = 1)
        bottom.addView(
            metaView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(ctx, 8) }
        )

        actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        bottom.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(ctx, 18) }
        )

        header.addView(
            bottom,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        page.column.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Theme.dp(ctx, 400)
            )
        )

        val storyBlock = block("القصة", "")
        storyView = storyBlock.getChildAt(1) as TextView
        page.column.addView(
            storyBlock,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        episodesHolder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        page.column.addView(
            episodesHolder,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return page.frame
    }

    override fun load() {
        loadInto(
            page.state,
            if (isMovie) "جارٍ تحميل الفيلم…" else "جارٍ تحميل المسلسل…",
            { repo.series(url) }
        ) { render(it) }
    }

    private fun render(series: SeriesInfo) {
        info = series
        titleView.text = series.title
        images.load(series.cover, cover, true)
        storyView.text = series.synopsis.ifBlank { "لا يوجد وصف متاح لهذا العمل على الموقع." }

        metaView.text = when {
            series.episodes.isNotEmpty() -> "مسلسل  •  ${series.episodes.size} حلقة في الموسم الحالي"
            isMovie -> "فيلم"
            else -> "مسلسل"
        }

        actions.removeAllViews()
        val play = Ui.actionButton(ctx, "شاهد الآن", icon = "▶")
        play.setOnClickListener {
            val latest = series.episodes.firstOrNull()
            if (latest != null) {
                host.play(latest.url, latest.title, url)
            } else {
                host.play(url, series.title, null)
            }
        }
        actions.addView(play)
        (play.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(ctx, 12)

        if (series.episodes.isNotEmpty()) {
            val all = Ui.actionButton(ctx, "كل الحلقات", accent = false)
            all.setOnClickListener { host.openEpisodes(url, series.title) }
            actions.addView(all)
            (all.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(ctx, 12)
        }

        val card = Card(
            url = url,
            title = series.title,
            image = series.cover,
            number = null,
            ribbon = null,
            kind = if (isMovie) Kind.MOVIE else Kind.SERIES
        )
        val favourite = Ui.actionButton(
            ctx,
            if (store.isFavorite(url)) "✓ في قائمتي" else "＋ قائمتي",
            accent = false
        )
        favourite.setOnClickListener {
            val added = store.toggleFavorite(card)
            favourite.text = if (added) "✓ في قائمتي" else "＋ قائمتي"
            host.toast(if (added) "أُضيف إلى المفضلة" else "أُزيل من المفضلة")
        }
        actions.addView(favourite)

        episodesHolder.removeAllViews()
        if (series.episodes.isNotEmpty()) {
            val carousel = newCarousel("الحلقات", portrait = false, cardWidthDp = 276)
            carousel.setMore("عرض الكل") { host.openEpisodes(url, series.title) }
            carousel.setCards(series.episodes, images)
            episodesHolder.addView(
                carousel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        page.state.hide()
        focusInitial(play)
    }

    /** A padded title + paragraph block. */
    private fun block(title: String, body: String): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 16),
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 10)
            )
        }
        box.addView(
            Ui.text(ctx, title, Theme.H3, Theme.TEXT, maxLines = 1).apply {
                typeface = Theme.medium
            }
        )
        box.addView(
            Ui.text(ctx, body, Theme.BODY, Theme.TEXT_DIM, maxLines = 8),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(ctx, 8) }
        )
        return box
    }
}
