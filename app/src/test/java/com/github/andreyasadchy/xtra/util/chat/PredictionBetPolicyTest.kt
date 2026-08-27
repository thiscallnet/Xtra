package com.github.andreyasadchy.xtra.util.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionBetPolicyTest {
    @Test
    fun `2 through 10 outcomes are wagerable`() {
        assertTrue(PredictionBetPolicy.isPredictionWagerable(listOf("a", "b")))
        assertTrue(PredictionBetPolicy.isPredictionWagerable(listOf("a", "b", "c")))
        assertTrue(PredictionBetPolicy.isPredictionWagerable(listOf("a", "b", "c", "d")))
        assertTrue(
            PredictionBetPolicy.isPredictionWagerable(
                (1..10).map { "outcome-$it" },
            ),
        )
    }

    @Test
    fun `invalid outcome sets are not wagerable`() {
        assertFalse(PredictionBetPolicy.isPredictionWagerable(listOf("a")))
        assertFalse(PredictionBetPolicy.isPredictionWagerable(listOf("a", null, "c")))
        assertFalse(PredictionBetPolicy.isPredictionWagerable(listOf("a", "", "c")))
        assertFalse(
            PredictionBetPolicy.isPredictionWagerable(
                (1..11).map { "outcome-$it" },
            ),
        )
    }

    @Test
    fun `same selected outcome can receive more points`() {
        assertTrue(
            PredictionBetPolicy.canBetOutcome(
                selectedOutcomeId = "c",
                candidateOutcomeId = "c",
                inFlight = false,
            ),
        )
    }

    @Test
    fun `different outcome cannot be selected after first bet`() {
        assertFalse(
            PredictionBetPolicy.canBetOutcome(
                selectedOutcomeId = "c",
                candidateOutcomeId = "b",
                inFlight = false,
            ),
        )
    }

    @Test
    fun `nothing can be submitted while request is in flight`() {
        assertFalse(
            PredictionBetPolicy.canBetOutcome(
                selectedOutcomeId = "c",
                candidateOutcomeId = "c",
                inFlight = true,
            ),
        )
    }

    @Test
    fun `additional bets accumulate local confirmed total`() {
        assertEquals(
            350,
            PredictionBetPolicy.totalAfterAdditionalBet(
                previousAmount = 100,
                additionalPoints = 250,
            ),
        )
    }
}
