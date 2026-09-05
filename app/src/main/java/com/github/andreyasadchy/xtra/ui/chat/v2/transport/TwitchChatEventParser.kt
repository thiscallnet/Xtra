package com.github.andreyasadchy.xtra.ui.chat.v2.transport

import com.github.andreyasadchy.xtra.model.chat.ChatMessage as LegacyChatMessage
import com.github.andreyasadchy.xtra.model.chat.Reply as LegacyReply
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEmoteInteraction
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSubscription
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSubscriptionNoticeTypes
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUserClearReason
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.SharedChatSource
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Instant

/**
 * Protocol-to-domain normalization for the production transport bridge.
 *
 * EventSub fragments are consumed as structured values. IRC emote offsets are
 * converted once at ingress and ordinary text is intentionally left intact for
 * current-catalog presentation resolution.
 */
object TwitchChatEventParser {
    fun fromIrc(message: ChatUtils.IRCMessage, channelId: String): ChatEvent? = when (message.command) {
        "PRIVMSG" -> {
            val legacy = ChatUtils.parseChatMessage(message)
            ChatEvent.Message(fromLegacy(legacy, channelId, gifTag = message.tags["gifs"]))
        }
        "USERNOTICE" -> ChatEvent.Message(
            fromLegacy(
                ChatUtils.parseChatMessage(message),
                channelId,
                forceNotice = true,
                subscriptionPlan = message.tags["msg-param-sub-plan"]
                    ?: message.tags["msg-param-sub-plan-name"],
                subscriptionTier = message.tags["msg-param-sub-plan"],
                isPrimeSubscription = message.tags["msg-param-sub-plan"]
                    ?.equals("Prime", ignoreCase = true),
                subscription = subscriptionFromIrc(message.tags),
            ),
        )
        "CLEARMSG" -> ChatEvent.Delete(
            messageId = ChatMessageId(message.tags["target-msg-id"] ?: return null),
            eventId = message.tags["target-msg-id"],
            receivedAtMs = timestamp(message.tags["tmi-sent-ts"]),
        )
        "CLEARCHAT" -> {
            val userId = message.tags["target-user-id"]?.takeIf { it.isNotBlank() }
            val userLogin = message.params.getOrNull(1)?.takeIf { it.isNotBlank() }
            if (userId != null || userLogin != null) {
                val timeoutSeconds = message.tags["ban-duration"]?.toIntOrNull()?.takeIf { it > 0 }
                ChatEvent.ClearUser(
                    userId = userId,
                    eventId = message.tags["tmi-sent-ts"] ?: userLogin,
                    receivedAtMs = timestamp(message.tags["tmi-sent-ts"]),
                    userLogin = userLogin,
                    reason = if (timeoutSeconds != null) ChatUserClearReason.TIMEOUT else ChatUserClearReason.BAN,
                    timeoutSeconds = timeoutSeconds,
                )
            } else {
                ChatEvent.Clear(message.tags["id"], timestamp(message.tags["tmi-sent-ts"]))
            }
        }
        "NOTICE" -> ChatEvent.Notice(
            fromLegacy(ChatUtils.parseNotice(message), channelId),
            message.tags["id"],
            timestamp(message.tags["tmi-sent-ts"]),
        )
        "ROOMSTATE" -> ChatEvent.SettingsUpdated(
            channelId = channelId,
            slowModeSeconds = message.tags["slow"]?.toIntOrNull()?.takeIf { it >= 0 },
            followerOnlyDurationMinutes = message.tags["followers-only"]?.toIntOrNull()?.takeIf { it >= 0 },
            subscriberOnly = message.tags["subs-only"] == "1",
            emoteOnly = message.tags["emote-only"] == "1",
            uniqueChatMode = message.tags["r9k"] == "1",
            eventId = message.tags["id"],
            receivedAtMs = timestamp(message.tags["tmi-sent-ts"]),
        )
        else -> null
    }

    fun fromEventSub(event: JSONObject, timestamp: String?, notice: Boolean = false): ChatEvent.Message {
        val message = eventMessage(event, timestamp, notice)
        return ChatEvent.Message(message, eventId = message.id.value, receivedAtMs = System.currentTimeMillis())
    }

    fun fromEventSubRewardRedemption(event: JSONObject, timestamp: String?): ChatEvent.Message {
        val reward = event.optJSONObject("reward")
        val userInput = event.optString("user_input")
        val redeemedAt = timestamp ?: event.optString("redeemed_at").takeIf { it.isNotBlank() }
        val message = ChatMessage(
            id = ChatMessageId(event.optString("id").takeIf { it.isNotBlank() } ?: "redemption-${event.hashCode()}"),
            channelId = event.optString("broadcaster_user_id").takeIf { it.isNotBlank() }.orEmpty(),
            timestampMs = parseTimestamp(redeemedAt),
            user = ChatUser(
                id = event.optString("user_id").takeIf { it.isNotBlank() },
                login = event.optString("user_login").takeIf { it.isNotBlank() },
                displayName = event.optString("user_name").takeIf { it.isNotBlank() },
                color = null,
            ),
            badges = emptyList(),
            segments = userInput.takeIf { it.isNotEmpty() }?.let { listOf(ChatSegment.Text(it)) }.orEmpty(),
            rawText = userInput.takeIf { it.isNotEmpty() },
            kind = ChatMessageKind.REWARD,
            rewardId = reward?.optString("id")?.takeIf { it.isNotBlank() },
            rewardTitle = reward?.optString("title")?.takeIf { it.isNotBlank() },
            rewardCost = reward?.optInt("cost")?.takeIf { it > 0 },
            rewardImageUrl = reward?.optJSONObject("image")?.optString("url_1x")?.takeIf { it.isNotBlank() }
                ?: reward?.optString("image")?.takeIf { it.startsWith("http") },
            rewardRedemptionId = event.optString("id").takeIf { it.isNotBlank() },
            systemText = reward?.optString("title")?.takeIf { it.isNotBlank() },
            noticeType = "channel_points_custom_reward_redemption",
        )
        return ChatEvent.Message(message, eventId = message.id.value, receivedAtMs = System.currentTimeMillis())
    }

    /** Normalizes the legacy unrestricted Hermes redemption event for v2 sessions. */
    fun fromPubSubReward(message: LegacyChatMessage, channelId: String): ChatEvent.Message {
        val base = fromLegacy(message, channelId)
        val reward = message.reward
        val redemptionId = message.fullMsg?.let { raw ->
            runCatching {
                JSONObject(raw).optJSONObject("data")?.optJSONObject("redemption")
                    ?.optString("id")?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        val id = redemptionId?.let { "reward-$it" }
            ?: message.id?.let { "reward-$it" }
            ?: "reward-${message.userId}-${message.timestamp ?: System.currentTimeMillis()}"
        return ChatEvent.Message(
            base.copy(
                id = ChatMessageId(id),
                kind = ChatMessageKind.REWARD,
                segments = if (message.message.isNullOrBlank()) emptyList() else base.segments,
                rawText = message.message?.takeIf { it.isNotEmpty() },
                rewardTitle = reward?.title,
                rewardCost = reward?.cost,
                rewardImageUrl = reward?.url4x ?: reward?.url2x ?: reward?.url1x,
                rewardRedemptionId = redemptionId,
                systemText = reward?.title,
                noticeType = "channel_points_custom_reward_redemption",
            ),
            eventId = redemptionId ?: id,
            receivedAtMs = System.currentTimeMillis(),
        )
    }

    fun fromEventSubClear(event: JSONObject, timestamp: String?, notificationId: String? = null): ChatEvent {
        val receivedAt = parseTimestamp(timestamp)
        val eventId = notificationId?.takeIf { it.isNotBlank() }
        val messageId = event.optString("message_id").takeIf { it.isNotBlank() }
        val userId = event.optString("target_user_id").takeIf { it.isNotBlank() }
        val userLogin = event.optString("target_user_login").takeIf { it.isNotBlank() }
        val userName = event.optString("target_user_name").takeIf { it.isNotBlank() }
        return when {
            messageId != null -> ChatEvent.Delete(ChatMessageId(messageId), messageId, receivedAt)
            userId != null || userLogin != null -> ChatEvent.ClearUser(
                userId = userId,
                eventId = eventId ?: "${userId ?: userLogin}-$receivedAt",
                receivedAtMs = receivedAt,
                userLogin = userLogin,
                userName = userName,
            )
            else -> ChatEvent.Clear(eventId, receivedAt)
        }
    }

    fun fromEventSubSettings(event: JSONObject, timestamp: String?, channelId: String): ChatEvent.SettingsUpdated =
        ChatEvent.SettingsUpdated(
            channelId = channelId,
            slowModeSeconds = event.optIntOrNull("slow_mode_wait_time_seconds"),
            followerOnlyDurationMinutes = event.optIntOrNull("follower_mode_duration_minutes"),
            subscriberOnly = event.optBoolean("subscriber_mode", false),
            emoteOnly = event.optBoolean("emote_mode", false),
            uniqueChatMode = event.optBoolean("unique_chat_mode", false),
            eventId = null,
            receivedAtMs = parseTimestamp(timestamp),
        )

    private fun eventMessage(event: JSONObject, timestamp: String?, notice: Boolean): ChatMessage {
        val messageObject = event.optJSONObject("message")
        val fragments = messageObject?.optJSONArray("fragments")
        val segments = if (fragments != null) parseFragments(fragments) else listOfNotNull(
            messageObject?.optString("text")?.takeIf { it.isNotEmpty() }?.let(ChatSegment::Text),
        )
        val rawType = event.optString("message_type").takeIf { it.isNotBlank() } ?: "text"
        val type = parseMessageType(rawType)
        val noticeType = event.optString("notice_type").takeIf { it.isNotBlank() }
        val subscription = event.optJSONObject(noticeType.orEmpty())
            ?: event.optJSONObject("sub")
            ?: event.optJSONObject("resub")
            ?: event.optJSONObject("shared_chat_sub")
            ?: event.optJSONObject("shared_chat_resub")
        val subscriptionDetails = subscriptionFromEvent(noticeType, event, subscription)
        val subscriptionPlan = event.optString("sub_tier").takeIf { it.isNotBlank() }
            ?: subscription?.optString("sub_tier")?.takeIf { it.isNotBlank() }
        val isPrimeSubscription = event.optBooleanOrNull("is_prime")
            ?: subscription?.optBooleanOrNull("is_prime")
        val fullText = segments.joinToString(separator = "") { segment ->
            when (segment) {
                is ChatSegment.Text -> segment.text
                is ChatSegment.Mention -> segment.text
                is ChatSegment.Emote -> segment.fallbackText
                is ChatSegment.Gif -> segment.fallbackText
                is ChatSegment.Cheermote -> segment.text
            }
        }
        val userName = event.optString("chatter_user_name").takeIf { it.isNotBlank() }
        val userLogin = event.optString("chatter_user_login").takeIf { it.isNotBlank() }
        val id = event.optString("message_id").takeIf { it.isNotBlank() }
            ?: "eventsub-${event.hashCode()}-${parseTimestamp(timestamp)}"
        return ChatMessage(
            id = ChatMessageId(id),
            channelId = event.optString("broadcaster_user_id").takeIf { it.isNotBlank() }.orEmpty(),
            timestampMs = parseTimestamp(timestamp),
            user = ChatUser(
                id = event.optString("chatter_user_id").takeIf { it.isNotBlank() },
                login = userLogin,
                displayName = userName,
                color = parseColor(event.optString("color").takeIf { it.isNotBlank() }),
            ),
            badges = parseBadges(event.optJSONArray("badges")),
            segments = segments,
            rawText = messageObject?.optString("text")?.takeIf { it.isNotEmpty() },
            kind = when {
                noticeType.equals("raid", ignoreCase = true) ||
                    noticeType.equals("unraid", ignoreCase = true) ||
                    noticeType.equals("shared_chat_raid", ignoreCase = true) -> ChatMessageKind.RAID
                noticeType.equals("announcement", ignoreCase = true) || noticeType.equals("shared_chat_announcement", ignoreCase = true) -> ChatMessageKind.ANNOUNCEMENT
                notice -> ChatMessageKind.NOTICE
                fullText.startsWith(ChatUtils.ACTION) -> ChatMessageKind.ACTION
                type == TwitchChatMessageType.Highlighted -> ChatMessageKind.REWARD
                else -> ChatMessageKind.CHAT
            },
            reply = parseReply(event.optJSONObject("reply")),
            source = parseSource(event),
            rewardId = event.optString("channel_points_custom_reward_id").takeIf { it.isNotBlank() },
            isFirst = type == TwitchChatMessageType.UserIntro ||
                noticeType.equals("first_message", ignoreCase = true) ||
                noticeType.equals("first_message_highlight", ignoreCase = true),
            bits = event.optJSONObject("cheer")?.optInt("bits")?.takeIf { it > 0 },
            watchStreakCount = event.optJSONObject("watch_streak")?.optInt("streak_count")?.takeIf { it > 0 },
            watchStreakPoints = event.optJSONObject("watch_streak")?.optInt("channel_points_awarded")?.takeIf { it > 0 },
            twitchType = type,
            systemText = event.optString("system_message").takeIf { it.isNotBlank() },
            noticeType = noticeType,
            subscriptionPlan = subscriptionPlan,
            subscriptionTier = subscriptionPlan,
            isPrimeSubscription = isPrimeSubscription,
            subscription = subscriptionDetails,
        )
    }

    private fun parseFragments(fragments: JSONArray): List<ChatSegment> = buildList {
        for (index in 0 until fragments.length()) {
            val fragment = fragments.optJSONObject(index) ?: continue
            val text = fragment.optString("text")
            when (fragment.optString("type")) {
                "emote" -> {
                    val emote = fragment.optJSONObject("emote")
                    val id = emote?.optString("id")?.takeIf { it.isNotBlank() }
                    if (id == null) add(ChatSegment.Text(text)) else add(
                        ChatSegment.Emote(
                            asset = nativeSpec("twitch-emote:$id", 56, 56),
                            fallbackText = text,
                            animated = emote.optJSONArray("format")?.let { formats ->
                                (0 until formats.length()).any { formats.optString(it) == "animated" }
                            } == true,
                            interaction = ChatEmoteInteraction(
                                id = id,
                                name = text,
                                url = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/3.0",
                                animated = emote.optJSONArray("format")?.let { formats ->
                                    (0 until formats.length()).any { formats.optString(it) == "animated" }
                                } == true,
                                provider = ChatAssetProvider.TWITCH,
                                scope = null,
                            ),
                        ),
                    )
                }
                "cheermote" -> {
                    val cheer = fragment.optJSONObject("cheermote")
                    val prefix = cheer?.optString("prefix").orEmpty()
                    val bits = cheer?.optInt("bits") ?: parseCheerAmount(text)
                    val tier = cheer?.optInt("tier") ?: 1
                    add(ChatSegment.Cheermote(nativeSpec("twitch-cheer:$prefix:$tier", 28, 28), text, bits, null))
                }
                "mention" -> {
                    val mention = fragment.optJSONObject("mention")
                    add(ChatSegment.Mention(
                        text = text,
                        userId = mention?.optString("user_id")?.takeIf { it.isNotBlank() },
                        login = mention?.optString("user_login")?.takeIf { it.isNotBlank() },
                    ))
                }
                "gif" -> {
                    val gif = fragment.optJSONObject("gif")
                    // EventSub calls this field gif_id. Accept the older id spelling
                    // as well so cached/test payloads remain readable.
                    val gifId = gif?.optString("gif_id")?.takeIf { it.isNotBlank() }
                        ?: gif?.optString("id")?.takeIf { it.isNotBlank() }
                    val url = gif?.optString("url")?.takeIf { it.isNotBlank() }
                    if (gifId == null || url == null) add(ChatSegment.Text(text)) else add(ChatSegment.Gif(gifId, url, text))
                }
                else -> add(ChatSegment.Text(text))
            }
        }
    }

    private fun parseBadges(array: JSONArray?): List<ChatBadgeRef> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val badge = array.optJSONObject(index) ?: continue
            val setId = badge.optString("set_id").takeIf { it.isNotBlank() } ?: continue
            val versionId = badge.optString("id").takeIf { it.isNotBlank() } ?: continue
            add(ChatBadgeRef(setId, versionId, badge.optString("info").takeIf { it.isNotBlank() }))
        }
    }

    private fun parseReply(reply: JSONObject?): ChatReply? {
        if (reply == null) return null
        val parent = reply.optString("parent_message_id").takeIf { it.isNotBlank() } ?: return null
        return ChatReply(
            parentMessageId = ChatMessageId(parent),
            parentMessageBody = reply.optString("parent_message_body").takeIf { it.isNotBlank() },
            parentUserId = reply.optString("parent_user_id").takeIf { it.isNotBlank() },
            parentUserName = reply.optString("parent_user_name").takeIf { it.isNotBlank() },
            parentUserLogin = reply.optString("parent_user_login").takeIf { it.isNotBlank() },
            threadMessageId = reply.optString("thread_message_id").takeIf { it.isNotBlank() }?.let(::ChatMessageId),
            threadUserId = reply.optString("thread_user_id").takeIf { it.isNotBlank() },
            threadUserName = reply.optString("thread_user_name").takeIf { it.isNotBlank() },
            threadUserLogin = reply.optString("thread_user_login").takeIf { it.isNotBlank() },
        )
    }

    private fun parseSource(event: JSONObject): SharedChatSource? {
        val id = event.optString("source_broadcaster_user_id").takeIf { it.isNotBlank() } ?: return null
        return SharedChatSource(
            broadcasterId = id,
            broadcasterLogin = event.optString("source_broadcaster_user_login").takeIf { it.isNotBlank() },
            broadcasterName = event.optString("source_broadcaster_user_name").takeIf { it.isNotBlank() },
            messageId = event.optString("source_message_id").takeIf { it.isNotBlank() }?.let(::ChatMessageId),
            badges = parseBadges(event.optJSONArray("source_badges")),
            sourceOnly = event.optBoolean("is_source_only", false),
        )
    }

    private fun fromLegacy(
        message: LegacyChatMessage,
        channelId: String,
        forceNotice: Boolean = false,
        gifTag: String? = null,
        subscriptionPlan: String? = null,
        subscriptionTier: String? = null,
        isPrimeSubscription: Boolean? = null,
        subscription: ChatSubscription? = null,
    ): ChatMessage {
        val raw = message.message.orEmpty()
        val segments = ircSegments(raw, message.emotes.orEmpty(), gifTag)
        val legacyReply = message.reply
        // Twitch uses source-msg-id for some notification variants (including the
        // watch-streak viewermilestone). Keep the effective notice ID so the v2
        // presentation does not silently downgrade those events to ordinary chat.
        val legacyNoticeType = (message.sourceMsgId ?: message.msgId)?.lowercase()
        return ChatMessage(
            id = ChatMessageId(message.id ?: "irc-${message.hashCode()}-${message.timestamp ?: System.currentTimeMillis()}"),
            channelId = channelId,
            timestampMs = message.timestamp ?: System.currentTimeMillis(),
            user = ChatUser(message.userId, message.userLogin, message.userName, parseColor(message.color)),
            badges = message.badges.orEmpty().mapNotNull { badge ->
                badge.setId.takeIf { it.isNotBlank() }?.let { setId ->
                    badge.version.takeIf { it.isNotBlank() }?.let { version -> ChatBadgeRef(setId, version) }
                }
            },
            segments = segments,
            kind = when {
                legacyNoticeType == "raid" ||
                    legacyNoticeType == "unraid" ||
                    legacyNoticeType == "shared_chat_raid" -> ChatMessageKind.RAID
                legacyNoticeType == "announcement" || legacyNoticeType == "shared_chat_announcement" -> ChatMessageKind.ANNOUNCEMENT
                message.isAction -> ChatMessageKind.ACTION
                forceNotice || message.type == LegacyChatMessage.NOTICE_MESSAGE -> ChatMessageKind.NOTICE
                else -> ChatMessageKind.CHAT
            },
            reply = legacyReply?.toChatReply(),
            source = message.sourceMsgId?.let { SharedChatSource(channelId, null, null, ChatMessageId(it), emptyList(), false) },
            rewardId = message.reward?.id,
            isFirst = message.isFirst,
            bits = message.bits,
            watchStreakCount = message.watchStreakCount,
            watchStreakPoints = message.watchStreakPoints,
            twitchType = message.msgId?.let(::parseMessageType) ?: TwitchChatMessageType.Text,
            systemText = message.systemMsg,
            noticeType = legacyNoticeType,
            subscriptionPlan = subscriptionPlan,
            subscriptionTier = subscriptionTier,
            isPrimeSubscription = isPrimeSubscription,
            subscription = subscription,
        )
    }

    private fun subscriptionFromIrc(tags: Map<String, String>): ChatSubscription? {
        val noticeType = (tags["source-msg-id"] ?: tags["msg-id"]).orEmpty().lowercase()
        if (!ChatSubscriptionNoticeTypes.isSubscription(noticeType)) return null
        return ChatSubscription(
            tier = tags["msg-param-sub-plan"] ?: tags["msg-param-sub-plan-name"],
            months = tags["msg-param-cumulative-months"]?.toIntOrNull(),
            streakMonths = tags["msg-param-streak-months"]?.toIntOrNull(),
            recipientName = tags["msg-param-recipient-display-name"]
                ?: tags["msg-param-recipient-user-name"],
            giftCount = tags["msg-param-mass-gift-count"]?.toIntOrNull()
                ?: tags["msg-param-sender-count"]?.toIntOrNull(),
            isCommunityGift = ChatSubscriptionNoticeTypes.isCommunityGift(noticeType),
            isAnonymous = ChatSubscriptionNoticeTypes.isAnonymous(noticeType) ||
                tags["msg-param-gifter-is-anonymous"] == "1" ||
                tags["msg-param-prior-gifter-anonymous"] == "1",
            isUpgrade = ChatSubscriptionNoticeTypes.isUpgrade(noticeType),
        )
    }

    private fun subscriptionFromEvent(
        noticeType: String?,
        event: JSONObject,
        subscription: JSONObject?,
    ): ChatSubscription? {
        val type = noticeType?.lowercase() ?: return null
        if (!ChatSubscriptionNoticeTypes.isSubscription(type)) return null
        fun stringValue(name: String): String? = event.optString(name).takeIf { it.isNotBlank() }
            ?: subscription?.optString(name)?.takeIf { it.isNotBlank() }
        fun intValue(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
            event.optIntOrNull(name) ?: subscription?.optIntOrNull(name)
        }
        fun booleanValue(name: String): Boolean? = event.optBooleanOrNull(name)
            ?: subscription?.optBooleanOrNull(name)

        return ChatSubscription(
            tier = stringValue("sub_tier") ?: stringValue("tier"),
            months = intValue("cumulative_months", "months", "duration_months"),
            streakMonths = intValue("streak_months"),
            recipientName = stringValue("recipient_user_name")
                ?: stringValue("recipient_user_login"),
            giftCount = intValue("total", "mass_gift_count", "gift_count"),
            isCommunityGift = ChatSubscriptionNoticeTypes.isCommunityGift(type) ||
                booleanValue("community_gift") == true ||
                intValue("total", "mass_gift_count") != null,
            isAnonymous = ChatSubscriptionNoticeTypes.isAnonymous(type) ||
                event.optBooleanOrNull("chatter_is_anonymous") == true ||
                booleanValue("gifter_is_anonymous") == true ||
                booleanValue("prior_gifter_is_anonymous") == true,
            isUpgrade = ChatSubscriptionNoticeTypes.isUpgrade(type),
        )
    }

    private sealed interface IrcInlineAsset {
        val start: Int
        val endInclusive: Int

        data class Emote(val value: TwitchEmote) : IrcInlineAsset {
            override val start: Int get() = value.begin
            override val endInclusive: Int get() = value.end
        }

        data class Gif(val value: IrcGif) : IrcInlineAsset {
            override val start: Int get() = value.start
            override val endInclusive: Int get() = value.endInclusive
        }
    }

    private data class IrcGif(
        val start: Int,
        val endInclusive: Int,
        val id: String,
        val url: String,
    )

    private fun ircSegments(
        text: String,
        emotes: List<TwitchEmote>,
        gifTag: String?,
    ): List<ChatSegment> {
        if (text.isEmpty()) return listOf(ChatSegment.Text(text))

        val assets = buildList {
            emotes.forEach { emote ->
                if (emote.id != null && isValidRange(emote.begin, emote.end, text.length)) {
                    add(IrcInlineAsset.Emote(emote))
                }
            }
            parseIrcGifs(gifTag).forEach { gif ->
                if (isValidRange(gif.start, gif.endInclusive, text.length)) {
                    add(IrcInlineAsset.Gif(gif))
                }
            }
        }.sortedWith(compareBy<IrcInlineAsset> { it.start }.thenBy { it.endInclusive })

        if (assets.isEmpty()) return listOf(ChatSegment.Text(text))

        val result = ArrayList<ChatSegment>()
        var cursor = 0
        assets.forEach { asset ->
            if (asset.start < cursor) return@forEach
            val start = asset.start
            val endExclusive = asset.endInclusive + 1
            if (start > cursor) result += ChatSegment.Text(text.substring(cursor, start))
            val token = text.substring(start, endExclusive)
            when (asset) {
                is IrcInlineAsset.Emote -> {
                    val emote = asset.value
                    result += ChatSegment.Emote(
                        nativeSpec("twitch-emote:${emote.id}", 56, 56),
                        token,
                        emote.isAnimated,
                        ChatEmoteInteraction(
                            id = emote.id,
                            name = token,
                            url = "https://static-cdn.jtvnw.net/emoticons/v2/${emote.id}/default/dark/3.0",
                            animated = emote.isAnimated,
                            provider = ChatAssetProvider.TWITCH,
                            scope = null,
                        ),
                    )
                }
                is IrcInlineAsset.Gif -> {
                    val gif = asset.value
                    result += ChatSegment.Gif(gif.id, gif.url, token)
                }
            }
            cursor = endExclusive
        }
        if (cursor < text.length) result += ChatSegment.Text(text.substring(cursor))
        return result
    }

    private fun isValidRange(start: Int, endInclusive: Int, textLength: Int): Boolean =
        start >= 0 && endInclusive >= start && endInclusive < textLength

    private fun parseIrcGifs(value: String?): List<IrcGif> {
        if (value.isNullOrBlank()) return emptyList()

        return value.split(',').mapNotNull { entry ->
            val parts = entry.split('|', limit = 3)
            if (parts.size != 3) return@mapNotNull null

            val range = parts[0].split('-', limit = 2)
            if (range.size != 2) return@mapNotNull null

            val start = range[0].toIntOrNull() ?: return@mapNotNull null
            val endInclusive = range[1].toIntOrNull() ?: return@mapNotNull null
            val id = parts[1].takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = parts[2].takeIf(String::isNotBlank) ?: return@mapNotNull null
            if (start < 0 || endInclusive < start) return@mapNotNull null

            IrcGif(start, endInclusive, id, url)
        }
    }

    private fun LegacyReply.toChatReply(): ChatReply? {
        val parentId = threadParentId ?: return null
        return ChatReply(
        parentMessageId = ChatMessageId(parentId),
        parentMessageBody = message,
        parentUserId = null,
        parentUserName = userName,
        parentUserLogin = userLogin,
        threadMessageId = null,
        threadUserId = null,
        threadUserName = null,
        threadUserLogin = null,
        )
    }

    private fun nativeSpec(key: String, width: Int, height: Int): ChatAssetSpec =
        ChatAssetSpec(
            key = ChatAssetKey(
                if (key.startsWith("twitch-emote:")) {
                    val id = key.substringAfter("twitch-emote:")
                    "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/3.0"
                } else {
                    key
                },
            ),
            sourceWidth = width,
            sourceHeight = height,
            targetHeight = 28,
        )

    private fun parseMessageType(raw: String): TwitchChatMessageType = when (raw.lowercase()) {
        "text" -> TwitchChatMessageType.Text
        "channel_points_highlighted", "highlighted-message" -> TwitchChatMessageType.Highlighted
        "channel_points_sub_only" -> TwitchChatMessageType.SubscriberOnly
        "user_intro" -> TwitchChatMessageType.UserIntro
        "power_ups_message_effect" -> TwitchChatMessageType.MessageEffect
        "power_ups_gigantified_emote" -> TwitchChatMessageType.GigantifiedEmote
        else -> TwitchChatMessageType.Unknown(raw)
    }

    private fun parseColor(raw: String?): Int? {
        val value = raw?.trim()?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) } ?: return null
        return runCatching { (value.substring(1).toLong(16).toInt() or 0xFF000000.toInt()) }.getOrNull()
    }

    private fun parseCheerAmount(text: String): Int = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 0
    private fun parseTimestamp(raw: String?): Long = raw?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
        ?: System.currentTimeMillis()
    private fun timestamp(raw: String?): Long = raw?.toLongOrNull() ?: System.currentTimeMillis()
    private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else optInt(name).takeIf { it >= 0 }
    private fun JSONObject.optBooleanOrNull(name: String): Boolean? = if (isNull(name)) null else optBoolean(name)
}
