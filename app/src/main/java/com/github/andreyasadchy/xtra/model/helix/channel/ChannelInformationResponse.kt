package com.github.andreyasadchy.xtra.model.helix.channel

import kotlinx.serialization.Serializable

@Serializable
class ChannelInformationResponse(
    val data: List<ChannelInformation> = emptyList(),
)
