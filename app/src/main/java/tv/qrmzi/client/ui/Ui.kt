package tv.qrmzi.client.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small factory helpers for the programmatic UI. Every screen is built from
 * these so that spacing, typography and RTL behaviour stay consistent.
 */
object Ui {

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float = Theme.BODY,
        color: Int = Theme.TEXT,
        bold: Boolean = false,
        maxLines: Int = 3,
        font: Typeface? = null
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = font ?: if (bold) Theme.bold else Theme.regular
        this.maxLines = maxLines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setLineSpacing(0f, 1.18f)
    }

    fun title(context: Context, value: CharSequence, sizeSp: Float = Theme.H2): TextView =
        text(context, value, sizeSp, Theme.TEXT, bold = false).apply {
            typeface = Theme.medium
            maxLines = 1
        }

    fun sectionTitle(context: Context, value: CharSequence): TextView =
        text(context, value, Theme.H2, Theme.TEXT).apply {
            typeface = Theme.medium
            maxLines = 1
        }

    fun label(context: Context, value: CharSequence): TextView =
        text(context, value, Theme.SMALL, Theme.TEXT_DIM).apply { maxLines = 1 }

    fun column(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun spacer(context: Context, dp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, Theme.dp(context, dp))
    }

    fun hspacer(context: Context, dp: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(Theme.dp(context, dp), 1)
    }

    fun pad(view: View, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) {
        view.setPadding(
            Theme.dp(view.context, l), Theme.dp(view.context, t),
            Theme.dp(view.context, r), Theme.dp(view.context, b)
        )
    }

    fun lp(
        width: Int = WRAP_CONTENT,
        height: Int = WRAP_CONTENT,
        weight: Float = 0f,
        marginEnd: Int = 0,
        marginStart: Int = 0,
        marginTop: Int = 0,
        marginBottom: Int = 0
    ): LinearLayout.LayoutParams = LinearLayout.LayoutParams(width, height, weight).apply {
        this.marginEnd = marginEnd
        this.marginStart = marginStart
        this.topMargin = marginTop
        this.bottomMargin = marginBottom
    }

    fun frameLp(width: Int = MATCH_PARENT, height: Int = WRAP_CONTENT): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(width, height)

    /** Primary call-to-action pill ("شاهد الآن"). */
    fun actionButton(
        context: Context,
        label: String,
        accent: Boolean = true,
        icon: String? = null
    ): TextView = TextView(context).apply {
        text = if (icon != null) "$icon  $label" else label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.BODY)
        setTextColor(if (accent) Color.WHITE else Theme.TEXT)
        typeface = Theme.medium
        includeFontPadding = false
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = if (accent) Theme.pill(context, Theme.ACCENT)
        else Theme.pill(context, 0x33FFFFFF, 0x55FFFFFF)
        setPadding(
            Theme.dp(context, 26), Theme.dp(context, 13),
            Theme.dp(context, 26), Theme.dp(context, 13)
        )
        setOnFocusChangeListener { v, focused ->
            if (Theme.animationsEnabled) {
                animate().scaleX(if (focused) 1.06f else 1f)
                    .scaleY(if (focused) 1.06f else 1f)
                    .setDuration(140)
                    .start()
            }
            (v as TextView).setTextColor(if (focused) Color.BLACK else if (accent) Color.WHITE else Theme.TEXT)
            v.background = if (focused) Theme.pill(context, Color.WHITE)
            else if (accent) Theme.pill(context, Theme.ACCENT)
            else Theme.pill(context, 0x33FFFFFF, 0x55FFFFFF)
        }
    }

    /** Focusable chip used for genres / popular searches. */
    fun chip(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.SMALL)
        setTextColor(Theme.TEXT)
        typeface = Theme.medium
        includeFontPadding = false
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        background = Theme.pill(context, Theme.SURFACE_2, Theme.STROKE)
        setPadding(
            Theme.dp(context, 20), Theme.dp(context, 10),
            Theme.dp(context, 20), Theme.dp(context, 10)
        )
        setOnFocusChangeListener { v, focused ->
            v.background = when {
                focused -> Theme.pill(context, Theme.ACCENT)
                else -> Theme.pill(context, Theme.SURFACE_2, Theme.STROKE)
            }
            if (Theme.animationsEnabled) {
                v.animate().scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f).setDuration(120).start()
            }
        }
    }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(Theme.STROKE)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, Theme.dp(context, 1))
    }

    fun setMargins(view: View, l: Int, t: Int, r: Int, b: Int) {
        val lp = view.layoutParams
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.setMargins(
                Theme.dp(view.context, l), Theme.dp(view.context, t),
                Theme.dp(view.context, r), Theme.dp(view.context, b)
            )
            view.layoutParams = lp
        }
    }

    fun backgroundFor(context: Context, focused: Boolean, radius: Float = Theme.RADIUS_CARD): Drawable =
        if (focused) Theme.outline(Theme.SURFACE_2, radius, 2f, Theme.FOCUS_RING, context)
        else Theme.tint(Color.TRANSPARENT, radius, context)
}
