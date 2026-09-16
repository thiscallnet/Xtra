package com.github.andreyasadchy.xtra.ui.player.hud

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * A quiet metadata backing that protects text against bright video without
 * introducing a hard-edged rectangle over the picture.
 */
class HudMetadataBackgroundDrawable(
    private val density: Float,
) : Drawable() {
    private companion object {
        const val BACKGROUND_ALPHA = 0x99
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawableAlpha = 255

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width <= 0f || height <= 0f) return

        val horizontalFade = min(8f * density, width / 2f)
        val verticalFade = min(6f * density, height / 2f)
        val horizontalShader = LinearGradient(
            bounds.left.toFloat(),
            0f,
            bounds.right.toFloat(),
            0f,
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(
                0f,
                (horizontalFade / width).coerceIn(0f, 0.5f),
                (1f - horizontalFade / width).coerceIn(0.5f, 1f),
                1f,
            ),
            Shader.TileMode.CLAMP,
        )
        val verticalShader = LinearGradient(
            0f,
            bounds.top.toFloat(),
            0f,
            bounds.bottom.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT),
            floatArrayOf(
                0f,
                (verticalFade / height).coerceIn(0f, 0.5f),
                (1f - verticalFade / height).coerceIn(0.5f, 1f),
                1f,
            ),
            Shader.TileMode.CLAMP,
        )
        paint.shader = ComposeShader(horizontalShader, verticalShader, PorterDuff.Mode.MULTIPLY)
        paint.alpha = BACKGROUND_ALPHA * drawableAlpha / 255
        canvas.drawRoundRect(
            RectF(bounds),
            8f * density,
            8f * density,
            paint,
        )
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getPadding(padding: Rect): Boolean {
        val horizontal = (8f * density).toInt()
        val vertical = (5f * density).toInt()
        padding.set(horizontal, vertical, horizontal, vertical)
        return true
    }
}
