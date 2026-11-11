package tv.qrmzi.client.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * Dark gradient overlay used on top of hero/header artwork so white text stays
 * readable on bright stills.
 */
internal class Scrim(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var bottomAlpha: Int = 0xF2
    var sideAlpha: Int = 0xF2

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        paint.shader = LinearGradient(
            0f, h * 0.30f, 0f, h,
            intArrayOf(0x00000000, 0x99000000.toInt(), (bottomAlpha shl 24) or 0x0B0B0E),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.shader = LinearGradient(
            w, 0f, w * 0.25f, 0f,
            intArrayOf((sideAlpha shl 24) or 0x0B0B0E, 0x800B0B0E.toInt(), 0x000B0B0E),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }
}
