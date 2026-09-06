package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGiftSource
import org.json.JSONObject
import kotlin.time.Instant

internal object HappeningNowGiftParser {

    fun fromEventSub(
        event: JSONObject,
        timestamp: String?,
        now: Long = System.currentTimeMillis(),
    ): HappeningNowGift? {
        val noticeType = event.optString("notice_type")
        val gift = when {
            noticeType.equals("community_sub_gift", ignoreCase = true) ->
                event.optJSONObject("community_sub_gift")

            noticeType.equals("shared_chat_community_sub_gift", ignoreCase = true) ->
                event.optJSONObject("shared_chat_community_sub_gift")

            else -> null
        } ?: return null
        val id = gift.optString("id").takeIf { it.isNotBlank() } ?: return null
        val count = gift.optInt("total", 0).takeIf { it > 0 } ?: return null

        val userId = event.optString("chatter_user_id")
            .takeIf { it.isNotBlank() }
        val login = event.optString("chatter_user_login")
            .takeIf { it.isNotBlank() }
        val displayName = event.optString("chatter_user_name")
            .ifBlank { login.orEmpty() }
            .takeIf { it.isNotBlank() }
        val anonymous = event.optBoolean("chatter_is_anonymous", false) || displayName == null
        val gifter = displayName.takeUnless { anonymous }

        return HappeningNowGift(
            stableId = id,
            occurredAt = timestamp
                ?.let(Instant::parseOrNull)
                ?.toEpochMilliseconds()
                ?: now,
            gifterDisplayName = gifter,
            gifterUserId = userId.takeUnless { anonymous },
            gifterLogin = login.takeUnless { anonymous },
            isAnonymous = anonymous,
            count = count,
            source = ChatGiftSource.EVENTSUB,
        )
    }

    fun fromIrc(
        message: ChatUtils.IRCMessage,
        now: Long = System.currentTimeMillis(),
    ): HappeningNowGift? {
        if (!message.command.equals("USERNOTICE", ignoreCase = true)) {
            return null
        }

        val effectiveNoticeId =
            message.tags["source-msg-id"] ?: message.tags["msg-id"]

        if (!effectiveNoticeId.equals("submysterygift", ignoreCase = true)) {
            return null
        }

        val count = message.tags["msg-param-mass-gift-count"]
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: return null

        val login = message.tags["login"].orEmpty()
        val userId = message.tags["user-id"]?.takeIf { it.isNotBlank() }
        val displayName = message.tags["display-name"].orEmpty()

        val anonymous =
            login.equals("ananonymousgifter", ignoreCase = true) ||
                displayName.equals("AnAnonymousGifter", ignoreCase = true)

        val resolvedDisplayName = displayName.orEmpty().ifBlank { login }.takeIf { it.isNotBlank() }
        val isAnonymous = anonymous || resolvedDisplayName == null
        val gifter = resolvedDisplayName.takeUnless { isAnonymous }

        val timestamp = message.tags["tmi-sent-ts"]
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: now

        val id = (message.tags["source-id"] ?: message.tags["id"])
            ?.takeIf { it.isNotBlank() }
            ?: "irc-community-gift:$count:$timestamp"

        return HappeningNowGift(
            stableId = id,
            occurredAt = timestamp,
            gifterDisplayName = gifter,
            gifterUserId = userId.takeUnless { isAnonymous },
            gifterLogin = login.takeIf { it.isNotBlank() }.takeUnless { isAnonymous },
            isAnonymous = isAnonymous,
            count = count,
            source = ChatGiftSource.IRC,
        )
    }
}
