package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreviewLifecycleTest {
    @Test
    fun scrollingDoesNotAutomaticallyClearActivePreviews() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)

        lifecycle.onScrolling()

        assertEquals(setOf("channel-a"), lifecycle.activeIdentities())
    }

    @Test
    fun brieflyOffscreenCardSurvivesGracePeriod() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)

        lifecycle.observeVisible(emptySet(), nowMs = 100L)
        lifecycle.expire(nowMs = StreamPreviewLifecyclePolicy.OFFSCREEN_GRACE_MS - 1L)

        assertTrue(lifecycle.activeIdentities().contains("channel-a"))
    }

    @Test
    fun cardOffscreenLongerThanGraceIsRemoved() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)
        lifecycle.observeVisible(emptySet(), nowMs = 100L)

        lifecycle.expire(nowMs = 100L + StreamPreviewLifecyclePolicy.OFFSCREEN_GRACE_MS)

        assertFalse(lifecycle.activeIdentities().contains("channel-a"))
    }

    @Test
    fun offscreenCardPublishesFutureExpiryForCoordinatorReconciliation() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)
        lifecycle.observeVisible(emptySet(), nowMs = 100L)

        assertEquals(
            100L + StreamPreviewLifecyclePolicy.OFFSCREEN_GRACE_MS,
            lifecycle.nextExpiryAtMs(),
        )
    }

    @Test
    fun coordinatorReconciliationIsScheduledAndRunsWithoutAnotherViewportEvent() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)
        lifecycle.observeVisible(emptySet(), nowMs = 100L)
        var scheduledDelayMs: Long? = null
        var scheduledCallback: (() -> Unit)? = null
        var cancelled = false

        val reconciler = StreamPreviewLifecycleReconciler(
            lifecycle = lifecycle,
            schedule = { delayMs, callback ->
                scheduledDelayMs = delayMs
                scheduledCallback = callback
                { cancelled = true }
            },
            onExpired = {
                lifecycle.expire(100L + StreamPreviewLifecyclePolicy.OFFSCREEN_GRACE_MS)
            },
        )

        reconciler.reconcile(nowMs = 100L)
        scheduledCallback!!.invoke()

        assertEquals(StreamPreviewLifecyclePolicy.OFFSCREEN_GRACE_MS, scheduledDelayMs)
        assertFalse(lifecycle.activeIdentities().contains("channel-a"))
        assertFalse(cancelled)
    }

    @Test
    fun sameStreamReappearingDuringGraceKeepsItsActiveState() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)
        lifecycle.observeVisible(emptySet(), nowMs = 100L)
        lifecycle.observeVisible(setOf("channel-a"), nowMs = 300L)

        assertEquals(setOf("channel-a"), lifecycle.activeIdentities())
    }

    @Test
    fun onePreviewFailureDoesNotTerminateAnother() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)
        lifecycle.track("channel-b", nowMs = 0L)

        lifecycle.failed("channel-a")

        assertEquals(setOf("channel-b"), lifecycle.activeIdentities())
    }

    @Test
    fun fullscreenHandoffRetainsOnlyClickedStream() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)
        lifecycle.track("channel-b", nowMs = 0L)

        lifecycle.retainOnly("channel-b")

        assertEquals(setOf("channel-b"), lifecycle.activeIdentities())
    }

    @Test
    fun backgroundClearRemovesAllPreviewState() {
        val lifecycle = StreamPreviewLifecycle()
        lifecycle.track("channel-a", nowMs = 0L)
        lifecycle.track("channel-b", nowMs = 0L)

        lifecycle.clear()

        assertTrue(lifecycle.activeIdentities().isEmpty())
    }
}
