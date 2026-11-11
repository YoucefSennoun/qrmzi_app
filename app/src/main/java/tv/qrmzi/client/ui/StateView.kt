package tv.qrmzi.client.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Full-screen overlay that shows loading / error / empty states. Every screen
 * places one of these over its content, so a failed request always offers a
 * D-pad-reachable retry button.
 */
class StateView(context: Context) : FrameLayout(context) {

    private val box = LinearLayout(context)
    private val spinner = ProgressBar(context)
    private val message = TextView(context)
    private val action = TextView(context)

    init {
        setBackgroundColor(Theme.BG)
        visibility = GONE
        isClickable = true
        isFocusable = false

        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.layoutDirection = View.LAYOUT_DIRECTION_RTL
        addView(
            box,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                .apply { leftMargin = Theme.dp(context, 48); rightMargin = Theme.dp(context, 48) }
        )

        spinner.indeterminateTintList = ColorStateList.valueOf(Theme.ACCENT)
        box.addView(
            spinner,
            LinearLayout.LayoutParams(Theme.dp(context, 46), Theme.dp(context, 46))
                .apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        message.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(Theme.TEXT_DIM)
            typeface = Theme.regular
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 5
        }
        box.addView(
            message,
            LinearLayout.LayoutParams(Theme.dp(context, 520), LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = Theme.dp(context, 22)
                }
        )

        action.apply {
            text = "إعادة المحاولة"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.BODY)
            setTextColor(Color.WHITE)
            typeface = Theme.medium
            includeFontPadding = false
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setPadding(
                Theme.dp(context, 30), Theme.dp(context, 13),
                Theme.dp(context, 30), Theme.dp(context, 13)
            )
            background = Theme.pill(context, Theme.ACCENT)
            setOnFocusChangeListener { v, focused ->
                (v as TextView).setTextColor(if (focused) Color.BLACK else Color.WHITE)
                v.background = if (focused) Theme.pill(context, Color.WHITE) else Theme.pill(context, Theme.ACCENT)
                v.animate().scaleX(if (focused) 1.05f else 1f)
                    .scaleY(if (focused) 1.05f else 1f).setDuration(120).start()
            }
        }
        box.addView(
            action,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = Theme.dp(context, 26)
            }
        )
    }

    fun loading(text: String = "جارٍ التحميل…") {
        visibility = VISIBLE
        spinner.visibility = VISIBLE
        message.text = text
        message.setTextColor(Theme.TEXT_DIM)
        action.visibility = GONE
        action.setOnClickListener(null)
    }

    fun error(text: String, retryLabel: String = "إعادة المحاولة", onRetry: () -> Unit) {
        visibility = VISIBLE
        spinner.visibility = GONE
        message.text = text
        message.setTextColor(Theme.DANGER)
        action.visibility = VISIBLE
        action.text = retryLabel
        action.setOnClickListener { onRetry() }
        action.post { if (isShown) action.requestFocus() }
    }

    fun empty(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        visibility = VISIBLE
        spinner.visibility = GONE
        message.text = text
        message.setTextColor(Theme.TEXT_DIM)
        if (actionLabel != null && onAction != null) {
            action.visibility = VISIBLE
            action.text = actionLabel
            action.setOnClickListener { onAction() }
            action.post { if (isShown) action.requestFocus() }
        } else {
            action.visibility = GONE
            action.setOnClickListener(null)
        }
    }

    fun hide() {
        visibility = GONE
    }
}
