package com.github.andreyasadchy.xtra.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePlaybackPolicyTest {

    @Test
    fun lowLatencyPolicyKeepsBufferHeadroom() {
        val policy = LivePlaybackPolicies.LOW_LATENCY

        assertEquals(1_500, policy.buffers.minBufferMs)
        assertEquals(6_000, policy.buffers.maxBufferMs)
        assertEquals(250, policy.buffers.bufferForPlaybackMs)
        assertEquals(500, policy.buffers.bufferForPlaybackAfterRebufferMs)
        assertEquals(2_000L, policy.targetOffsetMs)
    }
}
