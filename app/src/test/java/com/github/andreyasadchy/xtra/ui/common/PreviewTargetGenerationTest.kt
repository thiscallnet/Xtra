package com.github.andreyasadchy.xtra.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTargetGenerationTest {
    @Test
    fun recyclingTargetInvalidatesAnInFlightPreviewBind() {
        val target = Any()
        val generations = PreviewTargetGeneration<Any>()
        val bindGeneration = generations.capture(target)

        generations.invalidate(target)

        assertFalse(generations.isCurrent(target, bindGeneration))
        val reboundGeneration = generations.capture(target)
        assertTrue(generations.isCurrent(target, reboundGeneration))
    }
}
