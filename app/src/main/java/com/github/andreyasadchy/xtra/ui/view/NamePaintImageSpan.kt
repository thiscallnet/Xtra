package com.github.andreyasadchy.xtra.ui.view

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import kotlin.math.ceil
import kotlin.math.max

class NamePaintImageSpan(
    private val name: String,
    private val shadows: List<NamePaint.Shadow>?,
    var backgroundColor: Int?,
    private val bottomBackgroundColor: Int,
    @Volatile
    var drawable: Drawable,
) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) {
            val paintFm = paint.fontMetrics
            fm.ascent = paintFm.ascent.toInt()
            fm.bottom = paintFm.bottom.toInt()
            fm.descent = paintFm.descent.toInt()
            fm.leading = paintFm.leading.toInt()
            fm.top = paintFm.top.toInt()
        }
        return paint.measureText(name).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val xOffset = x.toInt()
        val width = paint.measureText(name).toInt()
        val height = bottom - top
        val drawableWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: drawable.bounds.width().coerceAtLeast(1)
        val drawableHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: drawable.bounds.height().coerceAtLeast(1)
        val widthRatio = drawableWidth.toFloat() / drawableHeight.toFloat()
        val fullWidth: Int
        val fullHeight: Int
        if (height > drawableHeight) {
            val addedWidth = ceil((height - drawableHeight) * widthRatio).toInt()
            val newWidth = drawableWidth + addedWidth
            if (width > newWidth) {
                val addedHeight = ceil((width - newWidth) / widthRatio).toInt()
                fullWidth = xOffset + width
                fullHeight = bottom + addedHeight
            } else {
                fullWidth = xOffset + newWidth
                fullHeight = bottom
            }
        } else {
            if (width > drawableWidth) {
                val addedHeight = ceil((width - drawableWidth) / widthRatio).toInt()
                fullWidth = xOffset + width
                fullHeight = top + drawableHeight + addedHeight
            } else {
                fullWidth = xOffset + drawableWidth
                fullHeight = top + drawableHeight
            }
        }
        drawable.setBounds(xOffset, top, fullWidth, fullHeight)
        drawable.draw(canvas)
        val maskBitmap = Bitmap.createBitmap(max(fullWidth - xOffset, 0), max(fullHeight - top, 0), Bitmap.Config.ARGB_8888)
        val maskCanvas = Canvas(maskBitmap)
        val maskPaint = Paint(paint)
        maskPaint.style = Paint.Style.FILL
        maskPaint.color = bottomBackgroundColor
        maskCanvas.drawPaint(maskPaint)
        backgroundColor?.let {
            maskPaint.color = it
            maskCanvas.drawPaint(maskPaint)
        }
        maskPaint.color = paint.color
        val yOffset = y.toFloat() - top
        shadows?.forEach {
            maskPaint.setShadowLayer(it.radius, it.xOffset, it.yOffset, it.color)
            maskCanvas.drawText(name, 0f, yOffset, maskPaint)
        }
        maskPaint.clearShadowLayer()
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
        maskPaint.alpha = 0
        maskCanvas.drawText(name, 0f, yOffset, maskPaint)
        canvas.drawBitmap(maskBitmap, xOffset.toFloat(), top.toFloat(), paint)
    }
}
