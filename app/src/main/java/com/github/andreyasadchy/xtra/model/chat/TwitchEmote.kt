package com.github.andreyasadchy.xtra.model.chat

class TwitchEmote(
    val id: String? = null,
    val name: String? = null,
    val localData: Pair<Long, Int>? = null,
    val url1x: String? = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/1.0",
    val url2x: String? = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0",
    val url3x: String? = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0",
    val url4x: String? = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/3.0",
    val format: String? = "gif",
    val isAnimated: Boolean = true,
    var begin: Int = 0,
    var end: Int = 0,
    val setId: String? = null,
    val ownerId: String? = null,
    /** Twitch's availability restriction from the Helix user-emotes response. */
    val restrictionType: String? = null,
)
