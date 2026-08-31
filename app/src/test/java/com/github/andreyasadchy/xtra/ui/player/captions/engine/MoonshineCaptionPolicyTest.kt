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

    @Test
    fun `fast partial inference keeps configured interval`() {
        assertEquals(1_000L, nextMoonshinePartialIntervalMs(1_000L, 200L, false))
    }

    @Test
    fun `slow partial inference backs off`() {
        assertEquals(1_800L, nextMoonshinePartialIntervalMs(1_000L, 900L, false))
    }

    @Test
    fun `adaptive interval is capped`() {
        assertEquals(2_500L, nextMoonshinePartialIntervalMs(1_000L, 2_000L, false))
    }

    @Test
    fun `explicit two second preference remains first partial interval`() {
        assertEquals(2_000L, nextMoonshinePartialIntervalMs(2_000L, null, true))
    }

    @Test
    fun `rollover finalizes old chunk and sends boundary once to fresh vad`() {
        val utterance = FloatArray(8)
        val boundary = FloatArray(2)

        val transition = prepareMoonshineRollover(utterance, boundary, capacity = 9)

        assertEquals(utterance.toList(), transition.completedUtterance.toList())
        assertEquals(boundary.toList(), transition.boundaryWindowForVad.toList())
        assertEquals(false, transition.speechActiveBeforeFreshVad)
    }
}
