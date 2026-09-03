package com.github.andreyasadchy.xtra.ui.chat

enum class ChatProfilePopoutGesture {
    HOLD,
    TAP,
    BOTH;

    val allowsTap: Boolean get() = this == TAP || this == BOTH
    val allowsHold: Boolean get() = this == HOLD || this == BOTH

    companion object {
        fun fromPreference(value: String?): ChatProfilePopoutGesture = when (value?.lowercase()) {
            "hold" -> HOLD
            "both" -> BOTH
            else -> TAP
        }
    }
}
