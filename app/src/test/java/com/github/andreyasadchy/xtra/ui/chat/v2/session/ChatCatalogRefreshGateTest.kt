package com.github.andreyasadchy.xtra.ui.chat.v2.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCatalogRefreshGateTest {
    @Test
    fun throttlesEachChannelIndependently() {
        var now = 0L
        val gate = ChatCatalogRefreshGate(cooldownMs = 1_000L, nowMs = { now })

        assertTrue(gate.shouldForceRefresh("channel-a"))
        assertFalse(gate.shouldForceRefresh("channel-a"))
        assertTrue(gate.shouldForceRefresh("channel-b"))

        now = 999L
        assertFalse(gate.shouldForceRefresh("channel-b"))
        now = 1_000L
        assertTrue(gate.shouldForceRefresh("channel-a"))
    }
}
