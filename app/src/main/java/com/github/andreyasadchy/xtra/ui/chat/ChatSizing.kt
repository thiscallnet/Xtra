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
    val edgePaddingDp: Int = DEFAULT_CHAT_EDGE_PADDING_DP,
)

internal const val DEFAULT_CHAT_EDGE_PADDING_DP = 8

/** Space between the screen edge and chat rows; "1" lets chat run almost edge to edge. */
internal fun chatEdgePaddingDp(context: Context): Int =
    context.prefs().getString(C.CHAT_EDGE_PADDING, DEFAULT_CHAT_EDGE_PADDING_DP.toString())
        ?.toIntOrNull()?.coerceIn(0, 24) ?: DEFAULT_CHAT_EDGE_PADDING_DP

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

internal fun resolveChatRenderStyle(
    context: Context,
    pip: Boolean = false,
    viewportWidthPx: Int = 0,
): ChatRenderStyle {
    val prefs = context.prefs()
    val sizing = resolveChatSizing(context)
    val metrics = context.resources.displayMetrics
    val widthDp = if (viewportWidthPx > 0) viewportWidthPx / metrics.density
        else context.resources.configuration.screenWidthDp.toFloat()
    val textSizeSp = if (pip) {
        // Use the floating window's dp width, including when the user resizes it.
        // Large normal-chat preferences must not turn PiP into a single oversized row.
        val scale = (widthDp / PIP_CHAT_REFERENCE_WIDTH_DP).coerceIn(0.65f, 0.85f)
        (sizing.textSizeSp * scale).coerceIn(PIP_CHAT_MIN_TEXT_SP, PIP_CHAT_MAX_TEXT_SP)
            .coerceAtMost(sizing.textSizeSp)
    } else sizing.textSizeSp
    val pipScale = if (pip) textSizeSp / sizing.textSizeSp.coerceAtLeast(1f) else 1f
    fun imageHeight(sizePx: Int, maximumDp: Float): Int {
        val scaled = sizePx * pipScale
        return (if (pip) scaled.coerceAtMost(maximumDp * metrics.density) else scaled)
            .roundToInt().coerceAtLeast(1)
    }
    return ChatRenderStyle(
        textSizeSp = textSizeSp,
        emoteHeightPx = imageHeight(sizing.emoteHeightPx, 22f),
        badgeHeightPx = imageHeight(sizing.badgeHeightPx, 12f),
        animateGifs = prefs.getBoolean(C.ANIMATED_EMOTES, true),
        showBadges = prefs.getBoolean(C.CHAT_SHOW_BADGES, true),
        enableOverlayEmotes = prefs.getBoolean(C.CHAT_ZERO_WIDTH, true),
        firstMessageVisibility = prefs.getString(C.CHAT_FIRST_MSG_VISIBILITY, "0")?.toIntOrNull() ?: 0,
        boldNames = prefs.getBoolean(C.CHAT_BOLD_NAMES, false),
        showTimestamps = !pip && prefs.getBoolean(C.CHAT_TIMESTAMPS, false),
        timestampFormat = prefs.getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
        gifDisplayMode = ChatGifDisplayMode.fromPreference(
            prefs.getString(C.CHAT_GIF_DISPLAY, "large"),
        ),
        edgePaddingDp = if (pip) PIP_CHAT_EDGE_PADDING_DP else chatEdgePaddingDp(context),
    )
}

private const val PIP_CHAT_REFERENCE_WIDTH_DP = 240f
private const val PIP_CHAT_MIN_TEXT_SP = 10f
private const val PIP_CHAT_MAX_TEXT_SP = 12f
private const val PIP_CHAT_EDGE_PADDING_DP = 4
