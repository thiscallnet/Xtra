package com.github.andreyasadchy.xtra.util

internal const val DEFAULT_CHAT_BADGE_SIZE_DP = 18.5f
internal const val MIN_CHAT_BADGE_SIZE_DP = 4f
internal const val MAX_CHAT_BADGE_SIZE_DP = 64f

internal fun parseChatBadgeSize(value: String?): Float? {
    return value
        ?.trim()
        ?.replace(',', '.')
        ?.toFloatOrNull()
        ?.takeIf { it.isFinite() && it in MIN_CHAT_BADGE_SIZE_DP..MAX_CHAT_BADGE_SIZE_DP }
}

internal fun chatBadgeSizeOrDefault(value: String?): Float {
    return parseChatBadgeSize(value) ?: DEFAULT_CHAT_BADGE_SIZE_DP
}
