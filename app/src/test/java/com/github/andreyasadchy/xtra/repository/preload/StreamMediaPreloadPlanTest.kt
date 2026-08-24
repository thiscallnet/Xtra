package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMediaPreloadPlanTest {
    @Test
    fun rankedCandidatesMapToSampleTracksAndSourceDepth() {
        val plan = StreamMediaPreloadPlan.reconcile(
            existing = emptyList(),
            candidates = listOf(
                MediaPreloadPlanEntry("first", "one", 0),
                MediaPreloadPlanEntry("second", "two", 1),
                MediaPreloadPlanEntry("third", "three", 2),
            ),
        )

        assertEquals(listOf(0, 1, 2), plan.added.map { it.rank })
    }

    @Test
    fun candidateLeavingRankingIsRemoved() {
        val old = MediaPreloadPlanEntry("old", "old-url", 2)
        val plan = StreamMediaPreloadPlan.reconcile(
            existing = listOf(old),
            candidates = emptyList(),
        )

        assertEquals(listOf(old), plan.removed)
        assertTrue(plan.retained.isEmpty())
    }

    @Test
    fun urlReplacementAndRankChangeReplaceTheOldSource() {
        val old = MediaPreloadPlanEntry("foo", "signed-old", 0, samplesLoadedAtMs = 1_000)
        val plan = StreamMediaPreloadPlan.reconcile(
            existing = listOf(old),
            candidates = listOf(MediaPreloadPlanEntry("FOO", "signed-new", 1)),
        )

        assertEquals(listOf(old), plan.removed)
        assertEquals(listOf("signed-new"), plan.added.map { it.url })
    }

    @Test
    fun rankedSampleIsRetainedUntilTheCandidateChanges() {
        val old = MediaPreloadPlanEntry("foo", "signed", 0, samplesLoadedAtMs = 1_000)
        val plan = StreamMediaPreloadPlan.reconcile(
            existing = listOf(old),
            candidates = listOf(MediaPreloadPlanEntry("foo", "signed", 0)),
        )

        assertEquals(listOf(old), plan.retained)
        assertTrue(plan.removed.isEmpty())
        assertTrue(plan.added.isEmpty())
    }

    @Test
    fun preloadTargetIsExplicitlyBounded() {
        assertEquals(32 * 1024 * 1024, StreamMedia3Runtime.PRELOAD_TARGET_BYTES)
        assertEquals(1_800L, StreamMedia3Runtime.SAMPLE_PRELOAD_DURATION_MS)
    }

    @Test
    fun handoffUsesOnlyTheExactCurrentGenerationAndUrl() {
        val entry = MediaPreloadPlanEntry("foo", "signed-url", 0, samplesLoadedAtMs = 1_000)

        assertTrue(StreamMediaPreloadHandoff.isUsable(entry, "FOO", "signed-url", true, nowMs = 5_000))
        assertTrue(!StreamMediaPreloadHandoff.isUsable(entry, "foo", "different-url", true, nowMs = 5_000))
        assertTrue(!StreamMediaPreloadHandoff.isUsable(entry, "foo", "signed-url", false, nowMs = 5_000))
        assertTrue(StreamMediaPreloadHandoff.isUsable(entry, "foo", "signed-url", true, nowMs = 5_000))
    }

    @Test
    fun oldRankZeroSamplesAreNotUsedForLivePlaybackHandoff() {
        val entry = MediaPreloadPlanEntry("foo", "signed-url", 0, samplesLoadedAtMs = 1_000)

        assertTrue(
            StreamMediaPreloadHandoff.isUsable(
                entry,
                "foo",
                "signed-url",
                configurationMatches = true,
                nowMs = 1_000 + StreamMediaPreloadHandoff.MAX_PRELOADED_HANDOFF_AGE_MS,
            ),
        )
        assertTrue(
            !StreamMediaPreloadHandoff.isUsable(
                entry,
                "foo",
                "signed-url",
                configurationMatches = true,
                nowMs = 1_001 + StreamMediaPreloadHandoff.MAX_PRELOADED_HANDOFF_AGE_MS,
            ),
        )
    }
}
