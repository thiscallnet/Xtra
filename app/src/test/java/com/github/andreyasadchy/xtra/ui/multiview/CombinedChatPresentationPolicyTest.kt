package com.github.andreyasadchy.xtra.ui.multiview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedChatPresentationPolicyTest {
    @Test
    fun onlyReadersAtBottomFollowIncomingMessages() {
        assertTrue(CombinedChatPresentationPolicy.shouldAutoScroll(wasAtBottom = true))
        assertFalse(CombinedChatPresentationPolicy.shouldAutoScroll(wasAtBottom = false))
    }

    @Test
    fun explicitRefreshCanScrollAReaderToTheFilteredEnd() {
        assertTrue(
            CombinedChatPresentationPolicy.shouldAutoScroll(
                wasAtBottom = false,
                explicitRefresh = true,
            ),
        )
    }

    @Test
    fun resourceRefreshGenerationChangesDiffContents() {
        assertEquals(8L, CombinedChatPresentationPolicy.nextRenderGeneration(7L))
    }
}
