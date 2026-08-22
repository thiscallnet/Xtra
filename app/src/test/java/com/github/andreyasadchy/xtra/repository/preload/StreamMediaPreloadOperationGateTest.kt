package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMediaPreloadOperationGateTest {
    @Test
    fun resolverCompletionQueuedBeforeScrollingCannotApplyAfterClear() {
        val gate = StreamMediaPreloadOperationGate()
        val resolverEpoch = gate.begin()
        gate.invalidate() // setViewportScrolling(true) / clearMediaPreloads()
        var reconciles = 0

        assertFalse(gate.runIfCurrent(resolverEpoch) { reconciles++ })
        assertEquals(0, reconciles)
    }

    @Test
    fun resolverCompletionQueuedBeforeBackgroundCannotApplyAfterClear() {
        val gate = StreamMediaPreloadOperationGate()
        val resolverEpoch = gate.begin()
        gate.invalidate() // onAppBackground() / clearMediaPreloads()

        assertFalse(gate.runIfCurrent(resolverEpoch) {})
    }

    @Test
    fun playbackEntryInvalidatesStaleReconcileButAllowsTheSelectedClear() {
        val gate = StreamMediaPreloadOperationGate()
        val staleResolverEpoch = gate.begin()
        gate.invalidate() // onPlaybackEntered()
        val selectedClearEpoch = gate.begin()
        var selectedClearApplied = false

        assertFalse(gate.runIfCurrent(staleResolverEpoch) { error("stale reconcile applied") })
        assertTrue(gate.runIfCurrent(selectedClearEpoch) { selectedClearApplied = true })
        assertTrue(selectedClearApplied)
    }

    @Test
    fun newestReconcileSupersedesAnOlderQueuedClear() {
        val gate = StreamMediaPreloadOperationGate()
        val clearEpoch = gate.begin()
        val currentReconcileEpoch = gate.begin()
        var clearApplied = false
        var reconcileApplied = false

        assertFalse(gate.runIfCurrent(clearEpoch) { clearApplied = true })
        assertTrue(gate.runIfCurrent(currentReconcileEpoch) { reconcileApplied = true })
        assertFalse(clearApplied)
        assertTrue(reconcileApplied)
    }
}
