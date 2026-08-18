package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotesAdapterTest {

    @Test
    fun moveListItemMovesNonAdjacentItem() {
        val items = mutableListOf("A", "B", "C", "D")

        assertTrue(moveListItem(items, 0, 3))
        assertEquals(listOf("B", "C", "D", "A"), items)
    }
}
