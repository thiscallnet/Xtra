package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.graphics.Color
import com.github.andreyasadchy.xtra.model.chat.ChatMessage as LegacyChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs

internal const val DEFAULT_CHAT_HIGHLIGHT_COLOR: Int = 0x80680E0E.toInt()

private val CHAT_HIGHLIGHT_COLOR_PATTERN = Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

data class ChatHighlightSettings(
    val highlightReplies: Boolean = true,
    val highlightMentions: Boolean = true,
    val matchMentionsWithoutAt: Boolean = true,
    val color: Int = DEFAULT_CHAT_HIGHLIGHT_COLOR,
    val viewerId: String? = null,
    val viewerLogin: String? = null,
)

internal fun resolveChatHighlightSettings(context: Context): ChatHighlightSettings {
    val preferences = context.prefs()
    return ChatHighlightSettings(
        highlightReplies = preferences.getBoolean(C.CHAT_HIGHLIGHT_REPLIES, true),
        highlightMentions = preferences.getBoolean(C.CHAT_HIGHLIGHT_MENTIONS, true),
        matchMentionsWithoutAt = preferences.getBoolean(C.CHAT_HIGHLIGHT_MENTIONS_WITHOUT_AT, true),
        color = parseChatHighlightColor(preferences.getString(C.CHAT_HIGHLIGHT_COLOR, "#80680E0E"))
            ?: DEFAULT_CHAT_HIGHLIGHT_COLOR,
        viewerId = context.tokenPrefs().getString(C.USER_ID, null)?.trim()?.takeIf { it.isNotEmpty() },
        viewerLogin = context.tokenPrefs().getString(C.USERNAME, null)?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun parseChatHighlightColor(value: String?): Int? {
    val normalized = value?.trim() ?: return null
    if (!CHAT_HIGHLIGHT_COLOR_PATTERN.matches(normalized)) return null
    val parsed = Color.parseColor(normalized)
    return if (normalized.length == 7) {
        // Keep the old highlight's 50% opacity when the user enters a plain hex color.
        (parsed and 0x00FFFFFF) or 0x80000000.toInt()
    } else {
        parsed
    }
}

internal fun containsChatViewerMention(
    text: String?,
    viewerLogin: String?,
    matchWithoutAt: Boolean,
): Boolean {
    val login = viewerLogin?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val prefix = if (matchWithoutAt) "@?" else "@"
    return Regex(
        "(?<![\\p{L}\\p{N}_])$prefix${Regex.escape(login)}(?![\\p{L}\\p{N}_])",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(text.orEmpty())
}

internal fun matchesChatViewer(
    id: String?,
    login: String?,
    displayName: String?,
    settings: ChatHighlightSettings,
): Boolean =
    (!settings.viewerId.isNullOrBlank() && id == settings.viewerId) ||
        (!settings.viewerLogin.isNullOrBlank() &&
            (login?.equals(settings.viewerLogin, ignoreCase = true) == true ||
                displayName?.equals(settings.viewerLogin, ignoreCase = true) == true))

internal fun shouldHighlightLegacyChatMessage(
    message: LegacyChatMessage,
    settings: ChatHighlightSettings,
): Boolean {
    if (message.type != LegacyChatMessage.USER_MESSAGE) return false
    if (settings.viewerId.isNullOrBlank() && settings.viewerLogin.isNullOrBlank()) return false
    if (matchesChatViewer(message.userId, message.userLogin, message.userName, settings)) return false

    val reply = settings.highlightReplies && message.reply?.let {
        matchesChatViewer(null, it.userLogin, it.userName, settings)
    } == true
    val mention = settings.highlightMentions && containsChatViewerMention(
        message.message,
        settings.viewerLogin,
        settings.matchMentionsWithoutAt,
    )
    return reply || mention
}

internal fun shouldHighlightV2ChatMessage(
    message: ChatMessage,
    settings: ChatHighlightSettings,
): Boolean {
    if (settings.viewerId.isNullOrBlank() && settings.viewerLogin.isNullOrBlank()) return false
    if (matchesChatViewer(message.user?.id, message.user?.login, message.user?.displayName, settings)) return false

    val reply = settings.highlightReplies && message.reply?.let {
        matchesChatViewer(it.parentUserId, it.parentUserLogin, it.parentUserName, settings)
    } == true
    val structuredMention = settings.highlightMentions && message.segments.any { segment ->
        val mention = segment as? ChatSegment.Mention ?: return@any false
        val isExplicit = mention.text.trimStart().startsWith("@")
        if (!isExplicit && !settings.matchMentionsWithoutAt) return@any false
        matchesChatViewer(mention.userId, mention.login, null, settings)
    }
    val rawText = message.rawText ?: message.segments.joinToString(separator = "") { segment ->
        when (segment) {
            is ChatSegment.Text -> segment.text
            is ChatSegment.Mention -> segment.text
            is ChatSegment.Emote -> segment.fallbackText
            is ChatSegment.Gif -> segment.fallbackText
            is ChatSegment.Cheermote -> segment.text
        }
    }
    val textMention = settings.highlightMentions && containsChatViewerMention(
        rawText,
        settings.viewerLogin,
        settings.matchMentionsWithoutAt,
    )
    return reply || structuredMention || textMention
}
