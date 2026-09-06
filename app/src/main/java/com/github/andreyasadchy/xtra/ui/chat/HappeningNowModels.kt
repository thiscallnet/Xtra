package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGiftSource

internal data class HappeningNowGift(
    val stableId: String,
    val occurredAt: Long,
    val gifterDisplayName: String?,
    val gifterUserId: String?,
    val gifterLogin: String?,
    val isAnonymous: Boolean,
    val count: Int,
    val source: ChatGiftSource,
)

internal object HappeningNowKeys {
    fun gift(id: String) = "gift:$id"
    fun prediction(id: String) = "prediction:$id"
    fun predictionResult(id: String) = "prediction-result:$id"
    fun poll(id: String) = "poll:$id"
}
