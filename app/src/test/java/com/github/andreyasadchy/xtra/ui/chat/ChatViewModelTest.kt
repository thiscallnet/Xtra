package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {

    @Test
    fun neverInitializedSessionDoesNotResume() {
        assertFalse(
            shouldResumeLiveChat(
                liveChatInitialized = false,
                chatReadJobActive = null,
                channelLogin = "channel",
                autoReconnect = true,
            ),
        )
    }

    @Test
    fun initializedInactiveSessionResumes() {
        assertTrue(
            shouldResumeLiveChat(
                liveChatInitialized = true,
                chatReadJobActive = false,
                channelLogin = "channel",
                autoReconnect = true,
            ),
        )
    }
}
