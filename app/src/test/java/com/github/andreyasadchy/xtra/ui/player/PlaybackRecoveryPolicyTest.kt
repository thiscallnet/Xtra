package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryPolicyTest {

    @Test
    fun repeatedFailuresKeepExponentialBackoffUntilReady() {
        val policy = PlaybackRecoveryPolicy()

        assertEquals(500L, policy.nextDelayMs())
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(4_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
        assertEquals(8_000L, policy.nextDelayMs())
        assertEquals(4, policy.attempt)
    }

    @Test
    fun readyResetStartsTheNextFailureAtInitialDelay() {
        val policy = PlaybackRecoveryPolicy()

        policy.nextDelayMs()
        policy.nextDelayMs()
        policy.reset()

        assertEquals(0, policy.attempt)
        assertEquals(500L, policy.nextDelayMs())
    }
}
