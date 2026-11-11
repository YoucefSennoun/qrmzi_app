package tv.qrmzi.client.ui

import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.Kind
import tv.qrmzi.client.util.Arabic

/**
 * One focusable content card: rounded thumbnail plus a two-line caption.
 *
 * The card is the focus target (the [ThumbView] is not focusable), so the
 * D-pad ring, the scale animation and the click all belong to a single view.
 */
class MediaCard(context: Context) : LinearLayout(context) {

    val thumb = ThumbView(context)

    private val primary = TextView(context)
    private val secondary = TextView(context)

    var card: Card? = null
        private set

    var onActivate: ((Card) -> Unit)? = null
    var onLongActivate: ((Card) -> Unit)? = null

    var isWide: Boolean = false
        private set

    init {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        isFocusable = true
        isClickable = true
        isLongClickable = true
        clipChildren = false
        clipToPadding = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        thumb.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(thumb)

        primary.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Theme.TEXT_DIM)
            typeface = Theme.medium
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Theme.dp(context, 8) }
        }
        addView(primary)

        secondary.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Theme.TEXT_FAINT)
            typeface = Theme.regular
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Theme.dp(context, 2) }
        }
        addView(secondary)

        setOnFocusChangeListener { _, focused ->
            thumb.setFocusedCard(focused)
            primary.setTextColor(if (focused) Theme.TEXT else Theme.TEXT_DIM)
            animate().cancel()
            if (!Theme.animationsEnabled) {
                scaleX = 1f
                scaleY = 1f
            } else {
                animate()
                    .scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f)
                    .setDuration(130)
                    .start()
            }
        }

        setOnClickListener {
            card?.let { onActivate?.invoke(it) }
        }
        setOnLongClickListener {
            val current = card
            if (current != null) {
                onLongActivate?.invoke(current)
                true
            } else {
                false
            }
        }
    }

    fun bind(
        card: Card,
        widthDp: Int,
        wide: Boolean,
        images: ImageLoader,
        progress: Float = 0f
    ) {
        this.card = card
        this.isWide = wide
        thumb.aspect = if (wide) 16f / 9f else 2f / 3f
        thumb.cornerRadius = Theme.dpf(context, Theme.RADIUS_CARD)
        thumb.badge = card.number?.let { "حلقة $it" }
        thumb.ribbon = card.ribbon
        thumb.progress = progress

        layoutParams = LayoutParams(Theme.dp(context, widthDp), LayoutParams.WRAP_CONTENT)

        val split = Arabic.splitEpisodeTitle(card.title)
        primary.text = when {
            card.kind == Kind.EPISODE && split.first.isNotBlank() -> split.first
            else -> card.title
        }
        secondary.text = when {
            card.kind == Kind.EPISODE && split.second != null -> "الحلقة ${split.second}"
            card.ribbon != null -> card.ribbon.orEmpty()
            card.kind == Kind.MOVIE -> "فيلم"
            card.kind == Kind.SERIES -> "مسلسل"
            else -> ""
        }
        secondary.visibility = if (secondary.text.isNullOrBlank()) View.GONE else View.VISIBLE

        images.load(card.image, thumb, wide)
    }
}
