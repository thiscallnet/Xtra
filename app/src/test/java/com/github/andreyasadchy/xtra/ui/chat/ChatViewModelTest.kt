package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun currentStreamIdWinsOverInitialLaunchArgument() {
        assertEquals("stream-b", resolveCurrentLiveStreamId("stream-b", "stream-a"))
        assertEquals("stream-a", resolveCurrentLiveStreamId(null, "stream-a"))
    }

    @Test
    fun replayEntryDoesNotOverwriteTheSavedComposerState() {
        assertTrue(shouldCaptureReplayComposerState(ChatViewModel.ActiveChatMode.Live))
        assertFalse(
            shouldCaptureReplayComposerState(
                ChatViewModel.ActiveChatMode.VideoReplay("video", null),
            ),
        )
    }
}
