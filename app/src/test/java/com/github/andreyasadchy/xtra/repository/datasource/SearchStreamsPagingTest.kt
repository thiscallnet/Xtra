package com.github.andreyasadchy.xtra.repository.datasource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStreamsPagingTest {
    @Test
    fun `drop game searches advance round robin`() {
        val active = listOf(
            SearchQueryPageState(exhausted = false),
            SearchQueryPageState(exhausted = false),
        )

        assertEquals(1, nextSearchQueryIndex(currentIndex = 0, states = active))
        assertEquals(0, nextSearchQueryIndex(currentIndex = 1, states = active))
    }

    @Test
    fun `exhausted game searches are skipped`() {
        val active = listOf(
            SearchQueryPageState(exhausted = true),
            SearchQueryPageState(exhausted = false),
            SearchQueryPageState(exhausted = false),
        )

        assertEquals(1, nextSearchQueryIndex(currentIndex = 0, states = active))
        assertEquals(1, nextSearchQueryIndex(currentIndex = 2, states = active))
    }

    @Test
    fun `no next query remains after all game searches are exhausted`() {
        assertNull(
            nextSearchQueryIndex(
                currentIndex = 0,
                states = listOf(
                    SearchQueryPageState(exhausted = true),
                    SearchQueryPageState(exhausted = true),
                ),
            ),
        )
    }

    @Test
    fun `page budget can exhaust a game before its upstream cursor`() {
        assertFalse(isSearchQueryExhausted(hasNextPage = true, pagesLoaded = 2, pageBudget = 3))
        assertTrue(isSearchQueryExhausted(hasNextPage = true, pagesLoaded = 3, pageBudget = 3))
        assertTrue(isSearchQueryExhausted(hasNextPage = false, pagesLoaded = 1, pageBudget = 3))
        assertFalse(isSearchQueryExhausted(hasNextPage = true, pagesLoaded = 100, pageBudget = null))
        assertNull(
            nextSearchQueryIndex(
                currentIndex = 0,
                states = listOf(SearchQueryPageState(exhausted = true)),
            ),
        )
    }
}
