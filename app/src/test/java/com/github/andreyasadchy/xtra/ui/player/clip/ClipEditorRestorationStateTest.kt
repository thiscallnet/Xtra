package com.github.andreyasadchy.xtra.ui.player.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipEditorRestorationStateTest {
    @Test
    fun restoredChildDirectoryIsAuthoritativeWhenParentAndChildStateExist() {
        val state = ClipEditorRestorationState("/cache/parent", "/cache/child")

        assertTrue(state.shouldRestoreEditor)
        assertEquals("/cache/child", state.directoryPath)
        assertNull(state.orphanDirectoryPath)
        assertEquals("/cache/parent", state.staleParentDirectoryPath)
    }

    @Test
    fun recoversDirectoryFromRestoredChildWhenParentPathWasNotSaved() {
        val state = ClipEditorRestorationState(null, "/cache/child")

        assertTrue(state.shouldRestoreEditor)
        assertEquals("/cache/child", state.directoryPath)
        assertNull(state.orphanDirectoryPath)
        assertNull(state.staleParentDirectoryPath)
    }

    @Test
    fun identifiesPreparedDirectoryWithoutRestoredEditorAsOrphan() {
        val state = ClipEditorRestorationState("/cache/orphan", null)

        assertFalse(state.shouldRestoreEditor)
        assertEquals("/cache/orphan", state.orphanDirectoryPath)
        assertNull(state.staleParentDirectoryPath)
        assertTrue(state.hasState)
    }

    @Test
    fun emptyStateDoesNotChangeParentPlayback() {
        val state = ClipEditorRestorationState(null, null)

        assertFalse(state.shouldRestoreEditor)
        assertNull(state.directoryPath)
        assertNull(state.orphanDirectoryPath)
        assertNull(state.staleParentDirectoryPath)
        assertFalse(state.hasState)
    }
}
