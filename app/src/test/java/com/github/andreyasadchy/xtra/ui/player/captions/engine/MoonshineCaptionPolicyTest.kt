package com.github.andreyasadchy.xtra.ui.player.captions.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MoonshineCaptionPolicyTest {

    @Test
    fun `moonshine partials default to one second`() {
        assertEquals(1_000L, DEFAULT_MOONSHINE_PARTIAL_INTERVAL_MS)
    }

    @Test
    fun `active speech is periodically decoded`() {
        assertEquals(
            true,
            shouldDecodeMoonshinePartial(
                speechActive = true,
                utteranceSize = 1,
                nowMs = 1_000,
                nextPartialDecodeMs = 1_000,
            ),
        )
    }
}
