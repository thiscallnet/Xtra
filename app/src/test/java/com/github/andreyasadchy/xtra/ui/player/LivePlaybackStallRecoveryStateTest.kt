package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackStallRecoveryStateTest {
    @Test
    fun startupBufferingDoesNotRecoverButPostStartStallDoes() {
        val state = LivePlaybackStallRecoveryState(stallTimeoutMs = 30_000L)
        val generation = state.currentGeneration()

        state.onBufferingChanged(generation, isBuffering = true, nowMs = 0L)
        assertNull(state.claimStalledRecovery(generation, nowMs = 30_000L))

        state.onPlaybackStarted(generation)
        state.onBufferingChanged(generation, isBuffering = true, nowMs = 40_000L)
        assertNull(state.claimStalledRecovery(generation, nowMs = 69_999L))
        assertEquals(1, state.claimStalledRecovery(generation, nowMs = 70_000L))
        assertNull(state.claimStalledRecovery(generation, nowMs = 80_000L))
    }

    @Test
    fun readyCancelsContinuousBufferingWithoutResettingRecoveryBudget() {
        val state = LivePlaybackStallRecoveryState(stallTimeoutMs = 1L, maxAttempts = 2)
        var generation = state.currentGeneration()
        state.onPlaybackStarted(generation)
        state.onBufferingChanged(generation, isBuffering = true, nowMs = 1L)
        assertEquals(1, state.claimStalledRecovery(generation, nowMs = 2L))
        generation = state.beginRecoveryGeneration()
        state.onBufferingChanged(generation, isBuffering = false, nowMs = 4L)
        assertEquals(1, state.recoveryAttempts())
        state.onPlaybackStarted(generation)
        assertEquals(0, state.recoveryAttempts())
        state.onBufferingChanged(generation, isBuffering = true, nowMs = 5L)
        assertEquals(1, state.claimStalledRecovery(generation, nowMs = 6L))
        assertNull(state.claimStalledRecovery(generation, nowMs = 7L))
        assertFalse(state.isRecoveryExhausted())
    }

    @Test
    fun userGenerationInvalidatesOldTimeoutAndResetsBudget() {
        val state = LivePlaybackStallRecoveryState(stallTimeoutMs = 1L)
        val staleGeneration = state.currentGeneration()
        state.onPlaybackStarted(staleGeneration)
        state.onBufferingChanged(staleGeneration, isBuffering = true, nowMs = 0L)
        val currentGeneration = state.beginUserGeneration()

        assertFalse(state.onBufferingChanged(staleGeneration, isBuffering = true, nowMs = 1L))
        assertNull(state.claimStalledRecovery(staleGeneration, nowMs = 2L))
        assertNull(state.claimStalledRecovery(currentGeneration, nowMs = 2L))
    }

    @Test
    fun recoveryBudgetIsSharedWithTerminalErrorsAndResetsAfterPlaybackResumes() {
        val state = LivePlaybackStallRecoveryState(maxAttempts = 1)
        assertEquals(1, state.claimErrorRecovery())
        assertNull(state.claimErrorRecovery())

        val generation = state.beginRecoveryGeneration()
        state.onPlaybackStarted(generation)
        assertFalse(state.isRecoveryExhausted())
        assertEquals(1, state.claimErrorRecovery())
    }

    @Test
    fun duplicateRecoveryEventsDoNotConsumeBudgetWhileRecoveryIsPending() {
        val state = LivePlaybackStallRecoveryState(maxAttempts = 3)

        assertEquals(1, state.claimErrorRecovery())
        assertNull(state.claimErrorRecovery(recoveryPending = true))
        assertNull(state.claimErrorRecovery(recoveryPending = true))
        assertEquals(1, state.recoveryAttempts())

        val generation = state.beginRecoveryGeneration()
        state.onPlaybackStarted(generation)
        assertEquals(1, state.claimErrorRecovery())
        assertEquals(1, state.recoveryAttempts())
    }

    @Test
    fun strictQualityRestoreDoesNotChooseNearbyRendition() {
        val identity = SourceSwitchQualityIdentity("1080p60", "avc1", 6_000_000)
        val qualities = listOf(
            VideoQuality("720p60", "avc1", 4_500_000, "720.m3u8"),
            VideoQuality("1080p60", "avc1", 6_000_000, "1080.m3u8"),
        )

        assertEquals("1080.m3u8", identity.resolveExact(qualities)?.url)
        assertNull(identity.resolveExact(qualities - qualities.last()))
    }
}
