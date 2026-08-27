package com.github.andreyasadchy.xtra.util.chat

internal object PredictionBetPolicy {
    fun isPredictionWagerable(outcomeIds: List<String?>): Boolean =
        outcomeIds.size in 2..10 && outcomeIds.all { !it.isNullOrBlank() }

    fun remainingPoints(
        previousAmount: Int?,
        maximumPoints: Int,
    ): Int = (maximumPoints - (previousAmount ?: 0)).coerceAtLeast(0)

    fun canAddPoints(
        previousAmount: Int?,
        additionalPoints: Int,
        minimumPoints: Int,
        maximumPoints: Int,
    ): Boolean {
        val remaining = remainingPoints(previousAmount, maximumPoints)
        return additionalPoints >= minimumPoints && additionalPoints <= remaining
    }

    fun canBetOutcome(
        selectedOutcomeId: String?,
        candidateOutcomeId: String?,
        inFlight: Boolean,
        confirmedAmount: Int,
        minimumPoints: Int,
        maximumPoints: Int,
    ): Boolean =
        !inFlight &&
            !candidateOutcomeId.isNullOrBlank() &&
            remainingPoints(confirmedAmount, maximumPoints) >= minimumPoints &&
            (
                selectedOutcomeId.isNullOrBlank() ||
                    selectedOutcomeId == candidateOutcomeId
                )

    fun totalAfterAdditionalBet(
        previousAmount: Int?,
        additionalPoints: Int,
    ): Int = (previousAmount ?: 0) + additionalPoints
}
