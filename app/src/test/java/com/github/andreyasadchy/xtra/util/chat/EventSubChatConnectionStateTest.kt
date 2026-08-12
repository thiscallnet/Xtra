package com.github.andreyasadchy.xtra.util.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSubChatConnectionStateTest {
    @Test
    fun disconnectThenHandoffWelcomeRestoresConnectedWithoutSecondJoin() {
        val connection = EventSubChatConnectionState()
        val announcement = EventSubConnectionAnnouncementState()

        assertEquals(
            EventSubChatConnectionStatus.CONNECTED,
            connection.onNormalWelcome(started = true),
        )
        assertTrue(announcement.shouldAnnounce())
        assertEquals(
            EventSubChatConnectionStatus.RECONNECTING,
            connection.onDisconnect(started = true, autoReconnect = true),
        )
        assertEquals(
            EventSubChatConnectionStatus.CONNECTED,
            connection.onHandoffWelcome(started = true),
        )
        assertFalse(announcement.shouldAnnounce())
    }
}
