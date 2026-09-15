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

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val height = height.toFloat().coerceAtLeast(1f)
        paint.shader = null
        paint.color = 0x24000000
        canvas.drawRect(0f, 0f, width.toFloat(), height, paint)

        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height * 0.34f,
            0x99000000.toInt(),
            0x00000000,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height * 0.34f, paint)

        paint.shader = LinearGradient(
            0f,
            height * 0.66f,
            0f,
            height,
            0x00000000,
            0x99000000.toInt(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, height * 0.66f, width.toFloat(), height, paint)
        paint.shader = null
    }
}
