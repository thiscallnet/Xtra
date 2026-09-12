package com.github.andreyasadchy.xtra.ui.view

import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControlEdgeLayoutInstrumentationTest {

    @Test
    fun crowdedOpposingRowsKeepTheirSharedWidth() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val edge = ControlEdgeLayout(context)
        val start = row(context, 4)
        val end = row(context, 4)
        edge.addView(start)
        edge.addView(end)

        edge.measure(exactly(320), exactly(400))
        edge.layout(0, 0, edge.measuredWidth, edge.measuredHeight)

        assertTrue(start.right <= end.left)
        assertTrue(start.left >= edge.paddingLeft)
        assertTrue(end.right <= edge.width - edge.paddingRight)
    }

    @Test
    fun invisibleControlsDoNotReserveRowWidth() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val edge = ControlEdgeLayout(context)
        val start = ControlRowLayout(context).apply {
            addView(ImageButton(context), ViewGroup.LayoutParams(48, 48))
            addView(
                ImageButton(context).apply { visibility = View.INVISIBLE },
                ViewGroup.LayoutParams(48, 48),
            )
        }
        val end = row(context, 1)
        edge.addView(start)
        edge.addView(end)

        edge.measure(exactly(320), exactly(400))

        assertEquals(48, start.measuredWidth)
    }

    private fun row(context: android.content.Context, count: Int) = ControlRowLayout(context).apply {
        repeat(count) {
            addView(
                ImageButton(context),
                ViewGroup.LayoutParams(48, 48),
            )
        }
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(
        size,
        View.MeasureSpec.EXACTLY,
    )
}
