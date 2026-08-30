package com.github.andreyasadchy.xtra.ui.player.captions.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCaptionEnginePolicyTest {

    @Test
    fun `two pass forwards zipformer partials and moonshine finals only`() {
        val events = mergeTwoPassEvents(
            streamingEvents = listOf(
                CaptionRecognitionEvent.Partial("low latency"),
                CaptionRecognitionEvent.Final("zipformer final"),
            ),
            finalizerEvents = listOf(
                CaptionRecognitionEvent.Partial("ignored moonshine partial"),
                CaptionRecognitionEvent.Final("corrected final"),
            ),
        )

        assertEquals(
            listOf(
                CaptionRecognitionEvent.Partial("low latency"),
                CaptionRecognitionEvent.Final("corrected final"),
            ),
            events,
        )
    }

    @Test
    fun `standalone moonshine partial policy remains enabled`() {
        assertEquals(
            true,
            shouldDecodeMoonshinePartial(
                emitPartials = true,
                speechActive = true,
                utteranceSize = 1,
                nowMs = 300,
                nextPartialDecodeMs = 300,
            ),
        )
    }

    @Test
    fun `hybrid moonshine partial policy is disabled`() {
        assertEquals(
            false,
            shouldDecodeMoonshinePartial(
                emitPartials = false,
                speechActive = true,
                utteranceSize = 1,
                nowMs = 300,
                nextPartialDecodeMs = 300,
            ),
        )
    }
}
