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
    fun parsesSubscriptionRevocationDetails() {
        val message = LiveNotificationEventSubProtocol.parse(
            """
            {
              "metadata": {"message_type": "revocation", "message_id": "revocation-1"},
              "payload": {
                "subscription": {
                  "id": "subscription-1",
                  "type": "stream.online",
                  "status": "authorization_revoked",
                  "condition": {"broadcaster_user_id": "42"}
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("revocation", message?.messageType)
        assertEquals("revocation-1", message?.messageId)
        assertEquals("subscription-1", message?.subscriptionId)
        assertEquals("stream.online", message?.subscriptionType)
        assertEquals("authorization_revoked", message?.subscriptionStatus)
        assertEquals("42", message?.subscriptionBroadcasterUserId)
    }

    @Test
    fun parsesOnlyStreamOnlineNotificationsForTheFastLane() {
        val message = LiveNotificationEventSubProtocol.parse(
            """
            {
              "metadata": {"message_type": "notification", "message_id": "event-1"},
              "payload": {
                "subscription": {"type": "stream.online"},
                "event": {
                  "id": "stream-1",
                  "broadcaster_user_id": "42",
                  "broadcaster_user_login": "channel_login",
                  "broadcaster_user_name": "Channel Name",
                  "started_at": "2025-01-02T03:04:05Z"
                }
              }
            }
            """.trimIndent()
        )

        assertEquals("notification", message?.messageType)
        assertEquals("stream.online", message?.subscriptionType)
        assertEquals("event-1", message?.messageId)
        assertEquals("stream-1", message?.streamOnlineEvent?.eventId)
        assertEquals("42", message?.streamOnlineEvent?.broadcasterUserId)
        assertEquals("channel_login", message?.streamOnlineEvent?.broadcasterUserLogin)
        assertEquals("Channel Name", message?.streamOnlineEvent?.broadcasterUserName)
        assertEquals("2025-01-02T03:04:05Z", message?.streamOnlineEvent?.startedAt)
    }

    @Test
    fun rejectsMalformedOrUnrecognizedMessages() {
        assertNull(LiveNotificationEventSubProtocol.parse("not json"))
        assertNull(LiveNotificationEventSubProtocol.parse("{\"payload\":{}}"))
    }
}
