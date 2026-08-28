package com.github.andreyasadchy.xtra.ui.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.drawable.Drawable
import android.text.style.ImageSpan
import kotlin.math.roundToInt


/**
 * An inline image span whose slot never changes after the text is measured.
 * The drawable can be decoded later, but its pixels are always drawn inside
 * the dimensions reserved when the message was built.
 */
class CenteredImageSpan(
    initialDrawable: Drawable,
    private val reservedWidth: Int = initialDrawable.bounds.width(),
    private val reservedHeight: Int = initialDrawable.bounds.height(),
) : ImageSpan(initialDrawable) {

    private val fallbackDrawable = initialDrawable

    @Volatile
    private var drawable: Drawable? = initialDrawable

    var imageDrawable: Drawable
        get() = drawable ?: fallbackDrawable
        set(value) {
            drawable = value.mutate().also { it.setBounds(0, 0, reservedWidth, reservedHeight) }
        }

    override fun getDrawable(): Drawable = drawable ?: fallbackDrawable

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fontMetricsInt: FontMetricsInt?): Int {
        if (fontMetricsInt != null) {
            val base = paint.fontMetricsInt
            val centerY = (base.ascent + base.descent) / 2
            val halfHeight = reservedHeight / 2
            fontMetricsInt.ascent = centerY - halfHeight
            fontMetricsInt.top = fontMetricsInt.ascent
            fontMetricsInt.bottom = centerY + reservedHeight - halfHeight
            fontMetricsInt.descent = fontMetricsInt.bottom
        }
        return reservedWidth
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val drawable = drawable ?: return
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: reservedWidth
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: reservedHeight
        val scale = minOf(
            reservedWidth.toFloat() / intrinsicWidth,
            reservedHeight.toFloat() / intrinsicHeight,
        )
        val drawWidth = (intrinsicWidth * scale).roundToInt().coerceAtLeast(1)
        val drawHeight = (intrinsicHeight * scale).roundToInt().coerceAtLeast(1)
        drawable.setBounds(0, 0, drawWidth, drawHeight)
        canvas.save()
        val base = paint.fontMetricsInt
        val baselineCenter = y + (base.ascent + base.descent) / 2f
        val drawX = x + (reservedWidth - drawWidth) / 2f
        val drawY = baselineCenter - drawHeight / 2f
        canvas.translate(drawX, drawY)
        drawable.draw(canvas)
        canvas.restore()
    }
}
