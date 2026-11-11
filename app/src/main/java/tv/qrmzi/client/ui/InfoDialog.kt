package tv.qrmzi.client.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Dark, D-pad-friendly information dialog.
 *
 * Used for the diagnostics log and the filter config help so neither feature
 * needs a touchscreen or an XML layout. BACK closes it; the first action (or
 * the close button) takes focus, and UP from the buttons moves into the
 * scrollable body.
 */
object InfoDialog {

    fun show(
        context: Context,
        title: String,
        body: String,
        actions: List<Pair<String, () -> Unit>> = emptyList()
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = Theme.tint(Theme.SURFACE, 14f, context)
            setPadding(
                Theme.dp(context, 30), Theme.dp(context, 26),
                Theme.dp(context, 30), Theme.dp(context, 24)
            )
        }

        column.addView(
            Ui.text(context, title, Theme.H2, Theme.TEXT, font = Theme.medium, maxLines = 2),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isFocusable = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setPadding(0, Theme.dp(context, 14), 0, Theme.dp(context, 14))
        }
        val bodyView = TextView(context).apply {
            text = body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Theme.TINY)
            setTextColor(Theme.TEXT_DIM)
            typeface = Theme.regular
            includeFontPadding = false
            maxLines = 200
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START
            setLineSpacing(Theme.dpf(context, 3f), 1f)
        }
        scroll.addView(
            bodyView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        column.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
        }
        var firstFocus: View? = null

        fun addButton(label: String, accent: Boolean, action: () -> Unit) {
            val button = Ui.actionButton(context, label, accent = accent)
            button.setOnClickListener {
                dialog.dismiss()
                action()
            }
            if (firstFocus == null) firstFocus = button
            buttons.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Theme.dp(context, 12) }
            )
        }

        actions.forEach { (label, action) ->
            addButton(label, firstFocus == null, action)
        }
        addButton("إغلاق", actions.isEmpty(), {})

        column.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.setContentView(column)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        val metrics = context.resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (metrics.widthPixels * 0.72f).toInt(),
                (metrics.heightPixels * 0.80f).toInt()
            )
            attributes = attributes.apply {
                dimAmount = 0.65f
                gravity = Gravity.CENTER
            }
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        firstFocus?.post { if (dialog.isShowing) firstFocus?.requestFocus() }
    }
}
