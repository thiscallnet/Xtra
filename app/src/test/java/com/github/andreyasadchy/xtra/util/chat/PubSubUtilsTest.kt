package com.github.andreyasadchy.xtra.util.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
