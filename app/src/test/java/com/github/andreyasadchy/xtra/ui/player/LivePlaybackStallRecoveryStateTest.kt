package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LivePlaybackStallRecoveryStateTest {
    @Test
    fun decoderFailuresKeepRecoveryDelayShortWhileOtherFailuresBackOff() {
        assertEquals(1_500L, LivePlaybackStallRecoveryState.recoveryDelayMs(attempt = 1, decoderFailure = false))
        assertEquals(24_000L, LivePlaybackStallRecoveryState.recoveryDelayMs(attempt = 5, decoderFailure = false))
        assertEquals(30_000L, LivePlaybackStallRecoveryState.recoveryDelayMs(attempt = 6, decoderFailure = false))
        assertEquals(1_500L, LivePlaybackStallRecoveryState.recoveryDelayMs(attempt = 5, decoderFailure = true))
    }

    @Test
    fun startupAndPostStartBufferingRecoverAfterTimeout() {
        val state = LivePlaybackStallRecoveryState(stallTimeoutMs = 30_000L)
        var generation = state.currentGeneration()

        state.onBufferingChanged(generation, isBuffering = true, nowMs = 0L)
        assertNull(state.claimStalledRecovery(generation, nowMs = 29_999L))
        assertEquals(1, state.claimStalledRecovery(generation, nowMs = 30_000L))

        generation = state.beginRecoveryGeneration()
        state.finishRecoveryAttempt(sourceStarted = true)
        state.onPlaybackStarted(generation)
        state.onBufferingChanged(generation, isBuffering = true, nowMs = 40_000L)
        assertNull(state.claimStalledRecovery(generation, nowMs = 69_999L))
        assertEquals(2, state.claimStalledRecovery(generation, nowMs = 70_000L))
        assertNull(state.claimStalledRecovery(generation, nowMs = 80_000L))
    }

    @Test
    fun readyCancelsContinuousBufferingWithoutResettingRecoveryBudget() {
        val state = LivePlaybackStallRecoveryState(stallTimeoutMs = 1L)
        var generation = state.currentGeneration()
        state.onPlaybackStarted(generation)
        state.onBufferingChanged(generation, isBuffering = true, nowMs = 1L)
        assertEquals(1, state.claimStalledRecovery(generation, nowMs = 2L))
        generation = state.beginRecoveryGeneration()
        state.onBufferingChanged(generation, isBuffering = false, nowMs = 4L)
        assertEquals(1, state.recoveryAttempts())
        state.finishRecoveryAttempt(sourceStarted = true)
        state.onPlaybackStarted(generation)
        state.onBufferingChanged(generation, isBuffering = true, nowMs = 5L)
        assertEquals(2, state.claimStalledRecovery(generation, nowMs = 6L))
        assertNull(state.claimStalledRecovery(generation, nowMs = 7L))
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
    fun retriesContinueAfterPlaybackResumes() {
        val state = LivePlaybackStallRecoveryState()
        assertEquals(1, state.claimErrorRecovery())
        assertNull(state.claimErrorRecovery())

        val generation = state.beginRecoveryGeneration()
        state.onPlaybackStarted(generation)
        assertEquals(2, state.claimErrorRecovery())
    }

    @Test
    fun duplicateRecoveryEventsDoNotConsumeBudgetWhileRecoveryIsPending() {
        val state = LivePlaybackStallRecoveryState()

        assertEquals(1, state.claimErrorRecovery())
        assertNull(state.claimErrorRecovery(recoveryPending = true))
        assertNull(state.claimErrorRecovery(recoveryPending = true))
        assertEquals(1, state.recoveryAttempts())

        val generation = state.beginRecoveryGeneration()
        state.onPlaybackStarted(generation)
        assertEquals(2, state.claimErrorRecovery())
        assertEquals(2, state.recoveryAttempts())
    }

    @Test
    fun endedRecoveryBudgetSurvivesPlaybackAndResetsAfterStablePlayback() {
        val state = LivePlaybackStallRecoveryState()
        var generation = state.currentGeneration()

        state.onPlaybackStarted(generation, nowMs = 0L)
        assertEquals(1, state.claimEndedRecovery(generation, nowMs = 28_000L))

        generation = state.beginRecoveryGeneration()
        state.onPlaybackStarted(generation, nowMs = 30_000L)
        assertEquals(2, state.claimEndedRecovery(generation, nowMs = 58_000L))

        generation = state.beginRecoveryGeneration()
        state.onPlaybackStarted(generation, nowMs = 60_000L)
        assertEquals(3, state.claimEndedRecovery(generation, nowMs = 88_000L))
        assertEquals(3, state.endedRecoveryAttempts())

        generation = state.beginRecoveryGeneration()
        state.onPlaybackStarted(generation, nowMs = 90_000L)
        assertEquals(1, state.claimEndedRecovery(generation, nowMs = 210_000L))
        assertEquals(1, state.endedRecoveryAttempts())
    }

    @Test
    fun endedRecoveryBudgetResetsForNewUserPlaybackGeneration() {
        val state = LivePlaybackStallRecoveryState()
        val oldGeneration = state.currentGeneration()

        state.onPlaybackStarted(oldGeneration, nowMs = 0L)
        assertEquals(1, state.claimEndedRecovery(oldGeneration, nowMs = 1L))
        val newGeneration = state.beginUserGeneration()

        assertNull(state.claimEndedRecovery(oldGeneration, nowMs = 2L))
        assertEquals(1, state.claimEndedRecovery(newGeneration, nowMs = 2L))
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
