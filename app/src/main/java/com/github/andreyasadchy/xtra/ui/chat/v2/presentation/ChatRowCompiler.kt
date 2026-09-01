package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot

class ChatRowCompiler(
    private val colors: ChatColorResolver = ChatColorResolver(),
    private val emoteHeightPx: Int = 28,
    private val timestampText: (Long) -> String? = { null },
    private val background: (ChatMessage) -> Int = { 0 },
) {
    fun compile(message: ChatMessage, catalog: ChatCatalogSnapshot = ChatCatalogSnapshot(0)): ChatRowUiModel {
        val targetHeight = emoteHeightPx * if (message.twitchType == TwitchChatMessageType.GigantifiedEmote) 2 else 1
        val rowBackground = background(message)
        val pieces = buildList {
            message.badges.forEach { badge -> add(ChatPiece.Badge(badgeSpec(badge, catalog))) }
            message.user?.let { user ->
                val name = user.displayName ?: user.login
                name?.let {
                    add(ChatPiece.Username(
                        it,
                        colors.resolve(user.color?.let(::colorToHex), user.id ?: user.login ?: it, rowBackground),
                    ))
                }
            }
            resolveSegments(message.segments, catalog).forEach { segment ->
                when (segment) {
                    is ChatSegment.Text -> add(ChatPiece.Text(segment.text))
                    is ChatSegment.Mention -> add(ChatPiece.Mention(segment.text, segment.userId, segment.login))
                    is ChatSegment.Emote -> add(ChatPiece.Emote(segment.asset.scaledTo(targetHeight), segment.fallbackText))
                    is ChatSegment.Gif -> add(ChatPiece.Gif(ChatAssetSpec(ChatAssetKey(segment.url), 1, 1, targetHeight), segment.url, segment.fallbackText))
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
            reply = message.reply,
            source = message.source,
            isAction = message.kind == com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.ACTION,
            twitchType = message.twitchType,
            accessibilityText = buildString {
                message.user?.let { user -> (user.displayName ?: user.login)?.let { append(it).append(": ") } }
                message.segments.forEach { segment ->
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
        catalog.badges[badge.catalogKey]?.asset ?: badge.asset

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
            if (definition.zeroWidth && composeOverlay(output, definition)) return@forEach
            output += ChatSegment.Emote(
                asset = definition.asset,
                fallbackText = token,
                animated = definition.animated,
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
