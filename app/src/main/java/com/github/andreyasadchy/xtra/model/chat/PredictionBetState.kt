package com.github.andreyasadchy.xtra.model.chat

data class PredictionBetState(
    val predictionId: String? = null,
    val outcomeId: String? = null,
    val amount: Int? = null,
    val inFlight: Boolean = false,
    val error: String? = null,
)
