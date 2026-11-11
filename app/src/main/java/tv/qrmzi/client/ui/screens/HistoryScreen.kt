package tv.qrmzi.client.ui.screens

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.HistoryEntry
import tv.qrmzi.client.ui.CardGrid
import tv.qrmzi.client.ui.Focus
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.Theme
import tv.qrmzi.client.ui.Ui

/**
 * "السجل": what the user opened recently, with a progress bar for entries the
 * player reported a position for. Long-press deletes a single entry.
 */
class HistoryScreen(host: Host) : Screen(host) {

    private lateinit var page: Scaffold
    private lateinit var grid: CardGrid
    private lateinit var subtitle: TextView
    private lateinit var clearButton: TextView

    override fun build(): View {
        page = scaffold(topPaddingDp = 100)

        val header = pageHeader("السجل", "آخر ما شاهدته على هذا الجهاز")
        subtitle = header.getChildAt(1) as TextView
        page.column.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val tools = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), 0,
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 14)
            )
        }
        clearButton = Ui.actionButton(ctx, "مسح السجل", accent = false)
        clearButton.setOnClickListener {
            store.clearHistory()
            host.toast("تم مسح السجل")
            render()
        }
        tools.addView(
            clearButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        page.column.addView(
            tools,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        grid = newGrid(portrait = false, cardWidthDp = 276) { card ->
            store.removeHistory(card.url)
            host.toast("أُزيل «${card.title}» من السجل")
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
        if (grid.childCount > 0 || store.countHistory() > 0) render()
    }

    private fun render() {
        val entries: List<HistoryEntry> = store.history(60)
        subtitle.text = if (entries.isEmpty()) {
            "لم تشاهد شيئًا بعد"
        } else {
            "${entries.size} عنصرًا"
        }
        clearButton.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        if (entries.isEmpty()) {
            grid.setCards(emptyList(), images)
            page.state.empty(
                "لا يوجد سجل مشاهدة بعد.\nستجد هنا كل حلقة أو فيلم تفتحه من التطبيق.",
                "أحدث الحلقات"
            ) { host.section(tv.qrmzi.client.ui.TopBar.Section.LATEST) }
            return
        }

        val progress = entries.associate { entry ->
            entry.card.url to if (entry.durationMs > 0) {
                (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        grid.setCards(entries.map { it.card }, images) { card -> progress[card.url] ?: 0f }
        page.state.hide()
        grid.post { if (grid.isAttachedToWindow) Focus.focusIn(grid) }
    }
}
