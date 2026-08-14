package com.github.andreyasadchy.xtra.ui.multiview

import androidx.lifecycle.SavedStateHandle
import com.github.andreyasadchy.xtra.model.ui.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiviewViewModelTest {
    private val initialStream = Stream(
        id = "stream-1",
        channelId = "100",
        channelLogin = "Alpha",
        channelName = "Alpha",
    )
    private val otherStream = Stream(
        id = "stream-2",
        channelId = "200",
        channelLogin = "Beta",
        channelName = "Beta",
    )

    @Test
    fun restoredActiveStreamIsNotResetBySecondInitialize() {
        val handle = SavedStateHandle()
        var state = applyInitialStream(handle, MultiviewSessionState())
        state = MultiviewSessionReducer.add(state, otherStream, initialAudioVolume = 0f)
        state = MultiviewSessionReducer.setActive(state, "id:200")

        val restoredState = applyInitialStream(recreateHandle(handle), state)

        assertEquals("id:200", restoredState.activeIdentity)
        assertEquals(listOf("id:100", "id:200"), restoredState.identities)
    }

    @Test
    fun removedInitialStreamIsNotReaddedDuringRecreation() {
        val handle = SavedStateHandle()
        var state = applyInitialStream(handle, MultiviewSessionState())
        state = MultiviewSessionReducer.remove(state, "id:100")

        val restoredState = applyInitialStream(recreateHandle(handle), state)

        assertEquals(emptyList<String>(), restoredState.identities)
    }

    private fun applyInitialStream(
        handle: SavedStateHandle,
        state: MultiviewSessionState,
    ): MultiviewSessionState {
        return MultiviewViewModel.initializeStateOnce(
            savedStateHandle = handle,
            state = state,
            initialStream = initialStream,
            initialAudioVolume = { 0f },
        ) ?: state
    }

    private fun recreateHandle(handle: SavedStateHandle): SavedStateHandle {
        return SavedStateHandle(
            handle.keys().associateWith { key -> handle.get<Any?>(key) },
        )
    }
}
