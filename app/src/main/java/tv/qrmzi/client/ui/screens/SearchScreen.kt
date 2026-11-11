package tv.qrmzi.client.ui.screens

import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import kotlinx.coroutines.CancellationException
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.Playback
import tv.qrmzi.client.ui.CardGrid
import tv.qrmzi.client.ui.Focus
import tv.qrmzi.client.ui.Host
import tv.qrmzi.client.ui.Screen
import tv.qrmzi.client.ui.StateView
import tv.qrmzi.client.ui.Theme
import tv.qrmzi.client.ui.Ui

/**
 * Search (`/?s=<query>`). The site has no autocomplete, so results are fetched
 * on submit (remote keyboard "بحث" key or the button).
 */
class SearchScreen(host: Host) : Screen(host) {

    private lateinit var field: EditText
    private lateinit var resultsColumn: LinearLayout
    private lateinit var state: StateView
    private lateinit var recentHolder: LinearLayout

    override fun build(): View {
        val root = FrameLayout(ctx).apply { setBackgroundColor(Theme.BG) }
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, Theme.dp(ctx, 96), 0, 0)
        }
        root.addView(
            outer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        outer.addView(
            pageHeader("البحث", "ابحث عن مسلسل أو فيلم على قرمزي"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 4),
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 12)
            )
        }

        field = EditText(ctx).apply {
            hint = "اسم المسلسل…"
            setSingleLine(true)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.TEXT_FAINT)
            typeface = Theme.regular
            background = Theme.outline(Theme.SURFACE_2, 12f, 1f, Theme.STROKE, ctx)
            setPadding(
                Theme.dp(ctx, 20), Theme.dp(ctx, 12),
                Theme.dp(ctx, 20), Theme.dp(ctx, 12)
            )
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isFocusable = true
            isFocusableInTouchMode = true
            setText(prefs.lastSearch)
            setSelection(text.length)
            setOnFocusChangeListener { v, focused ->
                v.background = if (focused) {
                    Theme.outline(Theme.SURFACE_3, 12f, 2f, Theme.FOCUS_RING, ctx)
                } else {
                    Theme.outline(Theme.SURFACE_2, 12f, 1f, Theme.STROKE, ctx)
                }
            }
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                    search(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        searchRow.addView(
            field,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val go = Ui.actionButton(ctx, "بحث")
        go.setOnClickListener { search(field.text.toString()) }
        searchRow.addView(
            go,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Theme.dp(ctx, 12) }
        )
        outer.addView(
            searchRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val suggestions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), 0,
                Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 14)
            )
        }
        for (query in SUGGESTIONS) {
            val chip = Ui.chip(ctx, query)
            chip.setOnClickListener {
                field.setText(query)
                field.setSelection(query.length)
                search(query)
            }
            suggestions.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Theme.dp(ctx, 10) }
            )
        }
        outer.addView(
            suggestions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Local search history (persisted; capped at Playback.MAX_SEARCHES).
        recentHolder = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        outer.addView(
            recentHolder,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val resultsFrame = FrameLayout(ctx)
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            isFillViewport = true
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        resultsColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(0, 0, 0, Theme.dp(ctx, 40))
        }
        scroll.addView(
            resultsColumn,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        resultsFrame.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        state = StateView(ctx)
        resultsFrame.addView(
            state,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        outer.addView(
            resultsFrame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        return root
    }

    override fun load() {
        // Nothing is fetched until the user submits a query.
        rebuildRecent()
        if (prefs.lastSearch.isNotBlank()) {
            search(prefs.lastSearch)
        } else {
            state.empty("اكتب اسم مسلسل في الأعلى ثم اضغط «بحث».\nيمكنك أيضًا تجربة إحدى الاقتراحات.")
        }
    }

    override fun onShow() {
        rebuildRecent()
    }

    /**
     * Recent queries, persisted locally. CENTER repeats a query; a long press
     * removes it; the last chip clears the whole list. No personal data beyond
     * the query text itself is stored.
     */
    private fun rebuildRecent() {
        if (!::recentHolder.isInitialized) return
        val queries = store.searches(Playback.MAX_SEARCHES)
        recentHolder.removeAllViews()
        if (queries.isEmpty()) {
            recentHolder.visibility = View.GONE
            return
        }
        recentHolder.visibility = View.VISIBLE
        recentHolder.addView(
            Ui.text(ctx, "آخر ما بحثت عنه", Theme.TINY, Theme.TEXT_FAINT, maxLines = 1).apply {
                setPadding(
                    Theme.dp(ctx, Theme.SCREEN_HPAD), 0,
                    Theme.dp(ctx, Theme.SCREEN_HPAD), Theme.dp(ctx, 6)
                )
            }
        )

        val scroller = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(0, 0, 0, Theme.dp(ctx, 12))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                Theme.dp(ctx, Theme.SCREEN_HPAD), 0,
                Theme.dp(ctx, Theme.SCREEN_HPAD), 0
            )
        }

        queries.forEach { query ->
            val chip = Ui.chip(ctx, query)
            chip.setOnClickListener {
                field.setText(query)
                field.setSelection(query.length)
                search(query)
            }
            chip.setOnLongClickListener {
                store.removeSearch(query)
                rebuildRecent()
                true
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Theme.dp(ctx, 10) }
            )
        }

        val clear = Ui.chip(ctx, "مسح سجل البحث")
        clear.setOnClickListener {
            store.clearSearches()
            host.toast("تم مسح سجل البحث")
            rebuildRecent()
        }
        row.addView(clear)

        scroller.addView(
            row,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        recentHolder.addView(
            scroller,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun search(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) {
            state.empty("اكتب اسم مسلسل أولًا، ثم اضغط «بحث».")
            return
        }
        prefs.lastSearch = query
        store.addSearch(query)
        rebuildRecent()
        state.loading("جارٍ البحث عن «$query»…")
        launch {
            try {
                val cards = repo.search(query)
                render(query, cards)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state.error(errorText(e)) { search(query) }
            }
        }
    }

    private fun render(query: String, cards: List<Card>) {
        resultsColumn.removeAllViews()
        resultsColumn.addView(
            pageHeader(
                "نتائج «$query»",
                if (cards.isEmpty()) "لا توجد نتائج" else "${cards.size} نتيجة"
            ),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        if (cards.isEmpty()) {
            state.empty(
                "لا توجد نتائج مطابقة لـ «$query».\nجرّب كلمة أخرى أو ابحث باسم المسلسل فقط."
            )
            return
        }

        val grid: CardGrid = newGrid(portrait = true, cardWidthDp = 156)
        grid.setCards(cards, images)
        resultsColumn.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        state.hide()
        grid.post { if (grid.isAttachedToWindow) Focus.focusIn(grid) }
    }

    private companion object {
        /** Verified non-empty queries against the live site. */
        val SUGGESTIONS = listOf("حب", "الخليفة", "عشق")
    }
}
