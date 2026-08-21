package com.github.andreyasadchy.xtra.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoOutputStateTest {
    @Test
    fun failedRestorationKeepsThePendingState() {
        val state = VideoOutputState()
        state.markDetachedForBackground()

        assertFalse(state.restoreIfNeeded { false })
        assertTrue(state.restoreIfNeeded { true })
        assertFalse(state.restoreIfNeeded { true })
    }

    @Test
    fun clearDropsPendingRestoration() {
        val state = VideoOutputState()
        state.markDetachedForBackground()
        state.clear()

        assertFalse(state.restoreIfNeeded { error("restore should not run") })
    }

    @Test
    fun repeatedDetachMarksStayPendingUntilRestored() {
        val state = VideoOutputState()
        state.markDetachedForBackground()
        state.markDetachedForBackground()

        var restoreCount = 0
        assertTrue(state.restoreIfNeeded { restoreCount++; true })
        assertFalse(state.restoreIfNeeded { restoreCount++; true })
        assertEquals(1, restoreCount)
    }

    @Test
    fun noPendingRestorationDoesNotInvokeCallback() {
        val state = VideoOutputState()

        assertFalse(state.restoreIfNeeded { error("restore should not run") })
    }
}
