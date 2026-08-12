package com.github.andreyasadchy.xtra.util.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventSubWebSocketTest {
    @Test
    fun rawTransportConnectWaitsForWelcomeBeforeConnectedCallback() {
        val state = EventSubConnectionState()

        val event = state.onTransportConnect()
        assertEquals(EventSubConnectionEvent.AWAITING_WELCOME, event)
        assertFalse(state.shouldNotifyConnected(event))
        assertFalse(state.welcomed)
        assertTrue(state.shouldRestartForMissingWelcome())
    }

    @Test
    fun normalWelcomeMarksSessionConnected() {
        val state = EventSubConnectionState()

        val event = state.onSessionWelcome()
        assertEquals(EventSubConnectionEvent.NORMAL_WELCOME, event)
        assertTrue(state.shouldNotifyConnected(event))
        assertTrue(state.welcomed)
        assertFalse(state.shouldRestartForMissingWelcome())
    }

    @Test
    fun transportRetryRequiresAnotherWelcome() {
        val state = EventSubConnectionState()

        state.onSessionWelcome()
        assertEquals(EventSubConnectionEvent.AWAITING_WELCOME, state.onTransportConnect())
        assertFalse(state.welcomed)
        assertEquals(EventSubConnectionEvent.NORMAL_WELCOME, state.onSessionWelcome())
        assertTrue(state.welcomed)
    }

    @Test
    fun missingWelcomeAfterRetryKeepsWatchdogArmed() {
        val state = EventSubConnectionState()

        state.onSessionWelcome()
        state.onTransportConnect()

        assertTrue(state.shouldRestartForMissingWelcome())
    }

    @Test
    fun handoffWelcomeDoesNotCreateSubscriptionsOrAnnounceJoin() {
        val connection = EventSubConnectionState(EventSubConnectionRole.HANDOFF_PENDING)
        val reconnectState = EventSubReconnectState()
        val announcementState = EventSubConnectionAnnouncementState()

        connection.onTransportConnect()
        val handoffEvent = connection.onSessionWelcome()
        assertEquals(EventSubConnectionEvent.HANDOFF_WELCOME, handoffEvent)
        assertFalse(connection.shouldNotifyConnected(handoffEvent))
        assertFalse(reconnectState.shouldCreateSubscriptions(isReplacement = true))
        assertTrue(connection.promoteHandoff())
        connection.onTransportConnect()
        val normalEvent = connection.onSessionWelcome()
        assertEquals(EventSubConnectionEvent.NORMAL_WELCOME, normalEvent)
        assertTrue(connection.shouldNotifyConnected(normalEvent))
        assertTrue(reconnectState.shouldCreateSubscriptions(isReplacement = false))
        assertTrue(announcementState.shouldAnnounce())
        assertFalse(announcementState.shouldAnnounce())
    }

    @Test
    fun repeatedSuccessfulSessionsProduceOneJoinAnnouncement() {
        val connection = EventSubConnectionState()
        val announcementState = EventSubConnectionAnnouncementState()

        assertEquals(EventSubConnectionEvent.NORMAL_WELCOME, connection.onSessionWelcome())
        assertTrue(announcementState.shouldAnnounce())
        connection.onTransportConnect()
        assertEquals(EventSubConnectionEvent.NORMAL_WELCOME, connection.onSessionWelcome())
        assertFalse(announcementState.shouldAnnounce())
    }

    @Test
    fun initialConnectionAnnouncementIsNotRepeatedAcrossReconnects() {
        val state = EventSubConnectionAnnouncementState()

        assertTrue(state.shouldAnnounce())
        assertFalse(state.shouldAnnounce())
        assertFalse(state.shouldAnnounce())
    }
}
