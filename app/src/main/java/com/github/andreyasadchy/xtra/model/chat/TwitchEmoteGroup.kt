package com.github.andreyasadchy.xtra.model.chat

/** The compact picker groups Twitch emotes by the availability bucket shown by Twitch. */
enum class TwitchEmoteGroup {
    UNLOCKED,
    HYPE_TRAIN,
    SUBSCRIBER,
    GLOBAL,
    ;

    companion object {
        fun fromRestrictionType(
            restrictionType: String?,
            channelScopedFallback: Boolean = false,
        ): TwitchEmoteGroup {
            val normalized = restrictionType.orEmpty()
                .replace("_", "")
                .replace("-", "")
                .lowercase()
            return when {
                normalized == "hypetrain" -> HYPE_TRAIN
                normalized == "subscriptions" -> SUBSCRIBER
                normalized in GLOBAL_RESTRICTIONS -> GLOBAL
                restrictionType.isNullOrBlank() && channelScopedFallback -> UNLOCKED
                // A Twitch emote returned in the viewer's catalog is available to that
                // viewer. Keep new/unknown entitlement types out of Global until Twitch
                // introduces an explicitly global restriction value.
                else -> UNLOCKED
            }
        }

        private val GLOBAL_RESTRICTIONS = setOf(
            "globals",
            "global",
            "smilies",
        )
    }
}
