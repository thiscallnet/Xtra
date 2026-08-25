package com.github.andreyasadchy.xtra.util.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PubSubUtilsTest {
    @Test
    fun parsesWatchStreakReasonCode() {
        val (points, channelId) = PubSubUtils.parsePointsEarned(
            JSONObject(
                """
                {
                  "data": {
                    "channel_id": "channel-100",
                    "timestamp": "2026-08-22T12:00:00Z",
                    "point_gain": {
                      "total_points": 300,
                      "reason_code": "WATCH_STREAK"
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("channel-100", channelId)
        assertEquals(300, points.pointsGained)
        assertEquals("WATCH_STREAK", points.reasonCode)
    }

    @Test
    fun parsesOrdinaryWatchReasonCode() {
        val (points, _) = PubSubUtils.parsePointsEarned(
            JSONObject(
                """
                {"data":{"point_gain":{"total_points":10,"reason_code":"WATCH"}}}
                """.trimIndent(),
            ),
        )

        assertEquals("WATCH", points.reasonCode)
    }

    @Test
    fun parsesEarnedAbsoluteBalanceAndStreakVariant() {
        val event = PubSubUtils.parseChannelPointsBalanceEvent(
            JSONObject(
                """
                {
                  "type": "points-earned",
                  "data": {
                    "channel": {"id": "channel-100"},
                    "point_gain": {
                      "points": 300,
                      "reasonCode": "WATCH_STREAK",
                      "balance": 1300,
                      "streak_count": 8
                    },
                    "created_at": "2026-08-22T12:00:00Z",
                    "message_id": "earn-1"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertNotNull(event)
        assertEquals("channel-100", event?.channelId)
        assertEquals(ChannelPointsBalanceEvent.Type.EARNED, event?.type)
        assertEquals(300, event?.delta)
        assertEquals(1300, event?.absoluteBalance)
        assertEquals("WATCH_STREAK", event?.reasonCode)
        assertEquals(8, event?.streakCount)
        assertEquals("earn-1", event?.messageId)
    }

    @Test
    fun parsesSpentNestedPayload() {
        val event = PubSubUtils.parsePointsSpent(
            JSONObject(
                """
                {
                  "type": "points-spent",
                  "data": {
                    "channel_id": "channel-100",
                    "event": {
                      "point_spend": {
                        "cost": 500,
                        "reason_code": "CUSTOM_REWARD"
                      }
                    },
                    "points": {"balance": 2500},
                    "timestamp": 1724328000000,
                    "event_id": "spend-1"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertNotNull(event)
        assertEquals("channel-100", event?.channelId)
        assertEquals(ChannelPointsBalanceEvent.Type.SPENT, event?.type)
        assertEquals(500, event?.delta)
        assertEquals(2500, event?.absoluteBalance)
        assertEquals("CUSTOM_REWARD", event?.reasonCode)
        assertEquals("spend-1", event?.messageId)
    }
}
