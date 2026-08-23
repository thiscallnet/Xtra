package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreviewSelectionPolicyTest {
    @Test
    fun firstPartiallyVisibleFeedItemIsEligible() {
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = listOf(
                StreamPreviewSelectionCandidate("first", visibleFraction = 0.34f, centerProximity = 0.1f, order = 0),
                StreamPreviewSelectionCandidate("second", visibleFraction = 0.90f, centerProximity = 0.9f, order = 1),
            ),
            activeIdentities = emptySet(),
        )

        assertTrue(selected.contains("first"))
    }

    @Test
    fun twoEligibleCardsCanBeSelectedAndThirdIsCapped() {
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = listOf(
                StreamPreviewSelectionCandidate("first", 0.90f, 0.5f, 0),
                StreamPreviewSelectionCandidate("second", 0.80f, 0.5f, 1),
                StreamPreviewSelectionCandidate("third", 0.70f, 0.5f, 2),
            ),
            activeIdentities = emptySet(),
        )

        assertEquals(listOf("first", "second"), selected)
        assertFalse(selected.contains("third"))
    }

    @Test
    fun multiplePreviewToggleCanLimitSelectionToOneCard() {
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = listOf(
                StreamPreviewSelectionCandidate("first", 0.90f, 0.5f, 0),
                StreamPreviewSelectionCandidate("second", 0.80f, 0.5f, 1),
            ),
            activeIdentities = emptySet(),
            maxActivePreviews = 1,
        )

        assertEquals(listOf("first"), selected)
    }

    @Test
    fun activeCardIsRetainedWhenCandidatesAreNearlyTied() {
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = listOf(
                StreamPreviewSelectionCandidate("active", 0.40f, 0.1f, 0),
                StreamPreviewSelectionCandidate("new", 0.44f, 1.0f, 1),
            ),
            activeIdentities = setOf("active"),
            maxActivePreviews = 1,
        )

        assertEquals(listOf("active"), selected)
    }

    @Test
    fun clearlyBetterNewCardReplacesBarelyVisibleActiveCard() {
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = listOf(
                StreamPreviewSelectionCandidate("active", 0.13f, 0.1f, 0),
                StreamPreviewSelectionCandidate("new", 0.95f, 1.0f, 1),
            ),
            activeIdentities = setOf("active"),
            maxActivePreviews = 1,
        )

        assertEquals(listOf("new"), selected)
    }

    @Test
    fun clearlyBetterNewCardsCanReplaceStaleActiveCards() {
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = listOf(
                StreamPreviewSelectionCandidate("active-a", 0.13f, 0.1f, 0),
                StreamPreviewSelectionCandidate("active-b", 0.14f, 0.1f, 1),
                StreamPreviewSelectionCandidate("new-a", 0.95f, 1.0f, 2),
                StreamPreviewSelectionCandidate("new-b", 0.90f, 0.9f, 3),
            ),
            activeIdentities = setOf("active-a", "active-b"),
        )

        assertEquals(listOf("new-a", "new-b"), selected)
    }

    @Test
    fun duplicateChannelAcrossViewportsOnlyUsesOneSelectionSlot() {
        val selected = StreamPreviewSelectionPolicy.select(
            candidates = listOf(
                StreamPreviewSelectionCandidate("channel-a", 0.40f, 0.2f, 0),
                StreamPreviewSelectionCandidate("CHANNEL-A", 0.95f, 0.9f, 1),
                StreamPreviewSelectionCandidate("channel-b", 0.80f, 0.5f, 2),
            ),
            activeIdentities = emptySet(),
        )

        assertEquals(2, selected.size)
        assertEquals(1, selected.count { it == "channel-a" })
    }
}
