package tv.qrmzi.client.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.EpisodeInfo
import tv.qrmzi.client.data.Kind
import tv.qrmzi.client.data.Playback
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.Scrim
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.Theme
import tv.qrmzi.client.ui.ThumbView
import tv.qrmzi.client.ui.Ui
import tv.qrmzi.client.util.Arabic

/**
 * Episode (or movie) details: artwork, description, the play action, the
 * previous/next episode of the season and related titles.
 */
class EpisodeScreen(
    host: Host,
    private val url: String,
    private val knownTitle: String?
) : Screen(host) {

    private lateinit var page: Scaffold
    private lateinit var cover: ThumbView
    private lateinit var titleView: TextView
    private lateinit var metaView: TextView
    private lateinit var actions: LinearLayout
    private lateinit var descriptionView: TextView
    private lateinit var navRow: LinearLayout
    private lateinit var episodesHolder: LinearLayout
    private lateinit var relatedHolder: LinearLayout

    private var info: EpisodeInfo? = null

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

        titleView = Ui.text(ctx, knownTitle.orEmpty(), 32f, Theme.TEXT, maxLines = 2).apply {
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
                Theme.dp(ctx, 380)
            )
        )

        val descriptionBlock = paragraphBlock("الوصف")
        descriptionView = descriptionBlock.getChildAt(1) as TextView
        page.column.addView(
            descriptionBlock,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        navRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 4),
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 12)
            )
            clipChildren = false
        }
        page.column.addView(
            navRow,
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

        relatedHolder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        page.column.addView(
            relatedHolder,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return page.frame
    }

    override fun load() {
        loadInto(page.state, "جارٍ تحميل الحلقة…", { repo.episode(url) }) { render(it) }
    }

    private fun render(episode: EpisodeInfo) {
        info = episode

        titleView.text = episode.title
        images.load(episode.cover, cover, true)

        val number = Arabic.episodeNumberFromTitle(episode.title)
        metaView.text = listOfNotNull(
            episode.seriesTitle,
            number?.let { "الحلقة $it" }
        ).joinToString("  •  ")

        descriptionView.text = episode.description
            .ifBlank { episode.seriesSynopsis }
            .ifBlank { "لا يوجد وصف متاح لهذه الحلقة على الموقع." }

        // ------------------------------------------------------------- actions
        actions.removeAllViews()
        val resume = store.resumeOf(episode.url)
        val play: TextView
        if (resume != null) {
            // A reliable position exists: resume is the primary action, and the
            // explicit "from the beginning" action stays one D-pad step away.
            play = Ui.actionButton(
                ctx,
                "متابعة من ${Playback.clock(resume.positionMs)}",
                icon = "▶"
            )
            play.setOnClickListener { host.play(episode.url, episode.title, episode.seriesUrl) }
            actions.addView(play)
            (play.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(ctx, 12)

            val restart = Ui.actionButton(ctx, "من البداية", accent = false)
            restart.setOnClickListener {
                host.play(episode.url, episode.title, episode.seriesUrl, 0L)
            }
            actions.addView(restart)
            (restart.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(ctx, 12)
        } else {
            play = Ui.actionButton(ctx, "شاهد الآن", icon = "▶")
            play.setOnClickListener { host.play(episode.url, episode.title, episode.seriesUrl) }
            actions.addView(play)
            (play.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(ctx, 12)
        }

        val seriesUrl = episode.seriesUrl
        if (!seriesUrl.isNullOrBlank()) {
            val seriesButton = Ui.actionButton(ctx, "المسلسل", accent = false)
            seriesButton.setOnClickListener { host.openSeries(seriesUrl) }
            actions.addView(seriesButton)
            (seriesButton.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(ctx, 12)
        }

        val card = Card(
            url = episode.url,
            title = episode.title,
            image = episode.cover,
            number = number,
            ribbon = null,
            kind = if (episode.episodes.isEmpty() && url.contains("/movies/")) Kind.MOVIE else Kind.EPISODE
        )
        val favourite = Ui.actionButton(
            ctx,
            if (store.isFavorite(episode.url)) "✓ في قائمتي" else "＋ قائمتي",
            accent = false
        )
        favourite.setOnClickListener {
            val added = store.toggleFavorite(card)
            favourite.text = if (added) "✓ في قائمتي" else "＋ قائمتي"
            host.toast(if (added) "أُضيف إلى المفضلة" else "أُزيل من المفضلة")
        }
        actions.addView(favourite)

        // ------------------------------------------------------- previous/next
        navRow.removeAllViews()
        val previous = episode.previousInSeason // newer episode, index - 1
        val next = episode.nextInSeason         // older episode, index + 1

        val previousButton = navButton(
            label = if (previous?.number != null) "السابقة · ${previous.number}" else "السابقة",
            enabled = previous != null
        ) {
            previous?.let { host.openEpisode(it.url, episode.seriesUrl, it.title) }
        }
        navRow.addView(previousButton)
        (previousButton.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(ctx, 12)

        val nextButton = navButton(
            label = if (next?.number != null) "التالية · ${next.number}" else "التالية",
            enabled = next != null
        ) {
            next?.let { host.openEpisode(it.url, episode.seriesUrl, it.title) }
        }
        navRow.addView(nextButton)

        // ------------------------------------------------------------ carousels
        episodesHolder.removeAllViews()
        if (episode.episodes.isNotEmpty()) {
            val carousel = newCarousel(
                "حلقات الموسم",
                portrait = false,
                cardWidthDp = 276
            )
            carousel.onCardClick = { item ->
                host.openEpisode(item.url, episode.seriesUrl, item.title)
            }
            if (!seriesUrl.isNullOrBlank()) {
                carousel.setMore("عرض الكل") {
                    host.openEpisodes(seriesUrl, episode.seriesTitle ?: episode.title)
                }
            }
            carousel.setCards(episode.episodes, images)
            episodesHolder.addView(
                carousel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        relatedHolder.removeAllViews()
        if (episode.related.isNotEmpty()) {
            val carousel = newCarousel("مسلسلات مشابهة", portrait = false, cardWidthDp = 276)
            carousel.setCards(episode.related, images)
            relatedHolder.addView(
                carousel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // Local watch history (no playback position: the player is remote).
        store.recordHistory(card, episode.seriesUrl)

        page.state.hide()
        focusInitial(play)
    }

    private fun navButton(label: String, enabled: Boolean, action: () -> Unit): TextView {
        val button = Ui.actionButton(ctx, label, accent = false)
        button.isEnabled = enabled
        button.isFocusable = enabled
        button.alpha = if (enabled) 1f else 0.35f
        button.setOnClickListener { if (enabled) action() }
        return button
    }

    private fun paragraphBlock(title: String): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 18),
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 6)
            )
        }
        box.addView(
            Ui.text(ctx, title, Theme.H3, Theme.TEXT, maxLines = 1).apply {
                typeface = Theme.medium
            }
        )
        box.addView(
            Ui.text(ctx, "", Theme.BODY, Theme.TEXT_DIM, maxLines = 14),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Theme.dp(ctx, 8) }
        )
        return box
    }
}
