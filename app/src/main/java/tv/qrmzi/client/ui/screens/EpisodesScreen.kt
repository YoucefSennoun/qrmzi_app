package tv.qrmzi.client.ui.screens

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.ui.CardGrid
import tv.qrmzi.client.ui.Focus
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.Theme

/** Full episode list of one series (the current season published by the site). */
class EpisodesScreen(
    host: Host,
    private val seriesUrl: String,
    private val seriesTitle: String
) : Screen(host) {

    private lateinit var page: Scaffold
    private lateinit var grid: CardGrid
    private lateinit var subtitle: TextView

    override fun build(): View {
        page = scaffold(topPaddingDp = 100)

        val header = pageHeader(seriesTitle, "كل الحلقات")
        subtitle = header.getChildAt(1) as TextView
        page.column.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        grid = newGrid(portrait = false, cardWidthDp = 276)
        grid.onCardClick = { card -> host.openEpisode(card.url, seriesUrl, card.title) }
        page.column.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        return page.frame
    }

    override fun load() {
        loadInto(page.state, "جارٍ تحميل الحلقات…", { repo.series(seriesUrl) }) { info ->
            if (info.episodes.isEmpty()) {
                page.state.empty(
                    "لا توجد حلقات منشورة لهذا المسلسل بعد.\nعد لاحقًا بعد صدور حلقة جديدة.",
                    "إعادة المحاولة"
                ) { load() }
                return@loadInto
            }
            subtitle.text = "${info.episodes.size} حلقة"
            grid.setCards(info.episodes, images)
            page.state.hide()
            grid.post { if (grid.isAttachedToWindow) Focus.focusIn(grid) }
        }
    }
}
