package com.github.andreyasadchy.xtra.util.chat

internal object PredictionBetPolicy {
    fun isPredictionWagerable(outcomeIds: List<String?>): Boolean =
        outcomeIds.size in 2..10 && outcomeIds.all { !it.isNullOrBlank() }

    fun canBetOutcome(
        selectedOutcomeId: String?,
        candidateOutcomeId: String?,
        inFlight: Boolean,
    ): Boolean =
        !inFlight &&
            !candidateOutcomeId.isNullOrBlank() &&
            (
                selectedOutcomeId.isNullOrBlank() ||
                    selectedOutcomeId == candidateOutcomeId
                )

    fun totalAfterAdditionalBet(
        previousAmount: Int?,
        additionalPoints: Int,
    ): Int = (previousAmount ?: 0) + additionalPoints
}
