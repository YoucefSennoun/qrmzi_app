package tv.qrmzi.client.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Design tokens for the Qrmzi TV client: dark "cinema" surfaces with the
 * crimson accent of the website. No XML resources are used (the APK is built
 * without aapt), so every value lives here.
 */
object Theme {

    // ------------------------------------------------------------------ colours
    val BG = 0xFF0B0B0E.toInt()
    val BG_DEEP = 0xFF070709.toInt()
    val SURFACE = 0xFF15151A.toInt()
    val SURFACE_2 = 0xFF1E1E25.toInt()
    val SURFACE_3 = 0xFF2A2A33.toInt()

    val ACCENT = 0xFFE50914.toInt()
    val ACCENT_DIM = 0xFFB00710.toInt()
    val ACCENT_SOFT = 0x33E50914

    val TEXT = 0xFFF5F5F7.toInt()
    val TEXT_DIM = 0xFFB9B9C2.toInt()
    val TEXT_FAINT = 0xFF7C7C88.toInt()

    val GOLD = 0xFFE8B923.toInt()
    val GREEN = 0xFF46D369.toInt()
    val DANGER = 0xFFFF5A5F.toInt()

    val STROKE = 0xFF33333D.toInt()
    val FOCUS_RING = 0xFFFFFFFF.toInt()

    // ------------------------------------------------------------------ type
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)

    const val HERO = 40f
    const val H1 = 28f
    const val H2 = 22f
    const val H3 = 18f
    const val BODY = 15f
    const val SMALL = 13f
    const val TINY = 11f

    // ------------------------------------------------------------------ metrics
    const val RADIUS_CARD = 10f
    const val RADIUS_PILL = 22f
    const val ROW_GAP = 14
    const val SCREEN_HPAD = 48
    const val CONTENT_TOP = 132

    /**
     * Focus animations are a nice touch on a fast box and a stutter source on
     * a 1 GB one, so the settings screen can turn them off at runtime.
     */
    @Volatile
    var animationsEnabled: Boolean = true

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dpf(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    fun tint(color: Int, radiusDp: Float, context: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dpf(context, radiusDp)
        }

    fun outline(
        fill: Int,
        radiusDp: Float,
        strokeDp: Float,
        strokeColor: Int,
        context: Context
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dpf(context, radiusDp)
        setStroke(dp(context, strokeDp.toInt().coerceAtLeast(1)), strokeColor)
    }

    fun pill(context: Context, fill: Int, strokeColor: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dpf(context, RADIUS_PILL)
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(context, 2), strokeColor)
        }

    fun applyTextSize(view: TextView, sp: Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /** Edge-to-edge, dark system bars, immersive so the TV never shows chrome. */
    fun immersive(activity: Activity) {
        val window = activity.window
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        hideSystemUi(activity)
    }

    fun hideSystemUi(activity: Activity) {
        activity.window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
