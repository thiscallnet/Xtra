package com.github.andreyasadchy.xtra.ui.chat.v2.transport

import com.github.andreyasadchy.xtra.model.chat.ChatMessage as LegacyChatMessage
import com.github.andreyasadchy.xtra.model.chat.Reply as LegacyReply
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatBadgeRef
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSegment
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.SharedChatSource
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.TwitchChatMessageType
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
        "PRIVMSG", "USERNOTICE" -> ChatEvent.Message(fromLegacy(ChatUtils.parseChatMessage(message), channelId))
        "CLEARMSG" -> ChatEvent.Delete(
            messageId = ChatMessageId(message.tags["target-msg-id"] ?: return null),
            eventId = message.tags["target-msg-id"],
            receivedAtMs = timestamp(message.tags["tmi-sent-ts"]),
        )
        "CLEARCHAT" -> {
            val userId = message.tags["target-user-id"]
            if (!userId.isNullOrBlank()) {
                ChatEvent.ClearUser(userId, message.tags["target-user-id"], timestamp(message.tags["tmi-sent-ts"]))
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

    fun fromEventSubClear(event: JSONObject, timestamp: String?): ChatEvent {
        val receivedAt = parseTimestamp(timestamp)
        val messageId = event.optString("message_id").takeIf { it.isNotBlank() }
        val userId = event.optString("target_user_id").takeIf { it.isNotBlank() }
        return when {
            messageId != null -> ChatEvent.Delete(ChatMessageId(messageId), messageId, receivedAt)
            userId != null -> ChatEvent.ClearUser(userId, userId, receivedAt)
            else -> ChatEvent.Clear(null, receivedAt)
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
            kind = when {
                notice -> ChatMessageKind.NOTICE
                fullText.startsWith(ChatUtils.ACTION) -> ChatMessageKind.ACTION
                type == TwitchChatMessageType.Highlighted -> ChatMessageKind.REWARD
                else -> ChatMessageKind.CHAT
            },
            reply = parseReply(event.optJSONObject("reply")),
            source = parseSource(event),
            rewardId = event.optString("channel_points_custom_reward_id").takeIf { it.isNotBlank() },
            bits = event.optJSONObject("cheer")?.optInt("bits")?.takeIf { it > 0 },
            twitchType = type,
            systemText = event.optString("system_message").takeIf { it.isNotBlank() },
            noticeType = event.optString("notice_type").takeIf { it.isNotBlank() },
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
                    val gifId = gif?.optString("id")?.takeIf { it.isNotBlank() }
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

    private fun fromLegacy(message: LegacyChatMessage, channelId: String): ChatMessage {
        val raw = message.message.orEmpty()
        val segments = legacySegments(raw, message.emotes.orEmpty())
        val legacyReply = message.reply
        return ChatMessage(
            id = ChatMessageId(message.id ?: "irc-${message.hashCode()}-${message.timestamp ?: System.currentTimeMillis()}"),
            channelId = channelId,
            timestampMs = message.timestamp ?: System.currentTimeMillis(),
            user = ChatUser(message.userId, message.userLogin, message.userName, parseColor(message.color)),
            badges = message.badges.orEmpty().map { ChatBadgeRef(it.setId, it.version) },
            segments = segments,
            kind = when {
                message.isAction -> ChatMessageKind.ACTION
                message.type == LegacyChatMessage.NOTICE_MESSAGE -> ChatMessageKind.NOTICE
                else -> ChatMessageKind.CHAT
            },
            reply = legacyReply?.toChatReply(),
            source = message.sourceMsgId?.let { SharedChatSource(channelId, null, null, ChatMessageId(it), emptyList(), false) },
            rewardId = message.reward?.id,
            bits = message.bits,
            twitchType = message.msgId?.let(::parseMessageType) ?: TwitchChatMessageType.Text,
            systemText = message.systemMsg,
            noticeType = message.msgId,
        )
    }

    private fun legacySegments(text: String, emotes: List<TwitchEmote>): List<ChatSegment> {
        if (text.isEmpty() || emotes.isEmpty()) return listOf(ChatSegment.Text(text))
        val byStart = emotes.filter { it.id != null && it.begin >= 0 && it.end >= it.begin }
            .sortedBy { it.begin }
        val result = ArrayList<ChatSegment>()
        var cursor = 0
        byStart.forEach { emote ->
            val start = emote.begin.coerceIn(cursor, text.length)
            val endExclusive = (emote.end + 1).coerceIn(start, text.length)
            if (start > cursor) result += ChatSegment.Text(text.substring(cursor, start))
            val token = text.substring(start, endExclusive)
            result += ChatSegment.Emote(nativeSpec("twitch-emote:${emote.id}", 56, 56), token, emote.isAnimated)
            cursor = endExclusive
        }
        if (cursor < text.length) result += ChatSegment.Text(text.substring(cursor))
        return result
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
        "channel_points_highlighted" -> TwitchChatMessageType.Highlighted
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
}
