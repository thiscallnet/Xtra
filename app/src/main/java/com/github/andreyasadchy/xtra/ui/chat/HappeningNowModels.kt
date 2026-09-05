package com.github.andreyasadchy.xtra.ui.chat

internal data class HappeningNowGift(
    val stableId: String,
    val occurredAt: Long,
    val gifterDisplayName: String,
    val count: Int,
)

internal object HappeningNowKeys {
    fun gift(id: String) = "gift:$id"
    fun prediction(id: String) = "prediction:$id"
    fun predictionResult(id: String) = "prediction-result:$id"
    fun poll(id: String) = "poll:$id"
}
