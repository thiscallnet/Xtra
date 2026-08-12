package com.github.andreyasadchy.xtra.ui.multiview.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewLayoutManagerTest {
    private val ids = listOf("a", "b", "c", "d")

    @Test
    fun autoThreeLandscapeMakesActiveStreamPrimary() {
        val plan = MultiviewLayoutManager.plan(ids.take(3), "c", MultiviewLayoutMode.AUTO, null, landscape = true)

        assertEquals(2, plan.rowCount)
        assertEquals(2, plan.columnCount)
        assertEquals(TilePlacement("c", 0, 0, rowSpan = 2), plan.placements.first())
        assertEquals(setOf("a", "b"), plan.placements.drop(1).map { it.identity }.toSet())
    }

    @Test
    fun autoThreePortraitPlacesPrimaryAboveTwoTiles() {
        val plan = MultiviewLayoutManager.plan(ids.take(3), "a", MultiviewLayoutMode.AUTO, null, landscape = false)

        assertEquals(TilePlacement("a", 0, 0, columnSpan = 2), plan.placements.first())
        assertTrue(plan.placements.drop(1).all { it.row == 1 })
    }

    @Test
    fun gridTreatsFourStreamsEqually() {
        val plan = MultiviewLayoutManager.plan(ids, "d", MultiviewLayoutMode.GRID, null, landscape = true)

        assertEquals(2, plan.rowCount)
        assertEquals(2, plan.columnCount)
        assertTrue(plan.placements.all { it.rowSpan == 1 && it.columnSpan == 1 })
    }

    @Test
    fun focusUsesFocusedIdentityAndKeepsAllThumbnailsInBounds() {
        val plan = MultiviewLayoutManager.plan(ids, "a", MultiviewLayoutMode.FOCUS, "d", landscape = false)

        assertEquals(TilePlacement("d", 0, 0, columnSpan = 3), plan.placements.first())
        assertTrue(plan.placements.drop(1).all { it.column in 0 until plan.columnCount })
    }

    @Test
    fun focusWithOneThumbnailUsesFullWidthRows() {
        val plan = MultiviewLayoutManager.plan(ids.take(2), "a", MultiviewLayoutMode.FOCUS, "b", landscape = true)

        assertEquals(2, plan.columnCount)
        assertEquals(TilePlacement("b", 0, 0, columnSpan = 2), plan.placements.first())
        assertEquals(TilePlacement("a", 1, 0, columnSpan = 2), plan.placements[1])
    }
}
