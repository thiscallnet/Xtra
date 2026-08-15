package com.github.andreyasadchy.xtra.model.helix.channel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChannelInformation(
    @SerialName("broadcaster_id")
    val broadcasterId: String? = null,
    @SerialName("broadcaster_login")
    val broadcasterLogin: String? = null,
    @SerialName("broadcaster_name")
    val broadcasterName: String? = null,
    @SerialName("game_name")
    val gameName: String? = null,
    @SerialName("game_id")
    val gameId: String? = null,
    @SerialName("broadcaster_language")
    val language: String? = null,
    val title: String? = null,
    val delay: Int? = null,
    val tags: List<String> = emptyList(),
    @SerialName("content_classification_labels")
    val contentClassificationLabels: List<String> = emptyList(),
    @SerialName("is_branded_content")
    val isBrandedContent: Boolean? = null,
)
