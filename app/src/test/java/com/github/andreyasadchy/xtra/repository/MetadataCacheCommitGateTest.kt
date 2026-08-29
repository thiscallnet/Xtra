package com.github.andreyasadchy.xtra.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCacheCommitGateTest {

    @Test
    fun staleNotificationFetchCannotUndoReadMutation() = runBlocking {
        val gate = MetadataCacheCommitGate()
        var isUnread = true

        val fetchGeneration = gate.generationAtStart()
        gate.commitMutation { isUnread = false }

        val committed = gate.commitFetch(fetchGeneration) { isUnread = true }

        assertFalse(committed)
        assertFalse(isUnread)
    }

    @Test
    fun staleNotificationFetchCannotUndoDismissMutation() = runBlocking {
        val gate = MetadataCacheCommitGate()
        var isPresent = true

        val fetchGeneration = gate.generationAtStart()
        gate.commitMutation { isPresent = false }

        val committed = gate.commitFetch(fetchGeneration) { isPresent = true }

        assertFalse(committed)
        assertFalse(isPresent)
    }

    @Test
    fun staleWhisperFetchCannotUndoReadMutation() = runBlocking {
        val gate = MetadataCacheCommitGate()
        var isUnread = true

        val fetchGeneration = gate.generationAtStart()
        gate.commitMutation { isUnread = false }

        val committed = gate.commitFetch(fetchGeneration) { isUnread = true }

        assertFalse(committed)
        assertFalse(isUnread)
    }

    @Test
    fun fetchCommitsWhenNoNewerMutationExists() = runBlocking {
        val gate = MetadataCacheCommitGate()
        var committedValue = false
        val fetchGeneration = gate.generationAtStart()

        assertTrue(gate.commitFetch(fetchGeneration) { committedValue = true })
        assertTrue(committedValue)
    }
}
