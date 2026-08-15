package com.github.andreyasadchy.xtra.model.helix.chat

import kotlinx.serialization.Serializable

@Serializable
class ChatSettingsResponse(
    val data: List<ChatSettings> = emptyList(),
)
