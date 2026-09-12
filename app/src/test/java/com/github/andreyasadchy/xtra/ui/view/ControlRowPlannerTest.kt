package com.github.andreyasadchy.xtra.ui.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRowPlannerTest {

    @Test
    fun `crowded controls wrap without exceeding the shared row budget`() {
        val lines = ControlRowPlanner.lineBreak(
            availableWidth = 100,
            itemWidths = listOf(40, 40, 40, 40),
            spacing = 8,
        )

        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), lines)
        assertTrue(lines.all { line ->
            line.sumOf { index -> 40 } + (line.size - 1).coerceAtLeast(0) * 8 <= 100
        })
    }
}
