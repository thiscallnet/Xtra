package com.github.andreyasadchy.xtra.ui.chat.v2.transport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchModerationEventParserTest {
    @Test
    fun timeoutRetainsActorTargetReasonAndDuration() {
        val action = requireNotNull(
            TwitchChatEventParser.fromEventSubBan(timeoutPayload(), "2025-01-02T03:04:05Z", "event-1"),
        )

        assertEquals(TwitchModeratorActionKind.TIMEOUT, action.kind)
        assertEquals("Mod_User", action.moderator)
        assertEquals("Cool_User", action.target)
        assertEquals("offensive language", action.reason)
        assertEquals(60L, action.durationSeconds)
        assertEquals("event-1", action.eventId)
    }

    @Test
    fun permanentBanHasNoTimeoutDuration() {
        val action = requireNotNull(
            TwitchChatEventParser.fromEventSubBan(
                JSONObject(timeoutPayload().toString()).put("is_permanent", true).put("ends_at", JSONObject.NULL),
                "2025-01-02T03:04:05Z",
                "event-2",
            ),
        )

        assertEquals(TwitchModeratorActionKind.BAN, action.kind)
        assertNull(action.durationSeconds)
    }

    @Test
    fun nonPermanentBanWithoutTimestampsRemainsTimeoutWithoutInventedDuration() {
        val action = requireNotNull(
            TwitchChatEventParser.fromEventSubBan(
                JSONObject(timeoutPayload().toString()).put("is_permanent", false)
                    .put("banned_at", JSONObject.NULL).put("ends_at", JSONObject.NULL),
                "2025-01-02T03:04:05Z",
                "event-3",
            ),
        )

        assertEquals(TwitchModeratorActionKind.TIMEOUT, action.kind)
        assertNull(action.durationSeconds)
    }

    @Test
    fun unbanProducesNeutralRemoveAction() {
        val action = requireNotNull(
            TwitchChatEventParser.fromEventSubUnban(
                JSONObject(
                    """
                    {
                      "user_id":"target-id",
                      "user_login":"cool_user",
                      "user_name":"Cool_User",
                      "moderator_user_id":"mod-id",
                      "moderator_user_name":"Mod_User"
                    }
                    """.trimIndent(),
                ),
                "2025-01-02T03:04:05Z",
                "event-4",
            ),
        )

        assertEquals(TwitchModeratorActionKind.REMOVE, action.kind)
        assertEquals("Mod_User", action.moderator)
        assertEquals("Cool_User", action.target)
        assertNull(action.reason)
        assertNull(action.durationSeconds)
    }

    @Test
    fun actorBanSubscriptionsAreOptionalAndUseDocumentedCondition() {
        val withoutModerationScope = eventSubSubscriptions(
            channelId = "broadcaster-id",
            userId = "moderator-id",
            enableRewardRedemptions = false,
            enableModerationActionNotices = false,
        )
        assertFalse(withoutModerationScope.any { it.type == "channel.ban" || it.type == "channel.unban" })

        val withModerationScope = eventSubSubscriptions(
            channelId = "broadcaster-id",
            userId = "moderator-id",
            enableRewardRedemptions = false,
            enableModerationActionNotices = true,
        ).filter { it.type == "channel.ban" || it.type == "channel.unban" }

        assertEquals(setOf("channel.ban", "channel.unban"), withModerationScope.map { it.type }.toSet())
        assertTrue(withModerationScope.all { it.version == "1" })
        assertTrue(withModerationScope.all { it.condition == mapOf("broadcaster_user_id" to "broadcaster-id") })
        assertNotNull(withoutModerationScope.firstOrNull { it.type == "channel.chat.message" })
    }

    private fun timeoutPayload() = JSONObject(
        """
        {
          "user_id":"target-id",
          "user_login":"cool_user",
          "user_name":"Cool_User",
          "moderator_user_id":"mod-id",
          "moderator_user_login":"mod_user",
          "moderator_user_name":"Mod_User",
          "reason":"offensive language",
          "banned_at":"2025-01-02T03:04:05Z",
          "ends_at":"2025-01-02T03:05:05Z",
          "is_permanent":false
        }
        """.trimIndent(),
    )
}
