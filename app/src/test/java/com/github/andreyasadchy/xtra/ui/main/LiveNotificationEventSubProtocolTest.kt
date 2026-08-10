package com.github.andreyasadchy.xtra.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveNotificationEventSubProtocolTest {

    @Test
    fun parsesWelcomeSessionAndKeepaliveTimeout() {
        val message = LiveNotificationEventSubProtocol.parse(
            """
            {
              "metadata": {"message_type": "session_welcome", "message_id": "welcome-1"},
              "payload": {"session": {"id": "session-1", "keepalive_timeout_seconds": 30}}
            }
            """.trimIndent()
        )

        assertEquals("session_welcome", message?.messageType)
        assertEquals("welcome-1", message?.messageId)
        assertEquals("session-1", message?.sessionId)
        assertEquals(30, message?.keepAliveTimeoutSeconds)
    }

    @Test
    fun parsesReconnectUrlWithoutTreatingItAsASecondSubscription() {
        val message = LiveNotificationEventSubProtocol.parse(
            """
            {
              "metadata": {"message_type": "session_reconnect"},
              "payload": {"session": {"reconnect_url": "wss://example.test/reconnect"}}
            }
            """.trimIndent()
        )

        assertEquals("session_reconnect", message?.messageType)
        assertEquals("wss://example.test/reconnect", message?.reconnectUrl)
        assertNull(message?.subscriptionType)
    }

    @Test
    fun parsesOnlyStreamOnlineNotificationsForTheFastLane() {
        val message = LiveNotificationEventSubProtocol.parse(
            """
            {
              "metadata": {"message_type": "notification", "subscription_type": "stream.online"},
              "payload": {"event": {"broadcaster_user_id": "42"}}
            }
            """.trimIndent()
        )

        assertEquals("notification", message?.messageType)
        assertEquals("stream.online", message?.subscriptionType)
    }

    @Test
    fun rejectsMalformedOrUnrecognizedMessages() {
        assertNull(LiveNotificationEventSubProtocol.parse("not json"))
        assertNull(LiveNotificationEventSubProtocol.parse("{\"payload\":{}}"))
    }
}
