package com.github.andreyasadchy.xtra.ui.chat.v2.domain

enum class ChatModerationDisplayMode {
    NOTICE,
    STRIKETHROUGH,
    HIDE,
    ;

    companion object {
        fun fromPreference(value: String?): ChatModerationDisplayMode = when (value) {
            "strikethrough" -> STRIKETHROUGH
            "hide" -> HIDE
            else -> NOTICE
        }
    }
}

data class ChatModeration(
    val reason: ChatUserClearReason,
    val timeoutSeconds: Int? = null,
)
