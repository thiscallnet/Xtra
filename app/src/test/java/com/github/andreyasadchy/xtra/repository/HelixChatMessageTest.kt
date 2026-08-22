package com.github.andreyasadchy.xtra.repository

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelixChatMessageTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun acceptedHttp200ResponseExposesMessageId() {
        val result = parseSendChatMessageResult(
            json,
            statusCode = 200,
            body = """
                {
                  "data": [{
                    "message_id": "message-id",
                    "is_sent": true,
                    "drop_reason": null
                  }]
                }
            """.trimIndent(),
        )

        assertTrue(result.isSent)
        assertEquals("message-id", result.messageId)
        assertNull(result.dropReasonCode)
        assertNull(result.dropReasonMessage)
        assertNull(result.errorMessage)
    }

    @Test
    fun droppedHttp200ResponseDoesNotExposeAcceptance() {
        val result = parseSendChatMessageResult(
            json,
            statusCode = 200,
            body = """
                {
                  "data": [{
                    "message_id": "",
                    "is_sent": false,
                    "drop_reason": {
                      "code": "slow_mode",
                      "message": "You are sending messages too quickly."
                    }
                  }]
                }
            """.trimIndent(),
        )

        assertFalse(result.isSent)
        assertNull(result.messageId)
        assertEquals("slow_mode", result.dropReasonCode)
        assertEquals("You are sending messages too quickly.", result.dropReasonMessage)
        assertNull(result.errorMessage)
    }
}
