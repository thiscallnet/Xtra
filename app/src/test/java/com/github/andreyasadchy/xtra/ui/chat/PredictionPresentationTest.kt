package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionPresentationTest {
    @Test
    fun keepsBinaryLabelsUnchanged() {
        val outcomes = listOf(outcome("a", "Yes"), outcome("b", "No"))

        assertEquals("Yes", predictionOutcomeLabel(outcomes[0], 0, outcomes.size))
        assertEquals("No", predictionOutcomeLabel(outcomes[1], 1, outcomes.size))
    }

    @Test
    fun numbersEveryMultiOutcomeLabel() {
        val outcomes = listOf(
            outcome("a", "One"),
            outcome("b", "Two"),
            outcome("c", "Three"),
            outcome("d", "Four"),
        )

        assertEquals(
            listOf("1. One", "2. Two", "3. Three", "4. Four"),
            outcomes.mapIndexed { index, value -> predictionOutcomeLabel(value, index, outcomes.size) },
        )
    }

    @Test
    fun usesBadgeOrdinalAndFallsBackForInvalidVersions() {
        assertEquals(7, predictionOutcomeOrdinal(outcome("g", "Seven", "blue-7"), 1))
        assertEquals(2, predictionOutcomeOrdinal(outcome("b", "Two", "blue-invalid"), 1))
        assertEquals("10. Ten", predictionOutcomeLabel(outcome("j", "Ten", "blue-10"), 9, 10))
    }

    private fun outcome(id: String, title: String, badgeVersion: String? = null) =
        Prediction.PredictionOutcome(
            id = id,
            title = title,
            totalPoints = null,
            totalUsers = null,
            color = "BLUE",
            badgeVersion = badgeVersion,
        )
}
