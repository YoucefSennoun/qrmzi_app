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
 * "المفضلة": everything the user saved, newest first. The list lives in the
 * local database only (the site has no account API), and long-press removes an
 * entry so the remote never needs a context menu.
 */
class FavoritesScreen(host: Host) : Screen(host) {

    private lateinit var page: Scaffold
    private lateinit var grid: CardGrid
    private lateinit var subtitle: TextView

    override fun build(): View {
        page = scaffold(topPaddingDp = 100)

        val header = pageHeader("المفضلة", "قائمتك على هذا الجهاز")
        subtitle = header.getChildAt(1) as TextView
        page.column.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        grid = newGrid(portrait = true, cardWidthDp = 156) { card ->
            store.removeFavorite(card.url)
            host.toast("أُزيل «${card.title}» من المفضلة")
            render()
        }
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
        render()
    }

    override fun onShow() {
        // Favourites can be added from a detail page while this screen is alive.
        if (grid.childCount > 0 || store.countFavorites() > 0) render()
    }

    private fun render() {
        val cards: List<Card> = store.favorites()
        subtitle.text = when (cards.size) {
            0 -> "لا شيء محفوظ بعد"
            1 -> "عنصر واحد"
            2 -> "عنصران"
            in 3..10 -> "${cards.size} عناصر"
            else -> "${cards.size} عنصرًا"
        }

        if (cards.isEmpty()) {
            grid.setCards(emptyList(), images)
            page.state.empty(
                "قائمتك فارغة.\nافتح أي مسلسل أو حلقة ثم اضغط «＋ قائمتي».",
                "تصفّح المسلسلات"
            ) { host.section(tv.qrmzi.client.ui.TopBar.Section.SERIES) }
            return
        }

        grid.setCards(cards, images)
        page.state.hide()
        grid.post { if (grid.isAttachedToWindow) Focus.focusIn(grid) }
    }
}
