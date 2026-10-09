package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class HudScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var topGradient: Shader? = null
    private var bottomGradient: Shader? = null

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val height = h.toFloat().coerceAtLeast(1f)
        topGradient = LinearGradient(0f, 0f, 0f, height * 0.42f,
            0xB3000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        bottomGradient = LinearGradient(0f, height * 0.66f, 0f, height,
            0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val height = height.toFloat()
        paint.shader = null
        paint.color = 0x24000000
        canvas.drawRect(0f, 0f, width.toFloat(), height, paint)
        paint.shader = topGradient
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.42f, paint)
        paint.shader = bottomGradient
        canvas.drawRect(0f, height * 0.66f, width.toFloat(), height, paint)
        paint.shader = null
    }
}
