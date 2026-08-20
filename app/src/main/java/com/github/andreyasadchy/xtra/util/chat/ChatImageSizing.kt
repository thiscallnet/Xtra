package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.ImageKind

internal fun imageSizeForKind(
    kind: ImageKind,
    emoteSize: Int,
    badgeSize: Int,
    inlineIconSize: Int,
): Int {
    return when (kind) {
        ImageKind.EMOTE -> emoteSize
        ImageKind.BADGE -> badgeSize
        ImageKind.INLINE_ICON -> inlineIconSize
    }
}
