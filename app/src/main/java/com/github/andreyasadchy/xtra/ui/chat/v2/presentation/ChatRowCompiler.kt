package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowBackground
import java.text.NumberFormat

data class ChatPresentationLabels(
    val firstChatter: String = "First Time Chatter",
    val redeemed: (String) -> String = { "Redeemed $it" },
    val highlightTitle: String = "Highlight My Message",
    val highlightRedeemed: (String) -> String = { "Redeemed $it" },
    val watchStreakReached: String = "Watch Streak Reached",
    val watchStreakStatus: (String, Int) -> String = { user, count -> "$user is currently on a $count-stream streak!" },
    val reply: (String, String) -> String = { user, message -> "Replying to $user: $message" },
    val sharedChat: (String) -> String = { "Shared chat from $it" },
    val announcement: String = "Announcement",
    val reward: String = "Channel points reward",
)

class ChatRowCompiler(
    private val colors: ChatColorResolver = ChatColorResolver(),
    private val emoteHeightPx: Int = 28,
    private val badgeHeightPx: Int = 18,
    private val showBadges: Boolean = true,
    private val enableOverlayEmotes: Boolean = true,
    private val firstMessageVisibility: Int = 0,
    private val boldNames: Boolean = false,
    private val timestampText: (Long) -> String? = { null },
    private val background: (ChatMessage) -> Int = { 0 },
    private val labels: ChatPresentationLabels = ChatPresentationLabels(),
) {
    fun compile(message: ChatMessage, catalog: ChatCatalogSnapshot = ChatCatalogSnapshot(0)): ChatRowUiModel {
        val targetHeight = emoteHeightPx * if (message.twitchType == TwitchChatMessageType.GigantifiedEmote) 2 else 1
        val rowBackground = background(message)
        val isWatchStreak = message.noticeType.equals("watch_streak", ignoreCase = true) ||
            (message.noticeType.equals("viewermilestone", ignoreCase = true) && message.watchStreakCount != null)
        val hasSemanticBody = message.segments.any {
            it !is ChatSegment.Text || it.text.isNotBlank()
        }
        val noticeBody = message.systemText?.takeIf {
            it.isNotBlank() &&
                (message.kind == ChatMessageKind.NOTICE ||
                    message.kind == ChatMessageKind.SYSTEM ||
                    message.kind == ChatMessageKind.RAID ||
                    message.kind == ChatMessageKind.ANNOUNCEMENT) &&
                !(isWatchStreak && message.watchStreakCount != null)
        }
        val resolvedSegments = if (noticeBody != null) {
            val body = resolveSegments(message.segments, catalog)
            if (hasSemanticBody) {
                listOf(ChatSegment.Text("$noticeBody\n")) + body
            } else {
                listOf(ChatSegment.Text(noticeBody))
            }
        } else {
            resolveSegments(message.segments, catalog)
        }
        val isFirstChatter = (message.isFirst || message.twitchType == TwitchChatMessageType.UserIntro) &&
            firstMessageVisibility == 0
        val mutedColor = 0xFFC4BEC9.toInt()
        val pieces = buildList {
            val specialNotice = isFirstChatter || isWatchStreak
            if (showBadges && !specialNotice) message.badges.forEach { badge -> add(ChatPiece.Badge(badgeSpec(badge, catalog))) }
            if (isFirstChatter) {
                add(ChatPiece.Icon(R.drawable.ic_chat_first_chatter))
                add(ChatPiece.Text(" ${labels.firstChatter}\n", bold = true))
            }
            if (isWatchStreak) {
                add(ChatPiece.Icon(R.drawable.ic_watch_streak, sizeDp = 22))
                add(ChatPiece.Text(" ${labels.watchStreakReached}", bold = true))
                message.watchStreakPoints?.let { points ->
                    add(ChatPiece.Text("  "))
                    add(ChatPiece.Icon(R.drawable.ic_chat_channel_points, sizeDp = 20))
                    add(ChatPiece.Text(" +${NumberFormat.getInstance().format(points)}", bold = true))
                }
                add(ChatPiece.Text("\n"))
                if (message.watchStreakCount != null) {
                    val user = message.user?.displayName ?: message.user?.login ?: ""
                    val marker = "\uE000"
                    val status = labels.watchStreakStatus(marker, message.watchStreakCount)
                    val markerStart = status.indexOf(marker)
                    if (markerStart >= 0) {
                        add(ChatPiece.Text(status.substring(0, markerStart), color = mutedColor))
                        add(ChatPiece.Text(user, color = colors.resolve(message.user?.color?.let(::colorToHex), message.user?.id ?: message.user?.login ?: user, rowBackground), bold = true))
                        add(ChatPiece.Text(status.substring(markerStart + marker.length), color = mutedColor))
                    } else {
                        add(ChatPiece.Text(status, color = mutedColor))
                    }
                    add(ChatPiece.Text("\n"))
                    add(ChatPiece.Icon(R.drawable.ic_chat_speaker_muted, tint = mutedColor, sizeDp = 18))
                    add(ChatPiece.Text(" "))
                }
            }
            message.reply?.let { reply ->
                val user = reply.parentUserName ?: reply.parentUserLogin ?: ""
                val body = reply.parentMessageBody.orEmpty()
                add(ChatPiece.Icon(R.drawable.ic_chat_reply, tint = mutedColor, sizeDp = 18))
                val replyUser = user.takeIf { it.startsWith("@") }?.let { it } ?: "@$user"
                add(ChatPiece.Text(" ${labels.reply(replyUser, body)}\n", color = mutedColor))
            }
            message.source?.let { source ->
                val sourceName = source.broadcasterName ?: source.broadcasterLogin ?: source.broadcasterId
                add(ChatPiece.Text("${labels.sharedChat(sourceName)}\n"))
            }
            if (message.kind == ChatMessageKind.ANNOUNCEMENT) {
                add(ChatPiece.Text("${labels.announcement}\n", bold = true))
            }
            if (message.twitchType == TwitchChatMessageType.Highlighted) {
                add(ChatPiece.Text("${labels.highlightRedeemed(labels.highlightTitle)}\n", bold = true))
            }
            message.user?.takeIf { noticeBody == null || hasSemanticBody }?.let { user ->
                val name = user.displayName ?: user.login
                name?.let {
                    add(ChatPiece.Username(
                        it,
                        colors.resolve(user.color?.let(::colorToHex), user.id ?: user.login ?: it, rowBackground),
                        bold = boldNames || specialNotice,
                    ))
                }
            }
            if (message.rewardId != null && message.twitchType != TwitchChatMessageType.Highlighted) {
                add(ChatPiece.Text("${labels.redeemed(labels.reward)}\n"))
            }
            resolvedSegments.forEach { segment ->
                when (segment) {
                    is ChatSegment.Text -> add(ChatPiece.Text(segment.text))
                    is ChatSegment.Mention -> add(ChatPiece.Mention(segment.text, segment.userId, segment.login))
                    is ChatSegment.Emote -> add(
                        ChatPiece.Emote(
                            segment.asset.scaledTo(targetHeight),
                            segment.fallbackText,
                            segment.animated,
                            segment.interaction,
                        ),
                    )
                    is ChatSegment.Gif -> {
                        // Twitch's EventSub GIF fragment carries an id and URL, but no
                        // dimensions. Keep the same large, 16:9 presentation as Twitch chat.
                        if (lastOrNull() !is ChatPiece.Text || !(last() as ChatPiece.Text).value.endsWith("\n")) {
                            add(ChatPiece.Text("\n"))
                        }
                        add(ChatPiece.Gif(
                            ChatAssetSpec(ChatAssetKey(segment.url), 16, 9, 180),
                            segment.url,
                            segment.fallbackText,
                            segment.interaction,
                        ))
                        add(ChatPiece.Text("\n"))
                    }
                    is ChatSegment.Cheermote -> add(ChatPiece.Cheermote(segment.asset.scaledTo(targetHeight), segment.text, segment.bits, segment.color))
                }
            }
        }
        return ChatRowUiModel(
            id = message.id,
            channelId = message.channelId,
            timestampText = timestampText(message.timestampMs),
            pieces = pieces,
            background = rowBackground,
            backgroundStyle = when {
                message.twitchType == TwitchChatMessageType.Highlighted -> ChatRowBackground.HIGHLIGHT
                isWatchStreak -> ChatRowBackground.WATCH_STREAK
                message.isFirst && firstMessageVisibility == 0 -> ChatRowBackground.FIRST_CHATTER
                message.isFirst && firstMessageVisibility == 1 -> ChatRowBackground.FIRST_CHATTER_TINT
                message.rewardId != null && firstMessageVisibility < 2 -> ChatRowBackground.REWARD
                message.kind != ChatMessageKind.CHAT || !message.noticeType.isNullOrBlank() || !message.systemText.isNullOrBlank() -> ChatRowBackground.NOTICE
                else -> ChatRowBackground.NORMAL
            },
            reply = message.reply,
            source = message.source,
            isAction = message.kind == com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.ACTION,
            twitchType = message.twitchType,
            accessibilityText = buildString {
                message.user?.takeIf { noticeBody == null || hasSemanticBody }?.let { user ->
                    (user.displayName ?: user.login)?.let { append(it).append(": ") }
                }
                resolvedSegments.forEach { segment ->
                    when (segment) {
                        is ChatSegment.Text -> append(segment.text)
                        is ChatSegment.Mention -> append(segment.text)
                        is ChatSegment.Emote -> append(segment.fallbackText)
                        is ChatSegment.Gif -> append(segment.fallbackText)
                        is ChatSegment.Cheermote -> append(segment.bits).append(" Bits")
                    }
                }
            },
        )
    }

    private fun badgeSpec(badge: ChatBadgeRef, catalog: ChatCatalogSnapshot): ChatAssetSpec =
        (catalog.badges[badge.catalogKey]?.asset ?: badge.asset).scaledTo(badgeHeightPx)

    private fun resolveSegments(segments: List<ChatSegment>, catalog: ChatCatalogSnapshot): List<ChatSegment> {
        val resolved = ArrayList<ChatSegment>()
        segments.forEach { segment ->
            if (segment is ChatSegment.Text) {
                tokenizeThirdPartyText(segment.text, catalog, resolved)
            } else {
                resolved += segment
            }
        }
        return resolved
    }

    private fun tokenizeThirdPartyText(text: String, catalog: ChatCatalogSnapshot, output: MutableList<ChatSegment>) {
        Regex("\\s+|\\S+").findAll(text).forEach { match ->
            val token = match.value
            if (token.firstOrNull()?.isWhitespace() == true) {
                output += ChatSegment.Text(token)
                return@forEach
            }
            val definition = catalog.lookupThirdParty(token)
            if (definition == null) {
                output += ChatSegment.Text(token)
                return@forEach
            }
            if (enableOverlayEmotes && definition.zeroWidth && composeOverlay(output, definition)) return@forEach
            output += ChatSegment.Emote(
                asset = definition.asset,
                fallbackText = token,
                animated = definition.animated,
                interaction = ChatEmoteInteraction(
                    id = definition.id,
                    name = definition.name,
                    url = definition.asset.key.value,
                    animated = definition.animated,
                    provider = definition.provider,
                    scope = definition.scope,
                ),
            )
        }
    }

    private fun composeOverlay(output: MutableList<ChatSegment>, modifier: ChatCatalogEmote): Boolean {
        while (output.lastOrNull() is ChatSegment.Text && output.last() is ChatSegment.Text && (output.last() as ChatSegment.Text).text.isBlank()) {
            output.removeAt(output.lastIndex)
        }
        val previous = output.lastOrNull() as? ChatSegment.Emote ?: return false
        output[output.lastIndex] = previous.copy(
            asset = previous.asset.copy(overlays = previous.asset.overlays + modifier.asset),
        )
        return true
    }

    private fun colorToHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}

private fun ChatCatalogSnapshot.lookupThirdParty(token: String): ChatCatalogEmote? =
    sevenTv[token] ?: bttv[token] ?: ffz[token]
