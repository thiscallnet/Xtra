package com.github.andreyasadchy.xtra.ui.player.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineDropBaselineTest {

    @Test
    fun `drop metric is reset to a per-engine delta`() {
        val baseline = EngineDropBaseline(initialTotal = 7)

        assertEquals(3, baseline.delta(total = 10))

        baseline.reset(total = 10)

        assertEquals(0, baseline.delta(total = 10))
        assertEquals(2, baseline.delta(total = 12))
    }
}
