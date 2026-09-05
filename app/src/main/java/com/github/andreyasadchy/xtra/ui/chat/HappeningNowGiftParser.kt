package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.util.chat.ChatUtils
import org.json.JSONObject
import kotlin.time.Instant

internal object HappeningNowGiftParser {

    fun fromEventSub(
        event: JSONObject,
        timestamp: String?,
        now: Long = System.currentTimeMillis(),
    ): HappeningNowGift? {
        if (!event.optString("notice_type").equals("community_sub_gift", ignoreCase = true)) {
            return null
        }

        val gift = event.optJSONObject("community_sub_gift") ?: return null
        val id = gift.optString("id").takeIf { it.isNotBlank() } ?: return null
        val count = gift.optInt("total", 0).takeIf { it > 0 } ?: return null

        val anonymous = event.optBoolean("chatter_is_anonymous", false)
        val gifter = if (anonymous) {
            "Anonymous"
        } else {
            event.optString("chatter_user_name")
                .ifBlank { event.optString("chatter_user_login") }
                .ifBlank { "Anonymous" }
        }

        return HappeningNowGift(
            stableId = id,
            occurredAt = timestamp
                ?.let(Instant::parseOrNull)
                ?.toEpochMilliseconds()
                ?: now,
            gifterDisplayName = gifter,
            count = count,
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
        val displayName = message.tags["display-name"].orEmpty()

        val anonymous =
            login.equals("ananonymousgifter", ignoreCase = true) ||
                displayName.equals("AnAnonymousGifter", ignoreCase = true)

        val gifter = if (anonymous) {
            "Anonymous"
        } else {
            displayName.ifBlank { login }.ifBlank { "Anonymous" }
        }

        val timestamp = message.tags["tmi-sent-ts"]
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: now

        val id = (message.tags["source-id"] ?: message.tags["id"])
            ?.takeIf { it.isNotBlank() }
            ?: "irc-community-gift:${gifter.lowercase()}:$count:$timestamp"

        return HappeningNowGift(
            stableId = id,
            occurredAt = timestamp,
            gifterDisplayName = gifter,
            count = count,
        )
    }
}
