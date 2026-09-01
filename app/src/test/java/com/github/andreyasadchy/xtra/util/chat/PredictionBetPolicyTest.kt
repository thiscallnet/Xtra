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
    fun `third fourth and tenth outcome IDs stay candidate IDs`() {
        val outcomeIds = (1..10).map { "outcome-$it" }

        listOf("outcome-3", "outcome-4", "outcome-10").forEach { candidateId ->
            assertTrue(
                PredictionBetPolicy.canBetOutcome(
                    selectedOutcomeId = null,
                    candidateOutcomeId = candidateId,
                    inFlight = false,
                    confirmedAmount = 0,
                    minimumPoints = 10,
                    maximumPoints = 250_000,
                ),
            )
            assertTrue(candidateId in outcomeIds)
        }
        assertFalse(
            PredictionBetPolicy.canBetOutcome(
                selectedOutcomeId = "outcome-3",
                candidateOutcomeId = "outcome-4",
                inFlight = false,
                confirmedAmount = 10,
                minimumPoints = 10,
                maximumPoints = 250_000,
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
                confirmedAmount = 0,
                minimumPoints = 10,
                maximumPoints = 250_000,
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
                confirmedAmount = 0,
                minimumPoints = 10,
                maximumPoints = 250_000,
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
                confirmedAmount = 0,
                minimumPoints = 10,
                maximumPoints = 250_000,
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

    @Test
    fun `full maximum may be wagered initially`() {
        assertTrue(
            PredictionBetPolicy.canAddPoints(
                previousAmount = 0,
                additionalPoints = 250_000,
                minimumPoints = 10,
                maximumPoints = 250_000,
            ),
        )
    }

    @Test
    fun `remaining maximum may be added`() {
        assertTrue(
            PredictionBetPolicy.canAddPoints(
                previousAmount = 100_000,
                additionalPoints = 150_000,
                minimumPoints = 10,
                maximumPoints = 250_000,
            ),
        )
    }

    @Test
    fun `cumulative amount above maximum is rejected`() {
        assertFalse(
            PredictionBetPolicy.canAddPoints(
                previousAmount = 100_000,
                additionalPoints = 150_001,
                minimumPoints = 10,
                maximumPoints = 250_000,
            ),
        )
    }

    @Test
    fun `last ten points may be added`() {
        assertTrue(
            PredictionBetPolicy.canAddPoints(
                previousAmount = 249_990,
                additionalPoints = 10,
                minimumPoints = 10,
                maximumPoints = 250_000,
            ),
        )
    }

    @Test
    fun `less than minimum remaining cannot be added`() {
        assertFalse(
            PredictionBetPolicy.canAddPoints(
                previousAmount = 249_991,
                additionalPoints = 10,
                minimumPoints = 10,
                maximumPoints = 250_000,
            ),
        )
    }

    @Test
    fun `remaining maximum is zero when previous amount is full`() {
        assertEquals(
            0,
            PredictionBetPolicy.remainingPoints(
                previousAmount = 250_000,
                maximumPoints = 250_000,
            ),
        )
    }

    @Test
    fun `exhausted prediction allowance disables every outcome`() {
        assertFalse(
            PredictionBetPolicy.canBetOutcome(
                selectedOutcomeId = "c",
                candidateOutcomeId = "c",
                inFlight = false,
                confirmedAmount = 250_000,
                minimumPoints = 10,
                maximumPoints = 250_000,
            ),
        )
    }
}
