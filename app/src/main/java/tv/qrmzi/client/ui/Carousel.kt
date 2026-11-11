package tv.qrmzi.client.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.data.Card

/**
 * Netflix-style horizontally scrolling row.
 *
 * The row is scrolled by the framework when a card receives focus; no custom
 * smooth-scrolling code is needed, which keeps D-pad behaviour predictable.
 */
class Carousel(context: Context) : LinearLayout(context) {

    private val headerRow = LinearLayout(context)
    private val titleView = TextView(context)
    private val moreView = TextView(context)
    private val scroller = HorizontalScrollView(context)
    private val row = LinearLayout(context)

    var onCardClick: ((Card) -> Unit)? = null
    var onLongCardClick: ((Card) -> Unit)? = null

    /** Portrait poster cards (home / search) or landscape episode cards. */
    var portrait: Boolean = true
    var cardWidthDp: Int = 156

    init {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL

        headerRow.orientation = LinearLayout.HORIZONTAL
        headerRow.layoutDirection = View.LAYOUT_DIRECTION_RTL
        headerRow.gravity = Gravity.CENTER_VERTICAL
        headerRow.setPadding(
            Theme.dp(context, Theme.SCREEN_HPAD), Theme.dp(context, 18),
            Theme.dp(context, Theme.SCREEN_HPAD), Theme.dp(context, 6)
        )
        titleView.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.H2)
            setTextColor(Theme.TEXT)
            typeface = Theme.medium
            includeFontPadding = false
            maxLines = 1
        }
        headerRow.addView(
            titleView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1f)
        )
        moreView.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.SMALL)
            setTextColor(Theme.TEXT_DIM)
            typeface = Theme.medium
            includeFontPadding = false
            isFocusable = true
            isClickable = true
            setPadding(
                Theme.dp(context, 14), Theme.dp(context, 6),
                Theme.dp(context, 14), Theme.dp(context, 6)
            )
            setOnFocusChangeListener { v, focused ->
                (v as TextView).setTextColor(if (focused) Theme.TEXT else Theme.TEXT_DIM)
                v.background = if (focused) Theme.pill(context, Theme.SURFACE_2, Theme.STROKE) else null
            }
            visibility = GONE
        }
        headerRow.addView(moreView)
        addView(headerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        scroller.apply {
            isHorizontalScrollBarEnabled = false
            isHorizontalFadingEdgeEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            clipChildren = false
            setPadding(0, Theme.dp(context, 4), 0, Theme.dp(context, 10))
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        row.apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(
                Theme.dp(context, Theme.SCREEN_HPAD), 0,
                Theme.dp(context, Theme.SCREEN_HPAD), 0
            )
        }
        scroller.addView(
            row,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(scroller, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setTitle(value: CharSequence) {
        titleView.text = value
    }

    fun setMore(label: String?, action: (() -> Unit)?) {
        if (label == null || action == null) {
            moreView.visibility = GONE
            moreView.setOnClickListener(null)
        } else {
            moreView.visibility = VISIBLE
            moreView.text = label
            moreView.setOnClickListener { action() }
        }
    }

    fun setCards(
        items: List<Card>,
        images: ImageLoader,
        progress: (Card) -> Float = { 0f }
    ) {
        row.removeAllViews()
        items.forEach { item ->
            val card = MediaCard(context)
            card.onActivate = { onCardClick?.invoke(it) }
            card.onLongActivate = { onLongCardClick?.invoke(it) }
            row.addView(card)
            card.bind(item, cardWidthDp, !portrait, images, progress(item))
            (card.layoutParams as LayoutParams).marginEnd = Theme.dp(context, Theme.ROW_GAP)
        }
    }

    fun isEmpty(): Boolean = row.childCount == 0
}
