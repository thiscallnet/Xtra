package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Emote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmotesFragmentTest {

    @Test
    fun pendingRefreshDoesNotReplaceChangedDragOrder() {
        val pendingRefresh = listOf(
            Emote(name = "A"),
            Emote(name = "B"),
            Emote(name = "C"),
        )

        assertNull(pendingFavoriteItemsToApply(pendingRefresh, orderChanged = true))
    }

    @Test
    fun pendingRefreshAppliesWhenDragDidNotChangeOrder() {
        val pendingRefresh = listOf(Emote(name = "A"))

        assertEquals(
            pendingRefresh,
            pendingFavoriteItemsToApply(pendingRefresh, orderChanged = false),
        )
    }
}
