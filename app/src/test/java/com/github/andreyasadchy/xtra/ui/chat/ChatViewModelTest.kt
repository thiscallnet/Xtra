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

    @Test
    fun contiguousChatMutationIsAppliedIncrementally() {
        assertEquals(ChatMutationAction.APPLY_INCREMENTAL, chatMutationAction(displayedRevision = 10, mutationRevision = 11))
    }

    @Test
    fun revisionGapRequestsSnapshotRecovery() {
        assertEquals(ChatMutationAction.SYNCHRONIZE_SNAPSHOT, chatMutationAction(displayedRevision = 10, mutationRevision = 12))
        assertEquals(ChatMutationAction.SYNCHRONIZE_SNAPSHOT, chatMutationAction(displayedRevision = 10, mutationRevision = 138))
    }

    @Test
    fun mutationsCoveredBySnapshotAreIgnored() {
        (11L..20L).forEach { mutationRevision ->
            assertEquals(ChatMutationAction.IGNORE, chatMutationAction(displayedRevision = 20, mutationRevision = mutationRevision))
        }
        assertFalse(shouldSynchronizeChatSnapshot(displayedRevision = 20, snapshotRevision = 20))
        assertFalse(shouldSynchronizeChatSnapshot(displayedRevision = 21, snapshotRevision = 20))
    }
}
