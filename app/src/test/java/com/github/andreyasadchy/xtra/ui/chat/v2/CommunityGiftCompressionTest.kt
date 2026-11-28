package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatCommunityGift
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatGiftSource
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSubscription
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatEventProcessor
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatTimelineStore
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.TwitchChatEventParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CommunityGiftCompressionTest {
    @Test
    fun eventSubRecipientNoticeRetainsItsCommunityGiftId() {
        val parsed = TwitchChatEventParser.fromEventSub(
            JSONObject()
                .put("broadcaster_user_id", "channel")
                .put("chatter_user_id", "gifter-id")
                .put("chatter_user_login", "gifter")
                .put("chatter_user_name", "Gifter")
                .put("message_id", "recipient")
                .put("message_type", "notice")
                .put("notice_type", "sub_gift")
                .put("message", JSONObject().put("text", "").put("fragments", org.json.JSONArray()))
                .put("sub_gift", JSONObject()
                    .put("recipient_user_name", "Recipient")
                    .put("recipient_user_login", "recipient")
                    .put("sub_tier", "1000")
                    .put("community_gift_id", "gift-1")),
            "2026-11-28T02:00:00Z",
        )

        assertEquals("gift-1", parsed.message.subscription?.communityGiftId)
    }

    @Test
    fun compressesOnlyRecipientsCoveredByEnabledFivePlusGift() = runBlocking {
        assertEquals(0, retainedRecipientCount(enabled = true, giftCount = 5, giftId = "gift-1", recipientGiftId = "gift-1"))
        assertEquals(1, retainedRecipientCount(enabled = true, giftCount = 4, giftId = "gift-1", recipientGiftId = "gift-1"))
        assertEquals(1, retainedRecipientCount(enabled = false, giftCount = 5, giftId = "gift-1", recipientGiftId = "gift-1"))
        assertEquals(1, retainedRecipientCount(enabled = true, giftCount = 5, giftId = "gift-1", recipientGiftId = "another-gift"))
        assertEquals(1, retainedRecipientCount(enabled = true, giftCount = 5, giftId = "gift-1", recipientGiftId = "gift-1", messageTimestampMs = 32_000L))
    }

    @Test
    fun ircFallbackMatchesGifterAndConsumesOnlyGiftCount() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 10)
        val processor = ChatEventProcessor(
            scope = scope,
            store = store,
            compressCommunityGiftRecipients = { true },
        )
        val key = ChatSessionKey("channel", 1)
        try {
            processor.activate(key)
            processor.submit(key, communityGift(count = 5))
            repeat(6) { index ->
                val donor = if (index == 5) ChatUser("other-id", "other", "Other", null) else ChatUser("gifter-id", "gifter", "Gifter", null)
                processor.submit(key, ChatEvent.Message(individualGift("irc-$index", donor, communityGiftId = null)))
            }
            processor.reconcile(key, emptyList())
            assertEquals(listOf("irc-5"), store.snapshot().map { it.id.value })
        } finally {
            scope.cancel()
        }
    }

    private suspend fun retainedRecipientCount(
        enabled: Boolean,
        giftCount: Int,
        giftId: String,
        recipientGiftId: String,
        messageTimestampMs: Long = 1_100L,
    ): Int {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 10)
        val processor = ChatEventProcessor(
            scope = scope,
            store = store,
            compressCommunityGiftRecipients = { enabled },
        )
        val key = ChatSessionKey("channel", 1)
        try {
            processor.activate(key)
            processor.submit(key, communityGift(giftId, giftCount))
            processor.submit(key, ChatEvent.Message(individualGift("recipient", communityGiftId = recipientGiftId).copy(timestampMs = messageTimestampMs)))
            processor.reconcile(key, emptyList())
            return store.snapshot().size
        } finally {
            scope.cancel()
        }
    }

    private fun communityGift(id: String = "gift-1", count: Int) = ChatEvent.CommunityGift(
        ChatCommunityGift(
            stableId = id,
            occurredAt = 1_000L,
            gifterDisplayName = "Gifter",
            gifterUserId = "gifter-id",
            gifterLogin = "gifter",
            isAnonymous = false,
            count = count,
            source = ChatGiftSource.IRC,
        ),
    )

    private fun individualGift(
        id: String,
        user: ChatUser = ChatUser("gifter-id", "gifter", "Gifter", null),
        communityGiftId: String?,
    ) = ChatMessage(
        id = ChatMessageId(id),
        channelId = "channel",
        timestampMs = 1_100L,
        user = user,
        badges = emptyList(),
        segments = emptyList(),
        kind = ChatMessageKind.NOTICE,
        noticeType = "subgift",
        subscription = ChatSubscription(
            recipientName = "Recipient",
            communityGiftId = communityGiftId,
        ),
    )
}
