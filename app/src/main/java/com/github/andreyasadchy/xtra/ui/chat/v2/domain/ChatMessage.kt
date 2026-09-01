package com.github.andreyasadchy.xtra.ui.chat.v2.domain

data class ChatUser(
    val id: String?,
    val login: String?,
    val displayName: String?,
    val color: Int?,
)

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
    ) : ChatSegment
    data class Gif(val gifId: String, val url: String, val fallbackText: String) : ChatSegment
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
    val kind: ChatMessageKind,
    val reply: ChatReply? = null,
    val source: SharedChatSource? = null,
    val rewardId: String? = null,
    val bits: Int? = null,
    val twitchType: TwitchChatMessageType = TwitchChatMessageType.Text,
    val systemText: String? = null,
    val noticeType: String? = null,
)
