package com.github.andreyasadchy.xtra.util

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.andreyasadchy.xtra.ui.view.ChatLayout
import com.github.andreyasadchy.xtra.ui.view.PlayerLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerUiGeometryInstrumentationTest {
    @Test
    fun safeBoundsClampEvenWhenInsetsExceedTheAvailableRectangle() {
        val geometry = PlayerUiGeometry.from(
            bounds = Rect(0, 0, 100, 60),
            insets = Rect(70, 50, 80, 40),
            density = 2f,
        )
        assertEquals(Rect(70, 50, 70, 50), geometry.safeBounds)
    }

    @Test
    fun asymmetricInsetsAndPopupPaddingStayInsideSafeBounds() {
        val geometry = PlayerUiGeometry.from(
            bounds = Rect(0, 0, 1000, 600),
            insets = Rect(20, 10, 40, 30),
            density = 2f,
            edgePaddingDp = 12,
        )
        assertEquals(Rect(44, 34, 936, 546), geometry.paddedSafeBounds())
    }

    @Test
    fun popupPaddingCannotInvertANarrowSafeArea() {
        val geometry = PlayerUiGeometry.from(
            bounds = Rect(0, 0, 20, 20),
            insets = Rect(8, 8, 8, 8),
            density = 2f,
            edgePaddingDp = 12,
        )
        val padded = geometry.paddedSafeBounds()
        assertTrue(padded.right >= padded.left)
        assertTrue(padded.bottom >= padded.top)
    }

    @Test
    fun portraitLayoutsKeepTheirCustomSplitWithExactlySpecs() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val player = PlayerLayout(context).apply { isPortrait = true }
        player.measure(exactly(1080), exactly(1920))
        assertEquals(607, player.measuredHeight)

        val chat = ChatLayout(context).apply { isPortrait = true }
        chat.measure(exactly(1080), exactly(1920))
        assertEquals(1313, chat.measuredHeight)

        val smallPlayer = PlayerLayout(context).apply { isPortrait = true }
        smallPlayer.measure(exactly(320), exactly(480))
        assertEquals(180, smallPlayer.measuredHeight)

        val smallChat = ChatLayout(context).apply { isPortrait = true }
        smallChat.measure(exactly(320), exactly(480))
        assertEquals(300, smallChat.measuredHeight)

        val shortPlayer = PlayerLayout(context).apply { isPortrait = true }
        shortPlayer.measure(exactly(320), exactly(160))
        assertTrue(shortPlayer.measuredHeight in 0..160)

        val shortChat = ChatLayout(context).apply { isPortrait = true }
        shortChat.measure(exactly(320), exactly(160))
        assertTrue(shortChat.measuredHeight >= 0)
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(
        size,
        View.MeasureSpec.EXACTLY,
    )
}
