package com.github.andreyasadchy.xtra.ui.chat

/** Controls which popout owns gestures that start on an inline emote. */
enum class ChatEmotePopoutMode {
    EMOTE_DETAILS,
    EMOTE_TAP_PROFILE_HOLD,
    PROFILE_GESTURE;

    companion object {
        fun fromPreference(value: String?): ChatEmotePopoutMode = when (value?.lowercase()) {
            "emote_tap_profile_hold" -> EMOTE_TAP_PROFILE_HOLD
            "profile_gesture" -> PROFILE_GESTURE
            else -> EMOTE_DETAILS
        }
    }
}
