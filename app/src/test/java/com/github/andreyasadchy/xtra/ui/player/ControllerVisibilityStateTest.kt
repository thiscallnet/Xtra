package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerVisibilityStateTest {

    @Test
    fun rapidTapsToggleAgainstTargetVisibility() {
        val state = ControllerVisibilityState()

        assertTrue(state.toggle(hideOnTouch = true))
        assertFalse(state.toggle(hideOnTouch = true))
        assertTrue(state.toggle(hideOnTouch = true))
    }

    @Test
    fun pausedPlaybackDoesNotScheduleAutoHide() {
        val state = ControllerVisibilityState()
        state.show()
        state.setAutoHideEnabled(false)

        assertFalse(state.shouldScheduleHide(
            hideOnTouch = true,
            interactionLocked = false,
            progressPressed = false,
            rootVisible = true,
        ))
    }

    @Test
    fun scrubCancelsTimerAndSuccessfulStopRestoresItWhenPlaying() {
        val state = ControllerVisibilityState()
        state.show()

        state.onScrubStart()
        assertFalse(state.shouldScheduleHide(
            hideOnTouch = true,
            interactionLocked = false,
            progressPressed = false,
            rootVisible = true,
        ))

        state.onScrubStop()
        assertTrue(state.shouldScheduleHide(
            hideOnTouch = true,
            interactionLocked = false,
            progressPressed = false,
            rootVisible = true,
        ))
    }

    @Test
    fun scrubStopKeepsPausedControlsVisibleWithoutTimer() {
        val state = ControllerVisibilityState()
        state.show()
        state.setAutoHideEnabled(false)
        state.onScrubStart()
        state.onScrubStop()

        assertFalse(state.shouldScheduleHide(
            hideOnTouch = true,
            interactionLocked = false,
            progressPressed = false,
            rootVisible = true,
        ))
        assertTrue(state.targetVisible)
    }
}
