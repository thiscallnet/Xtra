package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.transport.SevenTvPresenceReporter
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.eventSubSubscriptions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchChatTransportTest {
    @Test
    fun restrictedRewardSubscriptionIsOptIn() {
        val withoutRewards = eventSubSubscriptions(
            channelId = "channel-id",
            userId = "user-id",
            enableRewardRedemptions = false,
            enableModerationActionNotices = false,
        )
        val withRewards = eventSubSubscriptions(
            channelId = "channel-id",
            userId = "user-id",
            enableRewardRedemptions = true,
            enableModerationActionNotices = false,
        )
        assertFalse(withoutRewards.any { it.type == REWARD_SUBSCRIPTION })
        assertTrue(withRewards.any { it.type == REWARD_SUBSCRIPTION })
        assertTrue(withoutRewards.any { it.type == "channel.chat.message" })
    }

    @Test
    fun sevenTvPresenceIsResolvedOnceAndThrottled() = runBlocking {
        var now = 1_000L
        var resolutions = 0
        val requests = mutableListOf<List<Any?>>()
        val reporter = SevenTvPresenceReporter(
            channelId = "channel-id",
            resolveStvUserId = {
                resolutions++
                "stv-user-id"
            },
            sendPresence = { userId, channelId, sessionId, self ->
                requests += listOf(userId, channelId, sessionId, self)
            },
            now = { now },
        )

        reporter.update("stv-session", self = true)
        reporter.update(null, self = false)
        now += 10_001L
        reporter.update(null, self = false)

        assertEquals(1, resolutions)
        assertEquals(2, requests.size)
        assertEquals(listOf("stv-user-id", "channel-id", "stv-session", true), requests[0])
        assertEquals(listOf("stv-user-id", "channel-id", null, false), requests[1])
    }

    private companion object {
        const val REWARD_SUBSCRIPTION = "channel.channel_points_custom_reward_redemption.add"
    }
}
