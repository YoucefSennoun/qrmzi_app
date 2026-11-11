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
 * "أحدث الحلقات": the site's paginated latest feed (50 cards per page).
 * One page is loaded at a time to keep memory flat on a 1 GB device.
 */
class LatestScreen(host: Host) : Screen(host) {

    private lateinit var page: Scaffold
    private lateinit var grid: CardGrid
    private lateinit var subtitle: TextView
    private lateinit var pagerHolder: LinearLayout

    private var currentPage = 1
    private var currentCards: List<Card> = emptyList()

    override fun build(): View {
        page = scaffold(topPaddingDp = 100)

        val header = pageHeader("أحدث الحلقات", "كل ما نُشر مؤخرًا على قرمزي")
        subtitle = header.getChildAt(1) as TextView
        page.column.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        grid = newGrid(portrait = true, cardWidthDp = 156)
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
            "جارٍ تحميل الصفحة $currentPage…",
            { repo.latestPage(currentPage) }
        ) { cards ->
            if (cards.isEmpty() && currentPage > 1) {
                currentPage--
                load()
                return@loadInto
            }
            currentCards = cards
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
                hasNext = currentCards.size >= 50,
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
            page.state.empty(
                "لا توجد حلقات في هذه الصفحة.\nعد إلى الصفحة السابقة.",
                "الصفحة السابقة"
            ) {
                currentPage = maxOf(1, currentPage - 1)
                load()
            }
            return
        }

        page.state.hide()
        grid.post { if (grid.isAttachedToWindow) Focus.focusIn(grid) }
    }
}
