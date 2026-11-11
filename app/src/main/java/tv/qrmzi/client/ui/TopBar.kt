package tv.qrmzi.client.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Persistent top navigation. It floats over the content (so the home hero can
 * bleed underneath it) and every item is reachable with the D-pad.
 */
class TopBar(context: Context) : LinearLayout(context) {

    enum class Section { HOME, LATEST, SERIES, SEARCH, FAVORITES, HISTORY, SETTINGS }

    var onSection: ((Section) -> Unit)? = null

    private val order = listOf(
        Section.HOME to "الرئيسية",
        Section.LATEST to "أحدث الحلقات",
        Section.SERIES to "المسلسلات",
        Section.SEARCH to "البحث",
        Section.FAVORITES to "المفضلة",
        Section.HISTORY to "السجل",
        Section.SETTINGS to "الإعدادات"
    )
    private val chips = LinkedHashMap<Section, TextView>()

    var active: Section = Section.HOME
        set(value) {
            field = value
            chips.forEach { (section, chip) -> style(chip, section, chip.hasFocus()) }
        }

    init {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF0B0B0E.toInt(), 0xF20B0B0E.toInt(), 0x000B0B0E)
        )
        setPadding(
            Theme.dp(context, Theme.SCREEN_HPAD), 0,
            Theme.dp(context, Theme.SCREEN_HPAD), 0
        )

        val logo = TextView(context).apply {
            text = "قرمزي"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTextColor(Theme.ACCENT)
            typeface = Theme.bold
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
        }
        addView(
            logo,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = Theme.dp(context, 30) }
        )

        order.forEach { (section, label) ->
            val chip = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Theme.medium
                includeFontPadding = false
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                setPadding(
                    Theme.dp(context, 16), Theme.dp(context, 9),
                    Theme.dp(context, 16), Theme.dp(context, 9)
                )
                setOnFocusChangeListener { v, focused ->
                    style(v as TextView, section, focused)
                }
                setOnClickListener { onSection?.invoke(section) }
            }
            chips[section] = chip
            addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = Theme.dp(context, 2) }
            )
        }

        chips.forEach { (section, chip) -> style(chip, section, false) }
    }

    private fun style(chip: TextView, section: Section, focused: Boolean) {
        val isActive = section == active
        when {
            focused -> {
                chip.setTextColor(Color.BLACK)
                chip.background = Theme.pill(context, Color.WHITE)
            }
            isActive -> {
                chip.setTextColor(Theme.TEXT)
                chip.background = Theme.pill(context, Theme.ACCENT_SOFT)
            }
            else -> {
                chip.setTextColor(Theme.TEXT_DIM)
                chip.background = null
            }
        }
    }
}
