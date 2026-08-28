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

    // The name-paint mask is independent of the animated drawable pixels. Keep one bounded
    // mask per span and rebuild it only when the text/paint geometry changes, never per draw.
    private var maskBitmap: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var cachedMaskSignature = 0

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
        val maskWidth = max(fullWidth - xOffset, 0)
        val maskHeight = max(fullHeight - top, 0)
        if (maskWidth == 0 || maskHeight == 0) return
        val yOffset = y.toFloat() - top
        val signature = maskSignature(paint, maskWidth, maskHeight, yOffset)
        val cachedMask = maskBitmap
        if (cachedMask == null || cachedMask.width != maskWidth || cachedMask.height != maskHeight || signature != cachedMaskSignature) {
            val bitmap = if (cachedMask == null || cachedMask.width != maskWidth || cachedMask.height != maskHeight) {
                Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888).also {
                    maskBitmap = it
                    maskCanvas = Canvas(it)
                }
            } else {
                cachedMask.also { it.eraseColor(android.graphics.Color.TRANSPARENT) }
            }
            val targetCanvas = checkNotNull(maskCanvas)
            val maskPaint = Paint(paint).apply { style = Paint.Style.FILL }
            maskPaint.color = bottomBackgroundColor
            targetCanvas.drawPaint(maskPaint)
            backgroundColor?.let {
                maskPaint.color = it
                targetCanvas.drawPaint(maskPaint)
            }
            maskPaint.color = paint.color
            shadows?.forEach {
                maskPaint.setShadowLayer(it.radius, it.xOffset, it.yOffset, it.color)
                targetCanvas.drawText(name, 0f, yOffset, maskPaint)
            }
            maskPaint.clearShadowLayer()
            maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            maskPaint.alpha = 0
            targetCanvas.drawText(name, 0f, yOffset, maskPaint)
            cachedMaskSignature = signature
            canvas.drawBitmap(bitmap, xOffset.toFloat(), top.toFloat(), paint)
        } else {
            canvas.drawBitmap(cachedMask, xOffset.toFloat(), top.toFloat(), paint)
        }
    }

    private fun maskSignature(paint: Paint, width: Int, height: Int, yOffset: Float): Int {
        var result = name.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + yOffset.toBits()
        result = 31 * result + paint.color
        result = 31 * result + paint.alpha
        result = 31 * result + paint.flags
        result = 31 * result + paint.style.ordinal
        result = 31 * result + paint.strokeWidth.toBits()
        result = 31 * result + paint.textAlign.ordinal
        result = 31 * result + paint.textSize.toBits()
        result = 31 * result + paint.textScaleX.toBits()
        result = 31 * result + paint.textSkewX.toBits()
        result = 31 * result + (paint.typeface?.hashCode() ?: 0)
        result = 31 * result + (backgroundColor ?: 0)
        result = 31 * result + bottomBackgroundColor
        shadows?.forEach {
            result = 31 * result + it.radius.toBits()
            result = 31 * result + it.xOffset.toBits()
            result = 31 * result + it.yOffset.toBits()
            result = 31 * result + it.color
        }
        return result
    }
}
