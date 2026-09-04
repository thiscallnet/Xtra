package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.ChatHighlightSettings
import com.github.andreyasadchy.xtra.ui.chat.shouldHighlightV2ChatMessage
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
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatRewardCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatRowBackground
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewLink
import com.github.andreyasadchy.xtra.ui.chat.ChatGifDisplayMode
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
    val userRedeemed: (String) -> String = { "redeemed $it" },
)

class ChatRowCompiler(
    private val colors: ChatColorResolver = ChatColorResolver(),
    private val emoteHeightPx: Int = 28,
    private val badgeHeightPx: Int = 18,
    private val showBadges: Boolean = true,
    private val enableOverlayEmotes: Boolean = true,
    private val firstMessageVisibility: Int = 0,
    private val boldNames: Boolean = false,
    private val nameDisplay: String = "0",
    private val showSystemMessageEmotes: Boolean = true,
    private val showNamePaints: Boolean = true,
    private val showThirdPartyBadges: Boolean = true,
    private val showPersonalEmotes: Boolean = true,
    private val translation: (ChatMessage) -> String? = { null },
    private val timestampText: (Long) -> String? = { null },
    private val background: (ChatMessage) -> Int = { 0 },
    private val labels: ChatPresentationLabels = ChatPresentationLabels(),
    private val gifDisplayMode: ChatGifDisplayMode = ChatGifDisplayMode.LARGE,
    private val highlightSettings: ChatHighlightSettings = ChatHighlightSettings(),
) {
    fun compile(message: ChatMessage, catalog: ChatCatalogSnapshot = ChatCatalogSnapshot(0)): ChatRowUiModel {
        val targetHeight = emoteHeightPx * if (message.twitchType == TwitchChatMessageType.GigantifiedEmote) 2 else 1
        val isWatchStreak = message.noticeType.equals("watch_streak", ignoreCase = true) ||
            (message.noticeType.equals("viewermilestone", ignoreCase = true) && message.watchStreakCount != null)
        val noticeType = message.noticeType?.lowercase()
        val isSubscription = noticeType in SUBSCRIPTION_NOTICE_TYPES
        val isPrimeSubscription = isSubscription && (
            message.isPrimeSubscription == true ||
                message.subscriptionPlan?.contains("prime", ignoreCase = true) == true
            )
        val reward = ChatRewardCatalog(
            byId = catalog.channelPointRewards,
            automaticByType = catalog.automaticChannelPointRewards,
        ).rewardFor(message)
        val hasSemanticBody = message.segments.any {
            it !is ChatSegment.Text || it.text.isNotBlank()
        }
        val noticeBody = message.systemText?.takeIf {
            it.isNotBlank() &&
                (message.kind == ChatMessageKind.NOTICE ||
                    message.kind == ChatMessageKind.SYSTEM ||
                    message.kind == ChatMessageKind.RAID ||
                    message.kind == ChatMessageKind.ANNOUNCEMENT) &&
                !(isWatchStreak && message.watchStreakCount != null) &&
                !isSubscription
        }
        val subscriptionBody = message.systemText?.takeIf { isSubscription && it.isNotBlank() }
        val resolvedSegments = if (noticeBody != null) {
            val body = resolveSegments(message.segments, catalog, showSystemMessageEmotes, messagePersonalEmoteSetId(message, catalog))
            if (hasSemanticBody) {
                listOf(ChatSegment.Text("$noticeBody\n")) + body
            } else {
                listOf(ChatSegment.Text(noticeBody))
            }
        } else {
            resolveSegments(message.segments, catalog, personalEmoteSetId = messagePersonalEmoteSetId(message, catalog))
        }
        val clipPreviews = extractClipPreviews(message)
        val isFirstChatter = (message.isFirst || message.twitchType == TwitchChatMessageType.UserIntro) &&
            firstMessageVisibility == 0
        val isRewardOnly = message.rewardId != null &&
            message.twitchType != TwitchChatMessageType.Highlighted &&
            !hasSemanticBody
        val hasSpecialBackground = message.twitchType == TwitchChatMessageType.Highlighted ||
            isWatchStreak ||
            isSubscription ||
            (message.isFirst || message.twitchType == TwitchChatMessageType.UserIntro) && firstMessageVisibility in 0..1 ||
            message.rewardId != null && firstMessageVisibility < 2 ||
            message.kind != ChatMessageKind.CHAT ||
            !message.noticeType.isNullOrBlank() ||
            !message.systemText.isNullOrBlank()
        val isPersonalHighlight = !hasSpecialBackground && shouldHighlightV2ChatMessage(message, highlightSettings)
        val baseBackground = background(message)
        val rowBackground = if (isPersonalHighlight) {
            compositeColors(highlightSettings.color, baseBackground)
        } else {
            baseBackground
        }
        val mutedColor = colors.mutedTextColor(rowBackground)
        val pieces = buildList {
            val specialNotice = isFirstChatter || isWatchStreak || isSubscription
            message.reply?.let { reply ->
                val user = reply.parentUserName ?: reply.parentUserLogin ?: ""
                val body = reply.parentMessageBody.orEmpty()
                add(ChatPiece.Icon(R.drawable.ic_chat_reply, tint = mutedColor, sizeDp = 18))
                val replyUser = user.takeIf { it.startsWith("@") }?.let { it } ?: "@$user"
                add(ChatPiece.Reply(" ${labels.reply(replyUser, body)}", color = mutedColor))
                add(ChatPiece.Text("\n", color = mutedColor))
            }
            if (showBadges && !specialNotice) message.badges
                .filter { it.setId.isNotBlank() && it.versionId.isNotBlank() }
                .forEach { badge ->
                    add(ChatPiece.Badge(badgeSpec(badge, catalog), badgeInteraction(badge, catalog)))
                }
            if (showThirdPartyBadges && !specialNotice) {
                message.user?.id?.let(catalog.userDecorations::get)?.badgeId?.let { badgeId ->
                    catalog.sevenTvBadges[badgeId]?.let { badge ->
                        add(
                            ChatPiece.Badge(
                                badge.asset.scaledTo(badgeHeightPx),
                                ChatEmoteInteraction(
                                    id = badgeId,
                                    name = badge.info ?: badge.name,
                                    url = badge.asset.key.value,
                                    animated = true,
                                    provider = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider.SEVEN_TV,
                                    scope = null,
                                ),
                            ),
                        )
                    }
                }
            }
            if (isFirstChatter) {
                add(ChatPiece.Icon(R.drawable.ic_chat_first_chatter))
                add(ChatPiece.Text(" ${labels.firstChatter}\n", bold = true))
            }
            if (subscriptionBody != null) {
                val headingColor = colors.brightTextColor(rowBackground)
                val icon = if (isPrimeSubscription) {
                    R.drawable.ic_chat_subscription
                } else {
                    R.drawable.ic_chat_subscription_gift
                }
                add(ChatPiece.Icon(icon, tint = headingColor))
                add(ChatPiece.Text(" "))
                val systemUser = message.user?.displayName ?: message.user?.login
                val actor = systemUser ?: subscriptionBody.substringBefore(' ').takeIf { !it.equals("An", ignoreCase = true) }
                val nameEnd = actor
                    ?.takeIf { subscriptionBody.startsWith(it, ignoreCase = true) }
                    ?.length
                if (nameEnd != null) {
                    val userColor = if (systemUser == null) headingColor else colors.resolve(
                        message.user?.color?.let(::colorToHex),
                        message.user?.id ?: message.user?.login ?: systemUser,
                        rowBackground,
                    )
                    add(ChatPiece.Text(subscriptionBody.substring(0, nameEnd), color = userColor, bold = true))
                    add(ChatPiece.Text(subscriptionBody.substring(nameEnd), color = mutedColor))
                } else {
                    add(ChatPiece.Text(subscriptionBody, color = mutedColor))
                }
                if (hasSemanticBody) add(ChatPiece.Text("\n"))
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
            message.source?.let { source ->
                val sourceName = source.broadcasterName ?: source.broadcasterLogin ?: source.broadcasterId
                add(ChatPiece.Source(sourceName, colors.mutedTextColor(rowBackground)))
                add(ChatPiece.Text(" "))
            }
            if (message.kind == ChatMessageKind.ANNOUNCEMENT) {
                add(ChatPiece.Text("${labels.announcement}\n", bold = true))
            }
            if (message.twitchType == TwitchChatMessageType.Highlighted) {
                val headingColor = colors.brightTextColor(rowBackground)
                // The displayed highlight title is always the localized
                // presentation label. The catalog only provides the configured
                // cost/image metadata, whose built-in titles are hardcoded
                // English and must not override the label.
                val highlightTitle = labels.highlightTitle
                val heading = labels.highlightRedeemed(highlightTitle)
                val titleStart = heading.indexOf(highlightTitle).coerceAtLeast(0)
                add(ChatPiece.Text(heading.substring(0, titleStart), color = headingColor))
                add(ChatPiece.Text(heading.substring(titleStart), color = headingColor, bold = true))
                add(ChatPiece.Text("\n", color = headingColor))
                reward?.cost?.let { cost ->
                    add(ChatPiece.Icon(R.drawable.ic_chat_channel_points, tint = headingColor, sizeDp = 22))
                    add(ChatPiece.Text(" ", color = headingColor))
                    add(ChatPiece.Text(NumberFormat.getInstance().format(cost), color = headingColor, bold = true))
                    add(ChatPiece.Text("\n", color = headingColor))
                }
            }
            if (message.rewardId != null && message.twitchType != TwitchChatMessageType.Highlighted) {
                if (isRewardOnly) {
                    message.user?.let { user ->
                        user.displayName(nameDisplay)?.let { name ->
                            add(
                                ChatPiece.Username(
                                    value = name,
                                    color = colors.resolve(user.color?.let(::colorToHex), user.id ?: user.login ?: name, rowBackground),
                                    bold = true,
                                    paint = if (showNamePaints) {
                                        user.id?.let(catalog.userDecorations::get)?.paintId?.let(catalog.namePaints::get)
                                    } else null,
                                    separator = "",
                                ),
                            )
                        }
                    }
                    add(ChatPiece.Text(" ${labels.userRedeemed(reward?.title ?: labels.reward)} ", color = mutedColor))
                } else {
                    add(ChatPiece.Text("${labels.redeemed(reward?.title ?: labels.reward)} ", color = mutedColor))
                }
                reward?.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
                    add(
                        ChatPiece.RewardIcon(
                            asset = ChatAssetSpec(
                                key = ChatAssetKey(imageUrl),
                                sourceWidth = 1,
                                sourceHeight = 1,
                                targetHeight = badgeHeightPx,
                            ),
                            fallback = reward.title,
                        ),
                    )
                    add(ChatPiece.Text(" ", color = mutedColor))
                }
                reward?.cost?.let { cost ->
                    add(ChatPiece.Text(NumberFormat.getInstance().format(cost), color = mutedColor))
                }
                if (!isRewardOnly) add(ChatPiece.Text("\n", color = mutedColor))
            }
            message.user?.takeIf {
                !isRewardOnly && ((noticeBody == null && !isSubscription) || hasSemanticBody)
            }?.let { user ->
                val name = user.displayName(nameDisplay)
                name?.let {
                    add(ChatPiece.Username(
                        it,
                        colors.resolve(user.color?.let(::colorToHex), user.id ?: user.login ?: it, rowBackground),
                        bold = boldNames || specialNotice,
                        paint = if (showNamePaints) {
                            user.id?.let(catalog.userDecorations::get)?.paintId?.let(catalog.namePaints::get)
                        } else null,
                    ))
                }
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
                        when (gifDisplayMode) {
                            ChatGifDisplayMode.LINK -> add(ChatPiece.Text(segment.url))
                            ChatGifDisplayMode.EMOTE -> add(ChatPiece.Gif(
                                ChatAssetSpec(ChatAssetKey(segment.url), 16, 9, emoteHeightPx),
                                segment.url,
                                segment.fallbackText,
                                segment.interaction,
                            ))
                            ChatGifDisplayMode.LARGE -> {
                                // EventSub GIF fragments do not carry dimensions. Keep the
                                // existing large presentation on its own line.
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
                        }
                    }
                    is ChatSegment.Cheermote -> {
                        val catalogCheermote = catalog.cheermotes[segment.asset.key.value]
                        add(
                            ChatPiece.Cheermote(
                                asset = (catalogCheermote?.asset ?: segment.asset).scaledTo(targetHeight),
                                value = segment.text,
                                bits = segment.bits,
                                color = segment.color ?: catalogCheermote?.color,
                                interaction = ChatEmoteInteraction(
                                    id = segment.asset.key.value,
                                    name = segment.text,
                                    url = catalogCheermote?.asset?.key?.value ?: segment.asset.key.value,
                                    animated = false,
                                    provider = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider.TWITCH,
                                    scope = null,
                                ),
                            ),
                        )
                    }
                }
            }
            translation(message)?.takeIf { it.isNotBlank() }?.let {
                add(ChatPiece.Text("\n$it", color = mutedColor))
            }
        }
        return ChatRowUiModel(
            id = message.id,
            channelId = message.channelId,
            timestampText = timestampText(message.timestampMs),
            timestampColor = colors.resolve("#999999", rowBackground = rowBackground),
            pieces = pieces,
            background = if (isPersonalHighlight) highlightSettings.color else baseBackground,
            backgroundStyle = when {
                message.twitchType == TwitchChatMessageType.Highlighted -> ChatRowBackground.HIGHLIGHT
                isWatchStreak -> ChatRowBackground.WATCH_STREAK
                isSubscription -> ChatRowBackground.SUBSCRIPTION
                (message.isFirst || message.twitchType == TwitchChatMessageType.UserIntro) && firstMessageVisibility == 0 -> ChatRowBackground.FIRST_CHATTER
                (message.isFirst || message.twitchType == TwitchChatMessageType.UserIntro) && firstMessageVisibility == 1 -> ChatRowBackground.FIRST_CHATTER_TINT
                message.rewardId != null && firstMessageVisibility < 2 -> ChatRowBackground.REWARD
                message.kind != ChatMessageKind.CHAT || !message.noticeType.isNullOrBlank() || !message.systemText.isNullOrBlank() -> ChatRowBackground.NOTICE
                isPersonalHighlight -> ChatRowBackground.PERSONAL_HIGHLIGHT
                else -> ChatRowBackground.NORMAL
            },
            reply = message.reply,
            source = message.source,
            isAction = message.kind == com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.ACTION,
            twitchType = message.twitchType,
            clipPreviews = clipPreviews,
            accessibilityText = buildString {
                message.user?.takeIf { noticeBody == null || hasSemanticBody }?.let { user ->
                    user.displayName(nameDisplay)?.let { append(it).append(": ") }
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
                if (isRewardOnly) {
                    append(message.user?.displayName(nameDisplay).orEmpty())
                    append(" ").append(labels.userRedeemed(reward?.title ?: labels.reward))
                }
            },
        )
    }

    private fun badgeSpec(badge: ChatBadgeRef, catalog: ChatCatalogSnapshot): ChatAssetSpec =
        (catalog.badges[badge.catalogKey]?.asset ?: badge.asset).scaledTo(badgeHeightPx)

    private fun badgeInteraction(badge: ChatBadgeRef, catalog: ChatCatalogSnapshot): ChatEmoteInteraction {
        val definition = catalog.badges[badge.catalogKey]
        return ChatEmoteInteraction(
            id = badge.versionId,
            name = definition?.info ?: badge.info ?: badge.setId,
            url = definition?.asset?.key?.value ?: badge.asset.key.value,
            animated = false,
            provider = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider.TWITCH,
            scope = null,
        )
    }

    private fun resolveSegments(
        segments: List<ChatSegment>,
        catalog: ChatCatalogSnapshot,
        resolveThirdParty: Boolean = true,
        personalEmoteSetId: String? = null,
    ): List<ChatSegment> {
        val resolved = ArrayList<ChatSegment>()
        segments.forEach { segment ->
            if (segment is ChatSegment.Text) {
                if (resolveThirdParty) tokenizeThirdPartyText(segment.text, catalog, resolved, personalEmoteSetId)
                else resolved += segment
            } else {
                resolved += segment
            }
        }
        return resolved
    }

    private fun tokenizeThirdPartyText(
        text: String,
        catalog: ChatCatalogSnapshot,
        output: MutableList<ChatSegment>,
        personalEmoteSetId: String?,
    ) {
        Regex("\\s+|\\S+").findAll(text).forEach { match ->
            val token = match.value
            if (token.firstOrNull()?.isWhitespace() == true) {
                output += ChatSegment.Text(token)
                return@forEach
            }
            val definition = catalog.lookupThirdParty(token, showPersonalEmotes, personalEmoteSetId)
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

    private fun extractClipPreviews(message: ChatMessage): List<ChatClipPreviewLink> {
        val body = message.rawText ?: message.segments.joinToString("") { segment ->
            when (segment) {
                is ChatSegment.Text -> segment.text
                is ChatSegment.Mention -> segment.text
                is ChatSegment.Emote -> segment.fallbackText
                is ChatSegment.Gif -> segment.fallbackText
                is ChatSegment.Cheermote -> segment.text
            }
        }
        return ChatClipPreviewLink.parse(body)
    }

    private companion object {
        val SUBSCRIPTION_NOTICE_TYPES = setOf(
            "sub",
            "resub",
            "subgift",
            "submysterygift",
            "giftpaidupgrade",
            "anongiftpaidupgrade",
            "prime_paid_upgrade",
            "gift_paid_upgrade",
            "sub_gift",
            "community_sub_gift",
            "shared_chat_sub",
            "shared_chat_resub",
            "shared_chat_sub_gift",
            "shared_chat_community_sub_gift",
            "pay_it_forward",
            "shared_chat_gift_paid_upgrade",
            "shared_chat_prime_paid_upgrade",
            "shared_chat_pay_it_forward",
        )
    }

    private fun compositeColors(foreground: Int, background: Int): Int {
        val foregroundAlpha = foreground ushr 24 and 0xff
        val backgroundAlpha = background ushr 24 and 0xff
        val outputAlpha = 255 - ((255 - foregroundAlpha) * (255 - backgroundAlpha) / 255)
        if (outputAlpha == 0) return 0

        fun compositeComponent(foregroundComponent: Int, backgroundComponent: Int): Int =
            (foregroundComponent * foregroundAlpha * 255 +
                backgroundComponent * backgroundAlpha * (255 - foregroundAlpha)) /
                (outputAlpha * 255)

        return (outputAlpha shl 24) or
            (compositeComponent(foreground ushr 16 and 0xff, background ushr 16 and 0xff) shl 16) or
            (compositeComponent(foreground ushr 8 and 0xff, background ushr 8 and 0xff) shl 8) or
            compositeComponent(foreground and 0xff, background and 0xff)
    }
}

private fun ChatCatalogSnapshot.lookupThirdParty(
    token: String,
    includePersonal: Boolean,
    personalEmoteSetId: String?,
): ChatCatalogEmote? {
    fun ScopedEmoteCatalog.lookup(): ChatCatalogEmote? =
        lookup(token, personalEmoteSetId.takeIf { includePersonal })
    return sevenTv.lookup() ?: bttv.lookup() ?: ffz.lookup()
}

private fun messagePersonalEmoteSetId(
    message: ChatMessage,
    catalog: ChatCatalogSnapshot,
): String? = message.user?.id?.let(catalog.userDecorations::get)?.personalEmoteSetId

private fun com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser.displayName(mode: String): String? {
    val display = displayName?.takeIf { it.isNotBlank() }
    val loginName = login?.takeIf { it.isNotBlank() }
    return when {
        display == null -> loginName
        loginName == null || display.equals(loginName, ignoreCase = true) -> display
        mode == "0" -> "$display($loginName)"
        mode == "1" -> display
        else -> loginName
    }
}
