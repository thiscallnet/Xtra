package com.github.andreyasadchy.xtra.model.twitchinbox

sealed interface TwitchNotificationAction {
    data class Channel(
        val id: String?,
        val login: String?,
        val displayName: String?,
        val imageUrl: String?,
    ) : TwitchNotificationAction

    data class Video(val id: String) : TwitchNotificationAction
    data class Clip(val slug: String) : TwitchNotificationAction
    data class Game(val id: String?, val name: String?) : TwitchNotificationAction
    data class TwitchWebUrl(val url: String) : TwitchNotificationAction
    data object None : TwitchNotificationAction
}
