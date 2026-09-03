package com.github.andreyasadchy.xtra.ui.chat.v2.domain

/** The channel-point reward metadata needed to render a redemption in chat. */
data class ChatReward(
    val title: String,
    val cost: Int? = null,
    val imageUrl: String? = null,
)
