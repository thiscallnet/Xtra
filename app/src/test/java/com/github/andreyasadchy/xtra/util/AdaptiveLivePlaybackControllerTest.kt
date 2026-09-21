package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLivePlaybackControllerTest {

    @Test
    fun rebufferingMovesThroughLargerPolicies() {
        val controller = AdaptiveLivePlaybackController(LivePlaybackPolicies.LOW_LATENCY)

        assertTrue(controller.onRebuffer(1_000L))
        assertEquals(LivePlaybackPolicies.BUFFERED, controller.currentPolicy())
        assertTrue(controller.onRebuffer(2_000L))
        assertEquals(LivePlaybackPolicies.RECOVERING, controller.currentPolicy())
    }

    @Test
    fun recoveryNeedsAFullStableWindow() {
        val controller = AdaptiveLivePlaybackController(LivePlaybackPolicies.LOW_LATENCY)
        controller.onRebuffer(1_000L)

        assertFalse(controller.onStableSample(6_000L, 30_000L))
        assertFalse(controller.onStableSample(6_000L, 31_000L))
        assertTrue(controller.onStableSample(6_000L, 121_000L))
        assertEquals(LivePlaybackPolicies.LOW_LATENCY, controller.currentPolicy())
    }

    @Test
    fun repeatedLoadControlCallbacksDoNotCountTheSameRebuffer() {
        val controller = AdaptiveLivePlaybackController(LivePlaybackPolicies.LOW_LATENCY)

        assertTrue(controller.onRebuffer(1_000L))
        assertFalse(controller.onRebuffer(1_000L))
        assertTrue(controller.onRebuffer(2_000L))
        assertEquals(LivePlaybackPolicies.RECOVERING, controller.currentPolicy())
    }

    @Test
    fun disabledAdaptationNeverLeavesTheNormalPolicy() {
        val controller = AdaptiveLivePlaybackController(
            initialPolicy = LivePlaybackPolicies.NORMAL,
            adaptiveEnabled = false,
        )

        assertFalse(controller.onRebuffer(1_000L))
        assertFalse(controller.onStableSample(30_000L, 100_000L))
        assertEquals(LivePlaybackPolicies.NORMAL, controller.currentPolicy())
    }

    @Test
    fun recoveryPromotesOneStatePerStableWindow() {
        val controller = AdaptiveLivePlaybackController(LivePlaybackPolicies.LOW_LATENCY)
        controller.onRebuffer(1_000L)
        controller.onRebuffer(2_000L)

        assertFalse(controller.onStableSample(6_000L, 32_000L))
        assertTrue(controller.onStableSample(6_000L, 122_000L))
        assertEquals(LivePlaybackPolicies.BUFFERED, controller.currentPolicy())
        assertFalse(controller.onStableSample(4_000L, 123_000L))
        assertTrue(controller.onStableSample(4_000L, 213_000L))
        assertEquals(LivePlaybackPolicies.LOW_LATENCY, controller.currentPolicy())
    }
}
