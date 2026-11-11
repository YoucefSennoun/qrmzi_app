package tv.qrmzi.client.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.View

/**
 * A poster / episode thumbnail drawn by hand: rounded corners, centre-crop
 * scaling, gradient placeholder, episode badge, ribbon and focus ring.
 *
 * A custom View avoids two nested drawables and a bitmap copy per card, which
 * keeps the scroll jank low on a 1 GB box.
 */
class ThumbView(context: Context) : View(context) {

    var aspect: Float = 16f / 9f
        set(value) {
            field = value
            requestLayout()
        }

    var cornerRadius: Float = Theme.dpf(context, Theme.RADIUS_CARD)
    var imageTag: String? = null

    var badge: String? = null
        set(value) { field = value; invalidate() }

    var ribbon: String? = null
        set(value) { field = value; invalidate() }

    var showPlayGlyph: Boolean = true
    var progress: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private var bitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.medium }
    private val path = Path()
    private val clipRect = RectF()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var focusedState = false

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusable = false
        isClickable = false
    }

    fun setBitmap(value: Bitmap?) {
        bitmap = value
        invalidate()
    }

    fun setFocusedCard(focused: Boolean) {
        if (focusedState != focused) {
            focusedState = focused
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (width / aspect).toInt().coerceAtLeast(1)
        }
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        path.reset()
        clipRect.set(0f, 0f, w, h)
        path.addRoundRect(clipRect, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(path)

        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            drawCenterCrop(canvas, bmp, w, h)
        } else {
            drawPlaceholder(canvas, w, h)
        }

        // bottom scrim so titles/badges stay legible
        fillPaint.shader = LinearGradient(
            0f, h * 0.55f, 0f, h,
            intArrayOf(Color.TRANSPARENT, 0xB3000000.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.55f, w, h, fillPaint)
        fillPaint.shader = null

        drawBadges(canvas, w, h)

        if (progress > 0f) {
            val barH = Theme.dpf(context, 3f)
            fillPaint.color = 0xCCFFFFFF.toInt()
            canvas.drawRect(0f, h - barH, w, h, fillPaint)
            fillPaint.color = Theme.ACCENT
            canvas.drawRect(0f, h - barH, w * progress, h, fillPaint)
        }

        if (focusedState) {
            fillPaint.shader = LinearGradient(
                0f, h * 0.2f, 0f, h,
                intArrayOf(0x00FFFFFF, 0x22FFFFFF),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, fillPaint)
            fillPaint.shader = null
        }

        canvas.restore()

        if (focusedState) {
            strokePaint.color = Theme.FOCUS_RING
            strokePaint.strokeWidth = Theme.dpf(context, 3f)
            canvas.drawRoundRect(
                strokePaint.strokeWidth / 2f, strokePaint.strokeWidth / 2f,
                w - strokePaint.strokeWidth / 2f, h - strokePaint.strokeWidth / 2f,
                cornerRadius, cornerRadius, strokePaint
            )
        }
    }

    private fun drawCenterCrop(canvas: Canvas, bmp: Bitmap, w: Float, h: Float) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val scale = maxOf(w / bw, h / bh)
        val sw = w / scale
        val sh = h / scale
        val left = (bw - sw) / 2f
        val top = (bh - sh) / 2f
        srcRect.set(left.toInt(), top.toInt(), (left + sw).toInt(), (top + sh).toInt())
        dstRect.set(0f, 0f, w, h)
        canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
    }

    private fun drawPlaceholder(canvas: Canvas, w: Float, h: Float) {
        fillPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF23232C.toInt(), 0xFF17171E.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = null

        if (!showPlayGlyph) return
        val r = minOf(w, h) * 0.13f
        val cx = w / 2f
        val cy = h / 2f
        fillPaint.color = 0x33FFFFFF
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.color = 0xCCFFFFFF.toInt()
        val p = Path()
        p.moveTo(cx - r * 0.28f, cy - r * 0.42f)
        p.lineTo(cx + r * 0.48f, cy)
        p.lineTo(cx - r * 0.28f, cy + r * 0.42f)
        p.close()
        canvas.drawPath(p, fillPaint)
    }

    private fun drawBadges(canvas: Canvas, w: Float, h: Float) {
        val pad = Theme.dpf(context, 9f)
        var occupiedTop = pad

        badge?.let { text ->
            textPaint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
            )
            textPaint.typeface = Theme.bold
            val label = text
            val textWidth = textPaint.measureText(label)
            val boxW = textWidth + pad * 1.6f
            val boxH = textPaint.textSize * 1.85f
            val box = RectF(w - pad - boxW, pad, w - pad, pad + boxH)
            fillPaint.color = 0xE6E50914.toInt()
            canvas.drawRoundRect(box, boxH / 3.4f, boxH / 3.4f, fillPaint)
            textPaint.color = Color.WHITE
            val fm = textPaint.fontMetrics
            canvas.drawText(label, box.left + pad * 0.8f, box.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
            occupiedTop = box.bottom + pad * 0.5f
        }

        ribbon?.let { text ->
            textPaint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics
            )
            textPaint.typeface = Theme.medium
            val textWidth = textPaint.measureText(text)
            val boxW = textWidth + pad * 1.4f
            val boxH = textPaint.textSize * 1.8f
            val box = RectF(pad, occupiedTop, pad + boxW, occupiedTop + boxH)
            fillPaint.color = 0xE6E8B923.toInt()
            canvas.drawRoundRect(box, boxH / 3.4f, boxH / 3.4f, fillPaint)
            textPaint.color = 0xFF141414.toInt()
            val fm = textPaint.fontMetrics
            canvas.drawText(text, box.left + pad * 0.7f, box.centerY() - (fm.ascent + fm.descent) / 2f, textPaint)
        }
    }
}
