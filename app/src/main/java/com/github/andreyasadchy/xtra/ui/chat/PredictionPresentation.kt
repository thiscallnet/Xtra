package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction

internal fun predictionOutcomeOrdinal(
    outcome: Prediction.PredictionOutcome,
    fallbackIndex: Int,
): Int = outcome.badgeVersion
    ?.substringAfterLast('-')
    ?.toIntOrNull()
    ?.takeIf { it in 1..10 }
    ?: (fallbackIndex + 1)

internal fun predictionOutcomeLabel(
    outcome: Prediction.PredictionOutcome,
    index: Int,
    outcomeCount: Int,
): String {
    val title = outcome.title.orEmpty()
    return if (outcomeCount > 2) {
        "${predictionOutcomeOrdinal(outcome, index)}. $title"
    } else {
        title
    }
}
