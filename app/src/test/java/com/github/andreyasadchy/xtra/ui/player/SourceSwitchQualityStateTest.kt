package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSwitchQualityStateTest {

    @Test
    fun reverseTransitionKeepsSelectionWhenClearedSourceHasNoQuality() {
        val state = SourceSwitchQualityState()

        state.capture("1080p60")
        state.capture(null)

        assertEquals("1080p60", state.consume())
        assertNull(state.consume())
    }

    @Test
    fun newerExplicitSelectionReplacesOlderPendingSelection() {
        val state = SourceSwitchQualityState()

        state.capture("1080p60")
        state.capture("720p60")

        assertEquals("720p60", state.consume())
    }

    @Test
    fun cancellationCleanupDoesNotLeakSelection() {
        val state = SourceSwitchQualityState()

        state.capture("1080p60")
        state.clear()

        assertNull(state.consume())
    }

    @Test
    fun failedTransitionCanRestoreTheCapturedSelectionBeforeClearingIt() {
        val state = SourceSwitchQualityState()

        state.capture("1080p60")
        val selectionForRollback = state.consume()

        assertEquals("1080p60", selectionForRollback)
        assertNull(state.consume())
    }

    @Test
    fun autoAndAudioOnlySelectionsArePreservedLikeNamedQualities() {
        val state = SourceSwitchQualityState()

        state.capture("auto")
        assertEquals("auto", state.consume())

        state.capture("audio_only")
        assertEquals("audio_only", state.consume())
    }
}
