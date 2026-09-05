package com.github.andreyasadchy.xtra.ui.chat.v2.domain

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope

data class ChatUser(
    val id: String?,
    val login: String?,
    val displayName: String?,
    val color: Int?,
)

/** Structured subscription notice metadata shared by IRC and EventSub transports. */
data class ChatSubscription(
    val tier: String? = null,
    val months: Int? = null,
    val streakMonths: Int? = null,
    val recipientName: String? = null,
    val giftCount: Int? = null,
    val isCommunityGift: Boolean = false,
    val isAnonymous: Boolean = false,
    val isUpgrade: Boolean = false,
)

/**
 * Twitch has several transport-specific names for the same subscription event family.
 * Keep that normalization in the domain boundary so presentation code does not duplicate
 * protocol strings or infer semantics from localized system text.
 */
object ChatSubscriptionNoticeTypes {
    private val names = setOf(
        "sub", "resub", "subgift", "submysterygift", "giftpaidupgrade", "anongiftpaidupgrade",
        "prime_paid_upgrade", "gift_paid_upgrade", "sub_gift", "community_sub_gift",
        "shared_chat_sub", "shared_chat_resub", "shared_chat_sub_gift",
        "shared_chat_community_sub_gift", "pay_it_forward", "shared_chat_gift_paid_upgrade",
        "shared_chat_prime_paid_upgrade", "shared_chat_pay_it_forward",
    )
    private val giftNames = setOf(
        "subgift", "submysterygift", "giftpaidupgrade", "anongiftpaidupgrade",
        "gift_paid_upgrade", "sub_gift", "community_sub_gift", "shared_chat_sub_gift",
        "shared_chat_community_sub_gift", "pay_it_forward", "shared_chat_gift_paid_upgrade",
        "shared_chat_prime_paid_upgrade", "shared_chat_pay_it_forward",
    )

    fun isSubscription(type: String?): Boolean = type?.lowercase() in names

    fun isGift(type: String?): Boolean = type?.lowercase() in giftNames

    fun isCommunityGift(type: String?): Boolean = type?.lowercase()?.endsWith("community_sub_gift") == true

    fun isUpgrade(type: String?): Boolean = type?.lowercase()?.let {
        it.contains("paid_upgrade") || it.contains("pay_it_forward")
    } == true

    fun isAnonymous(type: String?): Boolean = type?.lowercase()?.startsWith("anon") == true
}

data class ChatBadgeRef(
    val setId: String,
    val versionId: String,
    val info: String? = null,
    val asset: ChatAssetSpec = ChatAssetSpec(
        key = ChatAssetKey("twitch-badge:$setId:$versionId"),
        sourceWidth = 18,
        sourceHeight = 18,
        targetHeight = 18,
    ),
) {
    val id: String get() = setId
    val version: String get() = versionId
    val catalogKey: String get() = "$setId:$versionId"
}

data class ChatAssetSpec(
    val key: ChatAssetKey,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetHeight: Int,
    val overlays: List<ChatAssetSpec> = emptyList(),
) {
    val computedWidth: Int
        get() = (sourceWidth.toLong() * targetHeight / sourceHeight.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    val compositionKey: String
        get() = buildString {
            append(key.value).append(':').append(sourceWidth).append('x').append(sourceHeight).append('@').append(targetHeight)
            overlays.forEach { append('+').append(it.compositionKey) }
        }
    val compositionWidth: Int
        get() = maxOf(computedWidth, overlays.maxOfOrNull(ChatAssetSpec::compositionWidth) ?: 0)
    val compositionHeight: Int
        get() = maxOf(targetHeight, overlays.maxOfOrNull(ChatAssetSpec::compositionHeight) ?: 0)
    fun scaledTo(height: Int): ChatAssetSpec = copy(targetHeight = height, overlays = overlays.map { it.scaledTo(height) })
}

data class ChatEmoteInteraction(
    val id: String?,
    val name: String,
    val url: String?,
    val animated: Boolean,
    val provider: ChatAssetProvider,
    val scope: ChatEmoteScope?,
)

data class ChatGifInteraction(
    val id: String,
    val description: String,
    val url: String,
)

data class ChatReply(
    val parentMessageId: ChatMessageId,
    val parentMessageBody: String?,
    val parentUserId: String?,
    val parentUserName: String?,
    val parentUserLogin: String?,
    val threadMessageId: ChatMessageId?,
    val threadUserId: String?,
    val threadUserName: String?,
    val threadUserLogin: String?,
) {
    val parentId: ChatMessageId get() = parentMessageId
    val parentText: String? get() = parentMessageBody
}

data class SharedChatSource(
    val broadcasterId: String,
    val broadcasterLogin: String?,
    val broadcasterName: String?,
    val messageId: ChatMessageId?,
    val badges: List<ChatBadgeRef>,
    val sourceOnly: Boolean,
)

sealed interface ChatSegment {
    data class Text(val text: String) : ChatSegment
    data class Mention(val text: String, val userId: String?, val login: String?) : ChatSegment
    data class Emote(
        val asset: ChatAssetSpec,
        val fallbackText: String,
        val animated: Boolean,
        val interaction: ChatEmoteInteraction? = null,
    ) : ChatSegment
    data class Gif(
        val gifId: String,
        val url: String,
        val fallbackText: String,
        val interaction: ChatGifInteraction = ChatGifInteraction(gifId, fallbackText, url),
    ) : ChatSegment
    data class Cheermote(val asset: ChatAssetSpec, val text: String, val bits: Int, val color: Int?) : ChatSegment
}

sealed interface TwitchChatMessageType {
    data object Text : TwitchChatMessageType
    data object Highlighted : TwitchChatMessageType
    data object SubscriberOnly : TwitchChatMessageType
    data object UserIntro : TwitchChatMessageType
    data object MessageEffect : TwitchChatMessageType
    data object GigantifiedEmote : TwitchChatMessageType
    data class Unknown(val raw: String) : TwitchChatMessageType
}

enum class ChatMessageKind { CHAT, ACTION, NOTICE, REWARD, SYSTEM, RAID, ANNOUNCEMENT }

data class ChatMessage(
    val id: ChatMessageId,
    val channelId: String,
    val timestampMs: Long,
    val user: ChatUser?,
    val badges: List<ChatBadgeRef>,
    val segments: List<ChatSegment>,
    /** The exact plain-text body supplied by Twitch, including GIF message text. */
    val rawText: String? = null,
    val kind: ChatMessageKind,
    val reply: ChatReply? = null,
    val source: SharedChatSource? = null,
    val rewardId: String? = null,
    val rewardTitle: String? = null,
    val rewardCost: Int? = null,
    val rewardImageUrl: String? = null,
    /** Redemption identity from PubSub/EventSub when the message is synthetic. */
    val rewardRedemptionId: String? = null,
    val isFirst: Boolean = false,
    val bits: Int? = null,
    val watchStreakCount: Int? = null,
    val watchStreakPoints: Int? = null,
    val twitchType: TwitchChatMessageType = TwitchChatMessageType.Text,
    val systemText: String? = null,
    val moderation: ChatModeration? = null,
    val noticeType: String? = null,
    /** Twitch reports Prime and paid subscription notices differently. */
    val subscriptionPlan: String? = null,
    val subscriptionTier: String? = null,
    val isPrimeSubscription: Boolean? = null,
    val subscription: ChatSubscription? = null,
)
