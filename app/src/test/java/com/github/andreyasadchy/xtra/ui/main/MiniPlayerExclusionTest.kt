package com.github.andreyasadchy.xtra.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerExclusionTest {
    @Test
    fun repeatedCalculationIsIdempotent() {
        val first = calculateMiniPlayerBottomExclusion(navigationTop = 1000, playerTop = 760, gap = 16)

        assertEquals(first, calculateMiniPlayerBottomExclusion(navigationTop = 1000, playerTop = 760, gap = 16))
    }

    @Test
    fun existingHostMarginDoesNotChangeTheStableAnchorResult() {
        val expected = 256

        assertEquals(expected, calculateMiniPlayerBottomExclusion(navigationTop = 1000, playerTop = 760, gap = 16))
    }

    @Test
    fun movedPlayerChangesTheExclusionFromItsNewTop() {
        assertEquals(356, calculateMiniPlayerBottomExclusion(navigationTop = 1000, playerTop = 660, gap = 16))
        assertEquals(156, calculateMiniPlayerBottomExclusion(navigationTop = 1000, playerTop = 860, gap = 16))
    }

    @Test
    fun playerBelowTheNavigationBarNeedsNoExclusion() {
        assertEquals(0, calculateMiniPlayerBottomExclusion(navigationTop = 1000, playerTop = 1100, gap = 16))
    }
}
