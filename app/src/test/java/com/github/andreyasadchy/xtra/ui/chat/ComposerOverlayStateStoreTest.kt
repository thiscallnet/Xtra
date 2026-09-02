package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerOverlayStateStoreTest {
    private data class RestoreState(
        val draft: String,
        val selection: Int,
        val replyId: String?,
    )

    @Test
    fun unsentRewardInputSurvivesRecreation() {
        val store = ComposerOverlayStateStore<String, RestoreState>()
        val restore = RestoreState("normal draft", 6, "reply-1")

        store.open("reward", restore)
        store.set(
            captureComposerOverlaySnapshot(
                overlay = "reward",
                existing = store.active,
                pendingRestoreState = null,
                pendingInput = null,
                currentInput = "reward text",
                submissionPending = false,
            )!!,
        )

        // This is the state that onViewCreated() reads after onDestroyView().
        val recreated = store.active
        assertNotNull(recreated)
        assertEquals("reward", recreated?.overlay)
        assertEquals("reward text", recreated?.input)
        assertFalse(recreated?.submissionPending ?: true)
        assertEquals(restore, recreated?.restoreState)
    }

    @Test
    fun pendingStreakRemainsLockedAndFailedStreakCanRestoreUnderlyingComposer() {
        val store = ComposerOverlayStateStore<String, RestoreState>()
        val restore = RestoreState("reply draft", 4, "reply-2")

        store.open("streak", restore)
        val pending = store.submit("streak text")
        assertTrue(pending?.submissionPending == true)
        assertEquals("streak text", pending?.input)
        assertEquals(restore, pending?.restoreState)

        store.set(
            captureComposerOverlaySnapshot(
                overlay = "streak",
                existing = store.active,
                pendingRestoreState = pending?.restoreState,
                pendingInput = pending?.input,
                currentInput = "",
                submissionPending = true,
            )!!,
        )
        val recreated = store.active
        assertTrue(recreated?.submissionPending == true)
        assertEquals("streak text", recreated?.input)

        val failed = store.markFailed("streak text")
        assertFalse(failed?.submissionPending ?: true)
        assertEquals("streak text", failed?.input)

        store.set(
            captureComposerOverlaySnapshot(
                overlay = "streak",
                existing = store.active,
                pendingRestoreState = null,
                pendingInput = null,
                currentInput = failed?.input,
                submissionPending = false,
            )!!,
        )
        assertFalse(store.active?.submissionPending ?: true)
        assertEquals("streak text", store.active?.input)
        assertEquals(restore, store.clear())
        assertNull(store.active)
    }
}
