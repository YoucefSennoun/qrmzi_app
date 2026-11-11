package tv.qrmzi.client.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import tv.qrmzi.client.data.Card

/**
 * A non-scrolling card grid (rows of fixed-width cards). Used by the archive
 * pages, search results, favourites and history.
 *
 * A flat grid of views is used instead of a RecyclerView: one archive page is
 * at most 50 cards, and keeping it simple avoids an AndroidX dependency.
 */
class CardGrid(context: Context) : LinearLayout(context) {

    var columns: Int = 6
    var portrait: Boolean = false
    var cardWidthDp: Int = 276
    var gapDp: Int = Theme.ROW_GAP

    var onCardClick: ((Card) -> Unit)? = null
    var onLongCardClick: ((Card) -> Unit)? = null

    init {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        clipChildren = false
        clipToPadding = false
    }

    fun setCards(
        items: List<Card>,
        images: ImageLoader,
        progress: (Card) -> Float = { 0f }
    ) {
        removeAllViews()
        var index = 0
        while (index < items.size) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
            }
            val end = minOf(index + columns, items.size)
            for (i in index until end) {
                val item = items[i]
                val card = MediaCard(context)
                card.onActivate = { onCardClick?.invoke(it) }
                card.onLongActivate = { onLongCardClick?.invoke(it) }
                row.addView(card)
                card.bind(item, cardWidthDp, !portrait, images, progress(item))
                (card.layoutParams as LayoutParams).marginEnd = Theme.dp(context, gapDp)
            }
            addView(
                row,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = Theme.dp(context, 18) }
            )
            index = end
        }
    }

    companion object {
        /** How many cards of [cardWidthDp] fit on this screen. */
        fun columnsFor(
            context: Context,
            cardWidthDp: Int,
            gapDp: Int = Theme.ROW_GAP,
            outerPadDp: Int = Theme.SCREEN_HPAD
        ): Int {
            val widthPx = context.resources.displayMetrics.widthPixels
            val available = widthPx - Theme.dp(context, outerPadDp) * 2
            val card = Theme.dp(context, cardWidthDp)
            val gap = Theme.dp(context, gapDp)
            return maxOf(2, (available + gap) / (card + gap))
        }
    }
}
