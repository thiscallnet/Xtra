package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.util.TypedValue
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.DEFAULT_CHAT_BADGE_SIZE_DP
import com.github.andreyasadchy.xtra.util.chatBadgeSizeOrDefault
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.roundToInt

internal data class ChatSizing(
    val textSizeSp: Float,
    val emoteHeightPx: Int,
    val badgeHeightPx: Int,
)

enum class ChatGifDisplayMode {
    LARGE,
    EMOTE,
    LINK,
    ;

    companion object {
        fun fromPreference(value: String?): ChatGifDisplayMode = when (value) {
            "emote" -> EMOTE
            "link" -> LINK
            else -> LARGE
        }
    }
}

internal data class ChatRenderStyle(
    val textSizeSp: Float,
    val emoteHeightPx: Int,
    val badgeHeightPx: Int,
    val animateGifs: Boolean,
    val showBadges: Boolean,
    val enableOverlayEmotes: Boolean,
    val firstMessageVisibility: Int,
    val boldNames: Boolean,
    val showTimestamps: Boolean,
    val timestampFormat: String?,
    val gifDisplayMode: ChatGifDisplayMode = ChatGifDisplayMode.LARGE,
)

internal fun resolveChatSizing(context: Context): ChatSizing {
    val prefs = context.prefs()
    val scale = prefs.getInt(C.CHAT_SIZE_MODIFIER, 100) / 100f
    val textSp = (prefs.getString(C.CHAT_TEXT_SIZE, "14")?.toFloatOrNull() ?: 14f) * scale
    val emoteDp = (prefs.getString(C.CHAT_EMOTE_SIZE, "29.5")?.toFloatOrNull() ?: 29.5f) * scale
    val badgeDp = chatBadgeSizeOrDefault(
        prefs.getString(C.CHAT_BADGE_SIZE, DEFAULT_CHAT_BADGE_SIZE_DP.toString()),
    ) * scale
    fun dpToPx(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        context.resources.displayMetrics,
    ).roundToInt().coerceAtLeast(1)
    return ChatSizing(textSp, dpToPx(emoteDp), dpToPx(badgeDp))
}

internal fun resolveChatRenderStyle(context: Context): ChatRenderStyle {
    val prefs = context.prefs()
    val sizing = resolveChatSizing(context)
    return ChatRenderStyle(
        textSizeSp = sizing.textSizeSp,
        emoteHeightPx = sizing.emoteHeightPx,
        badgeHeightPx = sizing.badgeHeightPx,
        animateGifs = prefs.getBoolean(C.ANIMATED_EMOTES, true),
        showBadges = prefs.getBoolean(C.CHAT_SHOW_BADGES, true),
        enableOverlayEmotes = prefs.getBoolean(C.CHAT_ZERO_WIDTH, true),
        firstMessageVisibility = prefs.getString(C.CHAT_FIRST_MSG_VISIBILITY, "0")?.toIntOrNull() ?: 0,
        boldNames = prefs.getBoolean(C.CHAT_BOLD_NAMES, false),
        showTimestamps = prefs.getBoolean(C.CHAT_TIMESTAMPS, false),
        timestampFormat = prefs.getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
        gifDisplayMode = ChatGifDisplayMode.fromPreference(
            prefs.getString(C.CHAT_GIF_DISPLAY, "large"),
        ),
    )
}
