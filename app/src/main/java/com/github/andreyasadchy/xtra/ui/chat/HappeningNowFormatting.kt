package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.util.TwitchApiHelper

internal fun happeningNowPredictionTotals(
    outcomes: List<Prediction.PredictionOutcome>,
): String? = outcomes.takeIf { it.size >= 2 }
    ?.take(2)
    ?.joinToString(" vs ") {
        TwitchApiHelper.formatCount(it.totalPoints ?: 0, compact = true)
    }

internal fun happeningNowWinnerIndex(prediction: Prediction): Int =
    prediction.outcomes.orEmpty().indexOfFirst {
        !prediction.winningOutcomeId.isNullOrBlank() &&
            it.id == prediction.winningOutcomeId
    }
