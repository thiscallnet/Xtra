package com.github.andreyasadchy.xtra.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventSubSubscriptionResultTest {

    @Test
    fun parsesCostFromSubscriptionDataAndLimitsFromResponseRoot() {
        val result = parseEventSubSubscriptionResult(
            json = Json.Default,
            statusCode = 202,
            rateLimitResetEpochSeconds = 1_700_000_000L,
            body = """
                {
                  "data": [{
                    "id": "subscription-1",
                    "type": "stream.online",
                    "status": "enabled",
                    "cost": 1
                  }],
                  "total": 1,
                  "total_cost": 1,
                  "max_total_cost": 10
                }
            """.trimIndent(),
        )

        assertEquals(202, result.statusCode)
        assertEquals(1, result.cost)
        assertEquals(1, result.totalCost)
        assertEquals(10, result.maxTotalCost)
        assertEquals("subscription-1", result.subscriptionId)
        assertEquals("stream.online", result.subscriptionType)
        assertEquals("enabled", result.subscriptionStatus)
        assertEquals(1_700_000_000L, result.rateLimitResetEpochSeconds)
        assertNull(result.errorMessage)
    }

    @Test
    fun parsesExistingSubscriptionIdFromConflictResponseRoot() {
        val result = parseEventSubSubscriptionResult(
            json = Json.Default,
            statusCode = 409,
            body = """
                {
                  "id": "existing-subscription",
                  "status": 409,
                  "message": "subscription already exists"
                }
            """.trimIndent(),
        )

        assertEquals(409, result.statusCode)
        assertEquals("existing-subscription", result.subscriptionId)
    }

    @Test
    fun parsesExistingSubscriptionTransportAndCondition() {
        val result = parseEventSubSubscriptionInfo(
            json = Json.Default,
            statusCode = 200,
            body = """
                {
                  "data": [{
                    "id": "existing-subscription",
                    "type": "stream.online",
                    "status": "enabled",
                    "cost": 1,
                    "condition": {
                      "broadcaster_user_id": "channel-1"
                    },
                    "transport": {
                      "method": "websocket",
                      "session_id": "session-1"
                    }
                  }],
                  "total_cost": 3,
                  "max_total_cost": 10
                }
            """.trimIndent(),
        )

        assertEquals(200, result.statusCode)
        assertEquals("existing-subscription", result.id)
        assertEquals("stream.online", result.subscriptionType)
        assertEquals("enabled", result.subscriptionStatus)
        assertEquals("channel-1", result.broadcasterUserId)
        assertEquals("websocket", result.transportMethod)
        assertEquals("session-1", result.transportSessionId)
        assertEquals(1, result.cost)
        assertEquals(3, result.totalCost)
        assertEquals(10, result.maxTotalCost)
    }
}
