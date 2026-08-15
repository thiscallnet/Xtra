package com.github.andreyasadchy.xtra.model.helix.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatSettings(
    @SerialName("broadcaster_id")
    val broadcasterId: String? = null,
    @SerialName("moderator_id")
    val moderatorId: String? = null,
    @SerialName("follower_mode")
    val followerMode: Boolean = false,
    @SerialName("follower_mode_duration")
    val followerModeDuration: Int? = null,
    @SerialName("slow_mode")
    val slowMode: Boolean = false,
    @SerialName("slow_mode_wait_time")
    val slowModeWaitTime: Int? = null,
    @SerialName("subscriber_mode")
    val subscriberMode: Boolean = false,
    @SerialName("emote_mode")
    val emoteMode: Boolean = false,
    @SerialName("unique_chat_mode")
    val uniqueChatMode: Boolean = false,
)
