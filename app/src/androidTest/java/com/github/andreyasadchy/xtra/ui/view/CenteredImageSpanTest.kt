package com.github.andreyasadchy.xtra.ui.view

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Test

class CenteredImageSpanTest {

    @Test
    fun loadingDrawableCannotChangeReservedMeasurement() {
        val placeholder = ColorDrawable(Color.TRANSPARENT).apply {
            setBounds(0, 0, 48, 32)
        }
        val span = CenteredImageSpan(placeholder, reservedWidth = 48, reservedHeight = 32)
        val paint = Paint().apply { textSize = 16f }
        val beforeMetrics = Paint.FontMetricsInt()
        val beforeWidth = span.getSize(paint, "x", 0, 1, beforeMetrics)

        span.imageDrawable = ColorDrawable(Color.RED).apply {
            setBounds(0, 0, 300, 200)
        }

        val afterMetrics = Paint.FontMetricsInt()
        val afterWidth = span.getSize(paint, "x", 0, 1, afterMetrics)
        assertEquals(48, beforeWidth)
        assertEquals(beforeWidth, afterWidth)
        assertEquals(beforeMetrics.ascent, afterMetrics.ascent)
        assertEquals(beforeMetrics.descent, afterMetrics.descent)
        assertEquals(beforeMetrics.top, afterMetrics.top)
        assertEquals(beforeMetrics.bottom, afterMetrics.bottom)
    }
}
