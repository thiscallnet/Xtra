package com.github.andreyasadchy.xtra.ui.multiview

import com.github.andreyasadchy.xtra.model.ui.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MultiviewSessionReducerTest {
    private val first = Stream(id = "stream-1", channelId = "100", channelLogin = "Alpha", channelName = "Alpha")
    private val second = Stream(id = "stream-2", channelId = "200", channelLogin = "Beta", channelName = "Beta")
    private val third = Stream(id = "stream-3", channelId = "300", channelLogin = "Gamma", channelName = "Gamma")

    @Test
    fun addUsesStableIdentityAndCapsAtFour() {
        var state = MultiviewSessionState()
        state = MultiviewSessionReducer.add(state, first)
        state = MultiviewSessionReducer.add(
            state,
            Stream(id = "another-id", channelId = "100", channelLogin = "Alpha", channelName = "Alpha"),
        )

        assertEquals(1, state.streams.size)
        assertEquals("id:100", state.activeIdentity)
        assertEquals("id:100", state.chatIdentity)
    }

    @Test
    fun removeActiveAndFocusedStreamChoosesRemainingFallback() {
        var state = MultiviewSessionState()
        state = MultiviewSessionReducer.add(state, first)
        state = MultiviewSessionReducer.add(state, second)
        state = state.copy(focusedIdentity = "id:200")

        state = MultiviewSessionReducer.remove(state, "id:100")

        assertEquals(listOf("id:200"), state.identities)
        assertEquals("id:200", state.activeIdentity)
        assertEquals("id:200", state.focusedIdentity)
    }

    @Test
    fun reorderChangesOrderWithoutChangingIdentity() {
        var state = MultiviewSessionState()
        state = MultiviewSessionReducer.add(state, first)
        state = MultiviewSessionReducer.add(state, second)
        state = MultiviewSessionReducer.add(state, third)
        val beforeActive = state.activeIdentity

        state = MultiviewSessionReducer.reorder(state, "id:300", 0)

        assertEquals(listOf("id:300", "id:100", "id:200"), state.identities)
        assertEquals(beforeActive, state.activeIdentity)
        assertNotEquals(state.streams[0].id, state.streams[1].id)
    }

    @Test
    fun activeChangesOnlyToExistingStream() {
        var state = MultiviewSessionState()
        state = MultiviewSessionReducer.add(state, first)

        state = MultiviewSessionReducer.setActive(state, "id:missing")
        assertEquals("id:100", state.activeIdentity)

        state = MultiviewSessionReducer.setActive(state, "id:100")
        assertEquals("id:100", state.activeIdentity)
    }
}
