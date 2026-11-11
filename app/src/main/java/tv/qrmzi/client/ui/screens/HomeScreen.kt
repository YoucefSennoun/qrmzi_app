package tv.qrmzi.client.ui.screens

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.ui.Carousel
import tv.qrmzi.client.ui.Focus
import tv.qrmzi.client.ui.Hero
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.StateView
import tv.qrmzi.client.ui.Theme

/**
 * Home: a Netflix-style billboard followed by rows of real content from
 * qrmzi.tv. Rows are grouped from the site's own feed (latest, last-episode
 * ribbons, delayed episodes) plus the user's local history and favourites.
 */
class HomeScreen(host: Host) : Screen(host) {

    private lateinit var column: LinearLayout
    private lateinit var state: StateView
    private var hero: Hero? = null

    private var cached: List<Card> = emptyList()
    private var lastLocalSignature: String = ""

    /**
     * Coming back from the player must show the new continue-watching progress
     * without re-fetching the whole feed. The local rows are rebuilt only when
     * their contents actually changed.
     */
    override fun onShow() {
        if (cached.isEmpty()) return
        if (localSignature() == lastLocalSignature) return
        render(cached, keepFocus = true)
    }

    /** Cheap fingerprint of the only rows that change while the app is open. */
    private fun localSignature(): String =
        store.continueWatching(14).joinToString("|") { "${it.card.url}@${it.positionMs}/${it.durationMs}" } +
            "#" + store.favorites().joinToString(",") { it.url }

    override fun build(): View {
        val frame = FrameLayout(ctx).apply { setBackgroundColor(Theme.BG) }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, 0, 0, Theme.dp(ctx, 48))
        }
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        frame.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        state = StateView(ctx)
        frame.addView(
            state,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return frame
    }

    override fun load() {
        loadInto(state, "جارٍ تحميل أحدث الحلقات…", { repo.home() }) { cards -> render(cards) }
    }

    private fun render(cards: List<Card>, keepFocus: Boolean = false) {
        cached = cards
        lastLocalSignature = localSignature()
        column.removeAllViews()

        if (cards.isEmpty()) {
            state.empty(
                "لم نجد حلقات على الصفحة الرئيسية الآن.\nقد يكون الموقع مشغولًا، أعد المحاولة بعد قليل.",
                "إعادة المحاولة"
            ) { load() }
            return
        }

        val featured = cards.firstOrNull { it.image != null } ?: cards.first()
        val billboard = Hero(ctx)
        billboard.bind(
            featured,
            images,
            { card -> host.play(card.url, card.title, null) },
            { card -> host.openCard(card) }
        )
        column.addView(
            billboard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Theme.dp(ctx, 430)
            )
        )
        hero = billboard

        val resume = store.continueWatching(14)
        if (resume.isNotEmpty()) {
            val entries = resume.associateBy { it.card.url }
            addRow(
                "متابعة المشاهدة",
                resume.map { it.card },
                onOpen = { card -> host.play(card.url, card.title, entries[card.url]?.seriesUrl) }
            ) { card -> entries[card.url]?.progress ?: 0f }
        }

        addRow("أحدث الحلقات", cards.take(20))

        val finals = cards.filter { it.ribbon == "الحلقة الأخيرة" }
        if (finals.size >= 2) addRow("الحلقة الأخيرة", finals)

        val more = cards.drop(20)
        if (more.isNotEmpty()) addRow("المزيد على قرمزي", more)

        val soon = cards.filter { it.ribbon == "مؤجلة" || it.ribbon == "نهاية الموسم" }
        if (soon.size >= 2) addRow("قريبًا", soon)

        val favourites = store.favorites().take(20)
        if (favourites.isNotEmpty()) addRow("قائمتك", favourites)

        column.addView(
            tv.qrmzi.client.ui.Ui.text(
                ctx,
                "المحتوى من موقع قرمزي — qrmzi.tv",
                Theme.TINY,
                Theme.TEXT_FAINT
            ).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(
                    Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 26),
                    Theme.dp(ctx, Theme.SCREEN_HPAD), 0
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        state.hide()
        if (keepFocus) {
            root.post { restoreFocus() }
        } else {
            billboard.requestInitialFocus()
        }
    }

    private fun addRow(
        title: String,
        cards: List<Card>,
        onOpen: ((Card) -> Unit)? = null,
        progress: (Card) -> Float = { 0f }
    ) {
        if (cards.isEmpty()) return
        val carousel: Carousel = newCarousel(title, portrait = true, cardWidthDp = 156)
        if (onOpen != null) carousel.onCardClick = onOpen
        carousel.setCards(cards, images, progress)
        column.addView(
            carousel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        column.addView(
            View(ctx),
            LinearLayout.LayoutParams(1, Theme.dp(ctx, 10))
        )
    }
}
