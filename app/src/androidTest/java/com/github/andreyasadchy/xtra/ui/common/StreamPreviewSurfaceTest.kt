package com.github.andreyasadchy.xtra.ui.common

import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StreamPreviewSurfaceTest {

    @Test
    fun firstFrameRevealsThePlayerAndItsHost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val playerView = PlayerView(context).apply {
                alpha = 0f
                visibility = android.view.View.GONE
            }
            val host = FrameLayout(context).apply {
                alpha = 0f
                visibility = android.view.View.GONE
            }

            revealPreviewSurface(playerView, host)

            assertEquals(1f, playerView.alpha)
            assertEquals(android.view.View.VISIBLE, playerView.visibility)
            assertEquals(1f, host.alpha)
            assertEquals(android.view.View.VISIBLE, host.visibility)
        }
    }
}
