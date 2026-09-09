package com.github.andreyasadchy.xtra.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPagerPresentationTest {
    @Test
    fun `Drops search temporarily enables Streams without changing configured order`() {
        val configured = listOf("0", "2", "3")

        assertEquals(listOf("0", "1", "2", "3"), searchTabsForDropsFilter(configured, true))
        assertEquals(configured, searchTabsForDropsFilter(configured, false))
        assertEquals(listOf("2", "1", "3"), searchTabsForDropsFilter(listOf("2", "1", "3"), true))
    }

    @Test
    fun `Drops chip is visible only on the Streams tab`() {
        assertTrue(shouldShowDropsFilter(true, selectedTabPosition = 1, streamTabPosition = 1))
        assertFalse(shouldShowDropsFilter(true, selectedTabPosition = 0, streamTabPosition = 1))
        assertFalse(shouldShowDropsFilter(false, selectedTabPosition = 1, streamTabPosition = 1))
    }
}
