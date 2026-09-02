package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWriteTransportDecisionTest {
    @Test
    fun v2ApiDisabledStillStartsWriteTransportWhenEventSubIsEnabled() {
        assertTrue(
            shouldStartLegacyChatWriteTransport(
                startMessageTransport = false,
                startWriteTransport = true,
                readOnly = false,
                isLoggedIn = true,
                useEventSubChat = true,
                hasGqlToken = true,
                hasHelixToken = true,
                useApiChatMessages = false,
            ),
        )
    }

    @Test
    fun legacyEventSubReadStillDoesNotStartTheLegacyWriteSocket() {
        assertFalse(
            shouldStartLegacyChatWriteTransport(
                startMessageTransport = true,
                startWriteTransport = true,
                readOnly = false,
                isLoggedIn = true,
                useEventSubChat = true,
                hasGqlToken = true,
                hasHelixToken = true,
                useApiChatMessages = false,
            ),
        )
    }
}
