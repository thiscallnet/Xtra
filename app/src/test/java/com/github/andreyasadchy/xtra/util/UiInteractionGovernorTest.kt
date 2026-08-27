package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiInteractionGovernorTest {

    @Test
    fun visibleImagesStillHaveBudgetDuringInteraction() {
        assertTrue(
            visibleImageStartsPerFrame(
                interacting = true,
            ) > 0,
        )
    }

    @Test
    fun idleAllowsMoreImageStartsThanInteraction() {
        val interacting =
            visibleImageStartsPerFrame(
                interacting = true,
            )

        val idle =
            visibleImageStartsPerFrame(
                interacting = false,
            )

        assertEquals(1, interacting)
        assertEquals(2, idle)
        assertTrue(idle > interacting)
    }
}
