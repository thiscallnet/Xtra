package com.github.andreyasadchy.xtra.repository

/** A Twitch EventSub subscription request for a WebSocket session. */
data class EventSubSubscriptionSpec(
    val type: String,
    val version: String = "1",
    val condition: Map<String, String>,
)
