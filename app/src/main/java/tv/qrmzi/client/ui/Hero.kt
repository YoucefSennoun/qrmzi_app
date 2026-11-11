package tv.qrmzi.client.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.qrmzi.client.data.Card
import tv.qrmzi.client.data.Kind
import tv.qrmzi.client.util.Arabic

/**
 * Netflix-style billboard: a full-bleed still, dark scrims, the title and the
 * two primary actions. Its first action receives the initial screen focus.
 */
class Hero(context: Context) : FrameLayout(context) {

    private val image = ThumbView(context)
    private val scrim = Scrim(context)
    private val column = LinearLayout(context)
    private val brand = TextView(context)
    private val titleView = TextView(context)
    private val metaView = TextView(context)

    val playButton: TextView = Ui.actionButton(context, "شاهد الآن", accent = true, icon = "▶")
    val detailsButton: TextView = Ui.actionButton(context, "التفاصيل", accent = false)

    var card: Card? = null
        private set

    init {
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        clipChildren = false
        image.apply {
            aspect = 16f / 9f
            cornerRadius = 0f
            showPlayGlyph = false
        }
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        column.orientation = LinearLayout.VERTICAL
        column.layoutDirection = View.LAYOUT_DIRECTION_RTL
        column.gravity = Gravity.BOTTOM or Gravity.START
        column.setPadding(
            Theme.dp(context, Theme.SCREEN_HPAD), 0,
            Theme.dp(context, Theme.SCREEN_HPAD), Theme.dp(context, 40)
        )

        brand.apply {
            text = "قرمزي TV  •  حصري"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Theme.ACCENT)
            typeface = Theme.bold
            includeFontPadding = false
        }
        column.addView(brand)

        titleView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.HERO)
            setTextColor(Color.WHITE)
            typeface = Theme.bold
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
            setShadowLayer(Theme.dpf(context, 10f), 0f, Theme.dpf(context, 2f), 0xCC000000.toInt())
        }
        column.addView(
            titleView,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Theme.dp(context, 10) }
        )

        metaView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.SMALL)
            setTextColor(Theme.TEXT_DIM)
            typeface = Theme.medium
            includeFontPadding = false
            maxLines = 1
        }
        column.addView(
            metaView,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Theme.dp(context, 10) }
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        buttons.addView(playButton)
        (playButton.layoutParams as LinearLayout.LayoutParams).marginEnd = Theme.dp(context, 14)
        buttons.addView(detailsButton)
        column.addView(
            buttons,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Theme.dp(context, 20) }
        )

        addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
    }

    fun bind(
        card: Card,
        images: ImageLoader,
        onPlay: (Card) -> Unit,
        onDetails: (Card) -> Unit
    ) {
        this.card = card
        titleView.text = Arabic.splitEpisodeTitle(card.title).first
        val parts = mutableListOf<String>()
        if (card.kind == Kind.SERIES) parts += "مسلسل"
        if (card.kind == Kind.MOVIE) parts += "فيلم"
        card.number?.let { parts += "الحلقة $it" }
        card.ribbon?.let { parts += it }
        metaView.text = parts.joinToString("  •  ")
        metaView.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        images.load(card.image, image, true)
        playButton.setOnClickListener { onPlay(card) }
        detailsButton.setOnClickListener { onDetails(card) }
    }

    fun requestInitialFocus() {
        playButton.post { if (isAttachedToWindow) playButton.requestFocus() }
    }
}

/** Small helper used by the hero and detail headers. */
internal fun TextView.applyTitleShadow(density: Float) {
    setShadowLayer(density * 8f, 0f, density * 2f, 0xCC000000.toInt())
}

/** A vertical gap that ignores focus. */
internal fun ViewGroup.addGap(dp: Int) {
    addView(
        View(context),
        if (this is LinearLayout) LinearLayout.LayoutParams(1, Theme.dp(context, dp))
        else ViewGroup.LayoutParams(1, Theme.dp(context, dp))
    )
}
