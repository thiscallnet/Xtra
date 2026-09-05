package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.ChatHighlightSettings
import com.github.andreyasadchy.xtra.ui.chat.shouldHighlightV2ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModeration
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSubscriptionNoticeTypes
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUserClearReason
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
    val moderationSuffix: (ChatModeration) -> String = { moderation ->
        when (moderation.reason) {
            ChatUserClearReason.TIMEOUT ->
                "(${moderation.timeoutSeconds ?: 0}s Timeout)"
            ChatUserClearReason.BAN -> "(Ban)"
            ChatUserClearReason.MESSAGES_CLEARED -> "(Messages cleared)"
        }
    },
    val announcement: String = "Announcement",
    val raid: String = "Raid",
    val notice: String = "Notice",
    val anonymous: String = "Anonymous",
    val viewer: String = "Viewer",
    val reward: String = "Channel points reward",
    val userRedeemed: (String) -> String = { "redeemed $it" },
    val subscriptionPrime: String = "subscribed with Prime Gaming",
    val subscriptionPaid: (String) -> String = { "subscribed at $it" },
    val subscriptionUpgrade: (String) -> String = { "upgraded to a paid $it Sub" },
    val subscriptionGift: (String, String) -> String = { tier, recipient ->
        "gifted a $tier Sub to $recipient"
    },
    val subscriptionCommunityGift: (Int, String) -> String = { count, tier ->
        "gifted $count $tier ${if (count == 1) "Sub" else "Subs"} to the community"
    },
    val subscriptionMonths: (Int) -> String = { months ->
        "$months ${if (months == 1) "month" else "months"} subscribed"
    },
    val subscriptionStreak: (Int) -> String = { months ->
        "$months ${if (months == 1) "month" else "months"} streak"
    },
    val subscriptionAccessibilityMonths: (Int) -> String = { months ->
        "They've been subscribed for $months ${if (months == 1) "month" else "months"}"
    },
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
        val isWatchStreak = message.noticeType.equals("watch_streak", ignoreCase = true) ||
            (message.noticeType.equals("viewermilestone", ignoreCase = true) && message.watchStreakCount != null)
        val noticeType = message.noticeType?.lowercase()
        val isSubscription = ChatSubscriptionNoticeTypes.isSubscription(noticeType)
        val isPrimeSubscription = isSubscription && (
            message.isPrimeSubscription == true ||
                message.subscriptionPlan?.contains("prime", ignoreCase = true) == true ||
                message.subscription?.tier?.contains("prime", ignoreCase = true) == true
            )
        val reward = ChatRewardCatalog(
            byId = catalog.channelPointRewards,
            automaticByType = catalog.automaticChannelPointRewards,
        ).rewardFor(message)
        val hasSemanticBody = message.segments.any {
            it !is ChatSegment.Text || it.text.isNotBlank()
        }
        val eventKind = eventKindFor(
            message = message,
            noticeType = noticeType,
            isSubscription = isSubscription,
            isWatchStreak = isWatchStreak,
        )
        if (eventKind != null) {
            return compileEventRow(
                message = message,
                catalog = catalog,
                eventKind = eventKind,
                reward = reward,
                isPrimeSubscription = isPrimeSubscription,
                hasSemanticBody = hasSemanticBody,
            )
        }
        val resolvedSegments = resolveSegments(
            message.segments,
            catalog,
            personalEmoteSetId = messagePersonalEmoteSetId(message, catalog),
        )
        val targetHeight = emoteTargetHeight(message, resolvedSegments)
        val moderationSuffix = message.moderation?.let(labels.moderationSuffix)
        val clipPreviews = extractClipPreviews(message)
        val isFirstChatterMessage = message.isFirst || message.twitchType == TwitchChatMessageType.UserIntro
        val hasFirstChatterTint = isFirstChatterMessage && firstMessageVisibility == 1
        val hasRewardBackground = message.rewardId != null && firstMessageVisibility < 2
        val hasNoticeBackground = message.kind != ChatMessageKind.CHAT ||
            !noticeType.isNullOrBlank() ||
            !message.systemText.isNullOrBlank()
        val hasSpecialBackground =
            (isFirstChatterMessage && firstMessageVisibility in 0..1) ||
                hasRewardBackground ||
                hasNoticeBackground
        val isPersonalHighlight = shouldHighlightV2ChatMessage(message, highlightSettings) && !hasSpecialBackground
        val baseBackground = background(message)
        val rowBackground = if (isPersonalHighlight) {
            compositeColors(highlightSettings.color, baseBackground)
        } else {
            baseBackground
        }
        val mutedColor = colors.mutedTextColor(rowBackground)
        var moderationPieceRange: IntRange? = null
        val pieces = buildList {
            message.reply?.let { reply ->
                val user = reply.parentUserName ?: reply.parentUserLogin ?: ""
                val body = reply.parentMessageBody.orEmpty()
                add(ChatPiece.Icon(R.drawable.ic_chat_reply, tint = mutedColor, sizeDp = 18))
                val replyUser = user
                add(
                    ChatPiece.Reply(
                        value = " ${labels.reply(replyUser, body)}",
                        color = mutedColor,
                        parentUser = replyUser.takeIf { it.isNotBlank() },
                        parentMessage = body,
                    ),
                )
                add(ChatPiece.Text("\n", color = mutedColor))
            }
            if (showBadges) message.badges
                .filter { it.setId.isNotBlank() && it.versionId.isNotBlank() }
                .mapNotNull { badge -> badgePiece(badge, catalog) }
                .forEach(::add)
            if (showThirdPartyBadges) {
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
            message.source?.let { source ->
                val sourceName = source.broadcasterName ?: source.broadcasterLogin ?: source.broadcasterId
                add(ChatPiece.Source(sourceName, colors.mutedTextColor(rowBackground)))
                add(ChatPiece.Text(" "))
            }
            val moderationStart = message.moderation?.let { size }
            message.user?.let { user ->
                val name = user.displayName(nameDisplay)
                name?.let {
                    add(ChatPiece.Username(
                        it,
                        colors.resolve(user.color?.let(::colorToHex), user.id ?: user.login ?: it, rowBackground),
                        bold = boldNames,
                        paint = if (showNamePaints) {
                            user.id?.let(catalog.userDecorations::get)?.paintId?.let(catalog.namePaints::get)
                        } else null,
                    ))
                }
            }
            addAll(renderSegments(resolvedSegments, catalog, targetHeight))
            moderationStart?.let { moderationPieceRange = it until size }
            moderationSuffix?.let { suffix ->
                add(ChatPiece.Text(" $suffix", color = colors.brightTextColor(rowBackground)))
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
                isPersonalHighlight -> ChatRowBackground.PERSONAL_HIGHLIGHT
                hasFirstChatterTint -> ChatRowBackground.FIRST_CHATTER_TINT
                hasRewardBackground -> ChatRowBackground.REWARD
                hasNoticeBackground -> ChatRowBackground.NOTICE
                else -> ChatRowBackground.NORMAL
            },
            reply = message.reply,
            moderation = message.moderation,
            moderationColor = message.moderation?.let { mutedColor },
            moderationPieceRange = moderationPieceRange,
            source = message.source,
            isAction = message.kind == com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind.ACTION,
            twitchType = message.twitchType,
            clipPreviews = clipPreviews,
            accessibilityText = buildString {
                message.user?.let { user ->
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
                moderationSuffix?.let { append(" ").append(it) }
            },
        )
    }

    private fun emoteTargetHeight(
        message: ChatMessage,
        resolvedSegments: List<ChatSegment>,
    ): Int {
        val isEmoteOnly = resolvedSegments.isNotEmpty() && resolvedSegments.all { segment ->
            when (segment) {
                is ChatSegment.Emote -> true
                is ChatSegment.Text -> segment.text.isBlank()
                else -> false
            }
        }
        return emoteHeightPx * when {
            message.twitchType == TwitchChatMessageType.GigantifiedEmote -> 2
            isEmoteOnly -> 2
            else -> 1
        }
    }

    private fun eventKindFor(
        message: ChatMessage,
        noticeType: String?,
        isSubscription: Boolean,
        isWatchStreak: Boolean,
    ): ChatEventKind? {
        val isFirstChatterMessage = message.isFirst || message.twitchType == TwitchChatMessageType.UserIntro
        val isFirstChatter = isFirstChatterMessage &&
            firstMessageVisibility == 0
        val isSuppressedReward = message.rewardId != null && firstMessageVisibility >= 2
        val isSuppressedFirstChatter = isFirstChatterMessage && firstMessageVisibility != 0
        return when {
            message.twitchType == TwitchChatMessageType.Highlighted -> ChatEventKind.HIGHLIGHT
            isWatchStreak -> ChatEventKind.WATCH_STREAK
            isSubscription -> ChatEventKind.SUBSCRIPTION
            isFirstChatter -> ChatEventKind.FIRST_CHATTER
            message.rewardId != null && firstMessageVisibility < 2 -> ChatEventKind.CHANNEL_POINTS
            !isSuppressedFirstChatter && message.kind == ChatMessageKind.ANNOUNCEMENT -> ChatEventKind.ANNOUNCEMENT
            !isSuppressedFirstChatter && message.kind == ChatMessageKind.RAID -> ChatEventKind.RAID
            !isSuppressedReward && !isSuppressedFirstChatter && (
                message.kind == ChatMessageKind.NOTICE ||
                    message.kind == ChatMessageKind.SYSTEM ||
                    !noticeType.isNullOrBlank() ||
                    !message.systemText.isNullOrBlank()
                ) -> ChatEventKind.NOTICE
            else -> null
        }
    }

    private fun compileEventRow(
        message: ChatMessage,
        catalog: ChatCatalogSnapshot,
        eventKind: ChatEventKind,
        reward: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward?,
        isPrimeSubscription: Boolean,
        hasSemanticBody: Boolean,
    ): ChatRowUiModel {
        val baseBackground = background(message)
        val mutedColor = colors.mutedTextColor(baseBackground)
        val systemEvent = eventKind == ChatEventKind.ANNOUNCEMENT ||
            eventKind == ChatEventKind.RAID ||
            eventKind == ChatEventKind.NOTICE
        val resolvedSegments = resolveSegments(
            message.segments,
            catalog,
            resolveThirdParty = !systemEvent || showSystemMessageEmotes,
            personalEmoteSetId = messagePersonalEmoteSetId(message, catalog),
        )
        val targetHeight = emoteTargetHeight(message, resolvedSegments)
        val bodyPieces = if (hasSemanticBody) {
            messageBodyPieces(message, resolvedSegments, catalog, targetHeight, baseBackground)
        } else {
            emptyList()
        }
        val event = buildEventPresentation(
            message = message,
            eventKind = eventKind,
            reward = reward,
            isPrimeSubscription = isPrimeSubscription,
            resolvedSegments = resolvedSegments,
            bodyPieces = bodyPieces,
            baseBackground = baseBackground,
            mutedColor = mutedColor,
        )
        var moderationStart: Int? = null
        var moderationEnd: Int? = null
        val pieces = buildList {
            message.reply?.let { reply ->
                val user = reply.parentUserName ?: reply.parentUserLogin ?: ""
                val body = reply.parentMessageBody.orEmpty()
                add(ChatPiece.Icon(R.drawable.ic_chat_reply, tint = mutedColor, sizeDp = 18))
                val replyUser = user
                add(
                    ChatPiece.Reply(
                        value = " ${labels.reply(replyUser, body)}",
                        color = mutedColor,
                        parentUser = replyUser.takeIf { it.isNotBlank() },
                        parentMessage = body,
                    ),
                )
                add(ChatPiece.Text("\n", color = mutedColor))
            }
            addAll(event.flatten())
            if (message.moderation != null && event.bodyPieces.isNotEmpty()) {
                moderationStart = size - event.bodyPieces.size
                moderationEnd = size
            }
            message.moderation?.let { add(ChatPiece.Text(" ${labels.moderationSuffix(it)}", color = colors.brightTextColor(baseBackground))) }
            translation(message)?.takeIf { it.isNotBlank() }?.let {
                add(ChatPiece.Text("\n$it", color = mutedColor))
            }
        }
        val accessibility = buildString {
            append(event.accessibilityText)
            message.moderation?.let { append(" ").append(labels.moderationSuffix(it)) }
        }
        return ChatRowUiModel(
            id = message.id,
            channelId = message.channelId,
            timestampText = timestampText(message.timestampMs),
            timestampColor = colors.resolve("#999999", rowBackground = baseBackground),
            pieces = pieces,
            background = baseBackground,
            backgroundStyle = ChatRowBackground.EVENT,
            eventPresentation = event,
            accessibilityText = accessibility,
            moderation = message.moderation,
            moderationColor = message.moderation?.let { mutedColor },
            moderationPieceRange = moderationStart?.let { start ->
                moderationEnd?.let { end -> start until end }
            },
            reply = message.reply,
            source = message.source,
            isAction = message.kind == ChatMessageKind.ACTION,
            twitchType = message.twitchType,
            clipPreviews = extractClipPreviews(message),
        )
    }

    private fun buildEventPresentation(
        message: ChatMessage,
        eventKind: ChatEventKind,
        reward: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward?,
        isPrimeSubscription: Boolean,
        resolvedSegments: List<ChatSegment>,
        bodyPieces: List<ChatPiece>,
        baseBackground: Int,
        mutedColor: Int,
    ): ChatEventPresentation {
        val headingColor = colors.brightTextColor(baseBackground)
        val actorName = message.user?.displayName(nameDisplay)
        val messageText = segmentsAccessibilityText(resolvedSegments)
        val bodyAccessibility = messageText.takeIf { it.isNotBlank() }?.let { " Message: $it." }.orEmpty()
        val sourcePieces = message.source?.let { source ->
            val sourceName = source.broadcasterName ?: source.broadcasterLogin ?: source.broadcasterId
            listOf<ChatPiece>(ChatPiece.Source(sourceName, mutedColor), ChatPiece.Text(" "))
        }.orEmpty()
        fun withSource(pieces: List<ChatPiece>): List<ChatPiece> = sourcePieces + pieces
        fun actorPiece(name: String? = actorName, anonymous: Boolean = false): ChatPiece.Text? {
            val value = if (anonymous) labels.anonymous else name ?: return null
            val color = if (anonymous || message.user == null) {
                headingColor
            } else {
                colors.resolve(
                    message.user.color?.let(::colorToHex),
                    message.user.id ?: message.user.login ?: value,
                    baseBackground,
                )
            }
            return ChatPiece.Text(value, color = color, bold = true)
        }
        fun actorTitle(description: String, anonymous: Boolean = false, name: String? = actorName): List<ChatPiece> =
            buildList {
                actorPiece(name, anonymous)?.let {
                    add(it)
                    add(ChatPiece.Text(" "))
                }
                add(ChatPiece.Text(description, color = headingColor, bold = true))
            }
        fun accessibilityActor(anonymous: Boolean = false): String =
            if (anonymous) labels.anonymous else actorName ?: labels.viewer
        fun messageAccessibility(prefix: String): String = buildString {
            append(prefix).append('.')
            actorName?.let { append(' ').append(it).append(':') }
            if (messageText.isNotBlank()) append(' ').append(messageText).append('.')
        }

        return when (eventKind) {
            ChatEventKind.WATCH_STREAK -> {
                val statusMarker = "\uE000"
                val status = message.watchStreakCount?.let { labels.watchStreakStatus(statusMarker, it) }
                val statusPieces = status?.let { localizedStatusPieces(it, statusMarker, actorPiece(), mutedColor) }.orEmpty()
                val title = buildList {
                    add(ChatPiece.Text(labels.watchStreakReached, color = headingColor, bold = true))
                    message.watchStreakPoints?.let { points ->
                        add(ChatPiece.Text("  "))
                        add(ChatPiece.Icon(R.drawable.ic_chat_channel_points, tint = headingColor, sizeDp = 18))
                        add(ChatPiece.Text(" +${formatNumber(points)}", color = headingColor, bold = true))
                    }
                }
                val metadata = withSource(statusPieces)
                val count = message.watchStreakCount
                val earned = message.watchStreakPoints
                val statusAccessibility = when {
                    count != null -> "${accessibilityActor()} is currently on a $count-stream streak!"
                    else -> null
                }
                val accessibility = buildString {
                    append(labels.watchStreakReached)
                    statusAccessibility?.let { append(". ").append(it) }
                    earned?.let { append(" and earned ").append(formatNumber(it)).append(" channel points") }
                    append('.').append(bodyAccessibility)
                }
                ChatEventPresentation(
                    kind = eventKind,
                    visualStyle = ChatEventVisualStyle.STREAK,
                    icon = ChatPiece.Icon(R.drawable.ic_watch_streak, sizeDp = ChatEventVisualTokens.iconSizeDp),
                    titlePieces = title,
                    metadataPieces = metadata,
                    bodyPieces = bodyPieces,
                    accessibilityText = accessibility,
                )
            }
            ChatEventKind.SUBSCRIPTION -> {
                val details = message.subscription
                val noticeType = message.noticeType?.lowercase().orEmpty()
                val communityGift = details?.isCommunityGift == true || ChatSubscriptionNoticeTypes.isCommunityGift(noticeType)
                val gift = communityGift || details?.recipientName != null || ChatSubscriptionNoticeTypes.isGift(noticeType)
                val upgrade = details?.isUpgrade == true || ChatSubscriptionNoticeTypes.isUpgrade(noticeType)
                val primeVisual = isPrimeSubscription && !upgrade
                val tier = subscriptionTierLabel(details?.tier ?: message.subscriptionTier ?: message.subscriptionPlan)
                val legacyActor = actorName ?: legacySystemActor(message.systemText)
                val anonymous = details?.isAnonymous == true || ChatSubscriptionNoticeTypes.isAnonymous(noticeType)
                val description = when {
                    communityGift && tier != null -> labels.subscriptionCommunityGift(details?.giftCount ?: 1, tier)
                    gift && tier != null && details?.recipientName != null -> labels.subscriptionGift(tier, details.recipientName)
                    upgrade && tier != null -> labels.subscriptionUpgrade(tier)
                    primeVisual -> labels.subscriptionPrime
                    tier != null -> labels.subscriptionPaid(tier)
                    else -> null
                }
                val title = if (description != null) {
                    actorTitle(description, anonymous, legacyActor)
                } else {
                    fallbackSystemTitle(message.systemText, legacyActor, anonymous, headingColor, baseBackground)
                }
                val metadata = withSource(buildList {
                    details?.months?.let { add(ChatPiece.Text(labels.subscriptionMonths(it), color = mutedColor)) }
                    details?.streakMonths?.let {
                        if (isNotEmpty()) add(ChatPiece.Text(" · ", color = mutedColor))
                        add(ChatPiece.Text(labels.subscriptionStreak(it), color = mutedColor))
                    }
                })
                val actorAccessibility = if (anonymous) labels.anonymous else legacyActor ?: labels.viewer
                val semanticAccessibility = when {
                    communityGift && tier != null -> "$actorAccessibility gifted ${details?.giftCount ?: 1} $tier subscriptions to the community."
                    gift && tier != null && details?.recipientName != null -> "$actorAccessibility gifted a $tier subscription to ${details.recipientName}."
                    upgrade && tier != null -> "$actorAccessibility upgraded to a paid $tier subscription."
                    primeVisual -> "$actorAccessibility ${labels.subscriptionPrime}."
                    tier != null -> "$actorAccessibility ${labels.subscriptionPaid(tier)}."
                    !message.systemText.isNullOrBlank() -> message.systemText.trim()
                    else -> "$actorAccessibility subscribed."
                }
                val accessibility = buildString {
                    append(semanticAccessibility)
                    details?.months?.let { append(" ").append(labels.subscriptionAccessibilityMonths(it)).append('.') }
                    append(bodyAccessibility)
                }
                ChatEventPresentation(
                    kind = eventKind,
                    visualStyle = ChatEventVisualStyle.SUPPORT,
                    icon = ChatPiece.Icon(
                        if (primeVisual) R.drawable.ic_chat_subscription else R.drawable.ic_chat_subscription_gift,
                        tint = headingColor,
                        sizeDp = ChatEventVisualTokens.iconSizeDp,
                    ),
                    titlePieces = title,
                    metadataPieces = metadata,
                    bodyPieces = bodyPieces,
                    accessibilityText = accessibility,
                )
            }
            ChatEventKind.CHANNEL_POINTS,
            ChatEventKind.HIGHLIGHT,
            -> {
                val isHighlight = eventKind == ChatEventKind.HIGHLIGHT
                val rewardTitle = if (isHighlight) labels.highlightTitle else reward?.title ?: message.rewardTitle ?: labels.reward
                val description = labels.userRedeemed(rewardTitle)
                val title = actorTitle(description, name = actorName)
                val cost = reward?.cost ?: message.rewardCost
                val metadata = withSource(buildList {
                    cost?.let {
                        add(ChatPiece.Icon(R.drawable.ic_chat_channel_points, tint = headingColor, sizeDp = 18))
                        add(ChatPiece.Text(" ", color = mutedColor))
                        add(ChatPiece.Text(formatNumber(it), color = headingColor, bold = true))
                    }
                })
                val icon = rewardEventIcon(reward, message.rewardImageUrl, headingColor)
                val actor = accessibilityActor()
                val accessibility = buildString {
                    append(actor).append(' ').append(description)
                    cost?.let { append(" for ").append(formatNumber(it)).append(" channel points") }
                    append('.').append(bodyAccessibility)
                }
                ChatEventPresentation(
                    kind = eventKind,
                    visualStyle = ChatEventVisualStyle.REWARD,
                    icon = icon,
                    titlePieces = title,
                    metadataPieces = metadata,
                    bodyPieces = bodyPieces,
                    accessibilityText = accessibility,
                )
            }
            ChatEventKind.FIRST_CHATTER -> ChatEventPresentation(
                kind = eventKind,
                visualStyle = ChatEventVisualStyle.INTRO,
                icon = ChatPiece.Icon(R.drawable.ic_chat_first_chatter, tint = headingColor, sizeDp = ChatEventVisualTokens.iconSizeDp),
                titlePieces = listOf(ChatPiece.Text(labels.firstChatter, color = headingColor, bold = true)),
                metadataPieces = withSource(emptyList()),
                bodyPieces = bodyPieces,
                accessibilityText = messageAccessibility(labels.firstChatter),
            )
            ChatEventKind.ANNOUNCEMENT,
            ChatEventKind.RAID,
            ChatEventKind.NOTICE,
            -> {
                val title = when (eventKind) {
                    ChatEventKind.ANNOUNCEMENT -> labels.announcement
                    ChatEventKind.RAID -> labels.raid
                    else -> message.systemText?.trim()?.takeIf { it.isNotBlank() } ?: labels.notice
                }
                val systemDetail = if (eventKind == ChatEventKind.NOTICE) emptyList() else {
                    message.systemText?.trim()?.takeIf { it.isNotBlank() }?.let {
                        listOf<ChatPiece>(ChatPiece.Text(it, color = mutedColor))
                    }.orEmpty()
                }
                val accessibility = buildString {
                    append(title).append('.')
                    if (systemDetail.isNotEmpty()) append(' ').append(message.systemText?.trim())
                    append(bodyAccessibility)
                }
                ChatEventPresentation(
                    kind = eventKind,
                    visualStyle = ChatEventVisualStyle.NOTICE,
                    icon = ChatPiece.Icon(R.drawable.ic_chat_speaker_muted, tint = headingColor, sizeDp = ChatEventVisualTokens.iconSizeDp),
                    titlePieces = listOf(ChatPiece.Text(title, color = headingColor, bold = true)),
                    metadataPieces = withSource(systemDetail),
                    bodyPieces = bodyPieces,
                    accessibilityText = accessibility,
                )
            }
        }
    }

    private fun messageBodyPieces(
        message: ChatMessage,
        segments: List<ChatSegment>,
        catalog: ChatCatalogSnapshot,
        targetHeight: Int,
        rowBackground: Int,
    ): List<ChatPiece> = buildList {
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
                    ),
                )
            }
        }
        addAll(renderSegments(segments, catalog, targetHeight))
    }

    private fun renderSegments(
        segments: List<ChatSegment>,
        catalog: ChatCatalogSnapshot,
        targetHeight: Int,
    ): List<ChatPiece> = buildList {
        segments.forEach { segment ->
            when (segment) {
                is ChatSegment.Text -> add(ChatPiece.Text(segment.text))
                is ChatSegment.Mention -> add(ChatPiece.Mention(segment.text, segment.userId, segment.login))
                is ChatSegment.Emote -> add(
                    ChatPiece.Emote(segment.asset.scaledTo(targetHeight), segment.fallbackText, segment.animated, segment.interaction),
                )
                is ChatSegment.Gif -> when (gifDisplayMode) {
                    ChatGifDisplayMode.LINK -> add(ChatPiece.Text(segment.url))
                    ChatGifDisplayMode.EMOTE -> add(
                        ChatPiece.Gif(
                            ChatAssetSpec(ChatAssetKey(segment.url), 16, 9, emoteHeightPx),
                            segment.url,
                            segment.fallbackText,
                            segment.interaction,
                        ),
                    )
                    ChatGifDisplayMode.LARGE -> {
                        if (lastOrNull() !is ChatPiece.Text || !(last() as ChatPiece.Text).value.endsWith("\n")) {
                            add(ChatPiece.Text("\n"))
                        }
                        add(
                            ChatPiece.Gif(
                                ChatAssetSpec(ChatAssetKey(segment.url), 16, 9, 180),
                                segment.url,
                                segment.fallbackText,
                                segment.interaction,
                            ),
                        )
                        add(ChatPiece.Text("\n"))
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
                                animated = catalogCheermote?.animated == true,
                                provider = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider.TWITCH,
                                scope = null,
                            ),
                        ),
                    )
                }
            }
        }
    }

    private fun localizedStatusPieces(
        status: String,
        marker: String,
        actor: ChatPiece.Text?,
        mutedColor: Int,
    ): List<ChatPiece> {
        val markerStart = status.indexOf(marker)
        if (markerStart < 0 || actor == null) return listOf(ChatPiece.Text(status, color = mutedColor))
        return buildList {
            status.substring(0, markerStart).takeIf { it.isNotEmpty() }?.let {
                add(ChatPiece.Text(it, color = mutedColor))
            }
            add(actor)
            add(ChatPiece.Text(status.substring(markerStart + marker.length), color = mutedColor))
        }
    }

    private fun fallbackSystemTitle(
        systemText: String?,
        actorName: String?,
        anonymous: Boolean,
        headingColor: Int,
        rowBackground: Int,
    ): List<ChatPiece> {
        val text = systemText?.trim()?.takeIf { it.isNotBlank() } ?: labels.subscriptionPaid("subscription")
        if (anonymous) return listOf(ChatPiece.Text(text, color = headingColor, bold = true))
        val actor = actorName?.takeIf { text.startsWith(it, ignoreCase = true) }
        if (actor == null) return listOf(ChatPiece.Text(text, color = headingColor, bold = true))
        return listOf(
            ChatPiece.Text(actor, color = colors.resolve(null, actor, rowBackground), bold = true),
            ChatPiece.Text(text.substring(actor.length), color = headingColor, bold = true),
        )
    }

    private fun legacySystemActor(systemText: String?): String? = systemText
        ?.trim()
        ?.substringBefore(' ')
        ?.takeIf { it.isNotBlank() && it.lowercase() !in LEGACY_SYSTEM_ACTOR_EXCLUSIONS }

    private fun rewardEventIcon(
        reward: com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward?,
        fallbackImageUrl: String?,
        tint: Int,
    ): ChatPiece = reward?.imageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
        ChatPiece.RewardIcon(
            asset = ChatAssetSpec(
                key = ChatAssetKey(imageUrl),
                sourceWidth = 1,
                sourceHeight = 1,
                targetHeight = badgeHeightPx,
            ),
            fallback = reward.title,
        )
    } ?: fallbackImageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
        ChatPiece.RewardIcon(
            asset = ChatAssetSpec(
                key = ChatAssetKey(imageUrl),
                sourceWidth = 1,
                sourceHeight = 1,
                targetHeight = badgeHeightPx,
            ),
            fallback = reward?.title ?: "reward",
        )
    } ?: ChatPiece.Icon(R.drawable.ic_chat_channel_points, tint = tint, sizeDp = ChatEventVisualTokens.iconSizeDp)

    private fun subscriptionTierLabel(value: String?): String? = value?.trim()?.takeIf { it.isNotBlank() }?.let { tier ->
        when (tier.lowercase()) {
            "1000", "tier 1", "tier1" -> "Tier 1"
            "2000", "tier 2", "tier2" -> "Tier 2"
            "3000", "tier 3", "tier3" -> "Tier 3"
            "prime" -> null
            else -> tier
        }
    }

    private fun formatNumber(value: Int): String = NumberFormat.getInstance().format(value)

    private fun segmentsAccessibilityText(segments: List<ChatSegment>): String = buildString {
        segments.forEach { segment ->
            when (segment) {
                is ChatSegment.Text -> append(segment.text)
                is ChatSegment.Mention -> append(segment.text)
                is ChatSegment.Emote -> append(segment.fallbackText)
                is ChatSegment.Gif -> append(segment.fallbackText)
                is ChatSegment.Cheermote -> append(segment.bits).append(" Bits")
            }
        }
    }.trim()

    private fun badgePiece(badge: ChatBadgeRef, catalog: ChatCatalogSnapshot): ChatPiece.Badge? {
        val definition = catalog.badges[badge.catalogKey] ?: return null
        val assetKey = definition.asset.key.value
        if (!assetKey.startsWith("http://", ignoreCase = true) &&
            !assetKey.startsWith("https://", ignoreCase = true)
        ) return null

        return ChatPiece.Badge(
            asset = definition.asset.scaledTo(badgeHeightPx),
            interaction = ChatEmoteInteraction(
                id = badge.versionId,
                name = definition.info ?: badge.info ?: badge.setId,
                url = definition.asset.key.value,
                animated = false,
                provider = com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider.TWITCH,
                scope = null,
            ),
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
            animated = previous.animated || modifier.animated,
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
        val LEGACY_SYSTEM_ACTOR_EXCLUSIONS = setOf("an", "a", "the", "someone")
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
