package tv.qrmzi.client.ui.screens

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.ui.CardGrid
import tv.qrmzi.client.ui.Focus
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.Theme

/**
 * The series catalogue (`/all-turkish-series/`): 40 landscape cards per page,
 * five pages at the time of writing.
 */
class CatalogueScreen(host: Host) : Screen(host) {

    private lateinit var page: Scaffold
    private lateinit var grid: CardGrid
    private lateinit var subtitle: TextView
    private lateinit var pagerHolder: LinearLayout

    private var currentPage = 1
    private var currentCards: List<Card> = emptyList()
    private var hasNext = false

    override fun build(): View {
        page = scaffold(topPaddingDp = 100)

        val header = pageHeader("المسلسلات", "الدليل الكامل لمسلسلات قرمزي")
        subtitle = header.getChildAt(1) as TextView
        page.column.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        grid = newGrid(portrait = false, cardWidthDp = 276)
        page.column.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        pagerHolder = LinearLayout(ctx)
        page.column.addView(
            pagerHolder,
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
            "جارٍ تحميل المسلسلات…",
            { repo.seriesCatalogue(currentPage) }
        ) { catalogue ->
            if (catalogue.cards.isEmpty() && currentPage > 1) {
                currentPage--
                load()
                return@loadInto
            }
            currentCards = catalogue.cards
            hasNext = catalogue.hasNext
            render()
        }
    }

    private fun render() {
        grid.setCards(currentCards, images)
        subtitle.text = "الصفحة $currentPage"

        pagerHolder.removeAllViews()
        pagerHolder.addView(
            pager(
                hasPrevious = currentPage > 1,
                hasNext = hasNext,
                onPrevious = {
                    currentPage--
                    load()
                },
                onNext = {
                    currentPage++
                    load()
                }
            )
        )

        if (currentCards.isEmpty()) {
            page.state.empty("تعذّر تحميل دليل المسلسلات.", "إعادة المحاولة") { load() }
            return
        }

        page.state.hide()
        grid.post { if (grid.isAttachedToWindow) Focus.focusIn(grid) }
    }
}
