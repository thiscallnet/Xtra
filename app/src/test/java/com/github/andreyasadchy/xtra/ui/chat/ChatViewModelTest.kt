package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
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
    fun queuedMutationAdvancesExpectedRevisionBeforeFrameIsApplied() {
        val expected = expectedChatMutationRevision(
            displayedRevision = 100L,
            pendingRevision = 101L,
        )

        assertEquals(101L, expected)
        assertEquals(
            ChatMutationAction.APPLY_INCREMENTAL,
            chatMutationAction(expected, 102L),
        )
    }

    @Test
    fun canonicalChatHistoryStaysCappedAndOrdered() {
        val history = ArrayList<ChatMessage>()

        repeat(601) { index ->
            appendChatMessageToHistory(
                messages = history,
                message = ChatMessage(message = "message-$index"),
                messageLimit = 600,
            )
        }

        assertEquals(600, history.size)
        assertEquals("message-1", history.first().message)
        assertEquals("message-600", history.last().message)
    }

    @Test
    fun droppedIncrementalMutationsAreRecoverableFromCanonicalSnapshot() {
        val history = ArrayList<ChatMessage>()
        repeat(601) { index ->
            appendChatMessageToHistory(history, ChatMessage(message = "message-$index"), 600)
        }

        // The incremental event for the latest message may be dropped, but the canonical
        // history still contains it for the next snapshot recovery.
        val snapshot = ChatViewModel.ChatSnapshot(revision = 601L, messages = history.toList())

        assertEquals(601L, snapshot.revision)
        assertEquals(600, snapshot.messages.size)
        assertEquals("message-1", snapshot.messages.first().message)
        assertEquals("message-600", snapshot.messages.last().message)
    }

    @Test
    fun consumedLiveBoundaryDoesNotReappearAfterTrimmingPastIt() {
        val boundary = advanceLiveMessageBoundary(
            startIndex = 0,
            consumed = false,
            establishesLiveBoundary = true,
            removeCount = 1,
            resultingLastIndex = 599,
        )

        assertEquals(LiveMessageBoundaryState(startIndex = null, consumed = true), boundary)
    }

    @Test
    fun disconnectAndReconnectStatusesDoNotEstablishTheLiveBoundary() {
        val welcomeMessage = ChatMessage(
            isChatJoin = true,
            establishesLiveBoundary = false,
        )
        assertFalse(welcomeMessage.establishesLiveBoundary)

        val disconnected = advanceLiveMessageBoundary(
            startIndex = null,
            consumed = false,
            establishesLiveBoundary = false,
            removeCount = 0,
            resultingLastIndex = 0,
        )
        val welcome = advanceLiveMessageBoundary(
            startIndex = disconnected.startIndex,
            consumed = disconnected.consumed,
            establishesLiveBoundary = welcomeMessage.establishesLiveBoundary,
            removeCount = 0,
            resultingLastIndex = 1,
        )
        val firstLiveMessage = advanceLiveMessageBoundary(
            startIndex = welcome.startIndex,
            consumed = welcome.consumed,
            establishesLiveBoundary = true,
            removeCount = 0,
            resultingLastIndex = 2,
        )

        assertEquals(LiveMessageBoundaryState(startIndex = 2, consumed = false), firstLiveMessage)
    }

    @Test
    fun coalescedAppendsPreserveMessageOrder() {
        val mutations = listOf(
            ChatViewModel.ChatMutation.Append(101L, listOf(ChatMessage(message = "first")), 0),
            ChatViewModel.ChatMutation.Append(102L, listOf(ChatMessage(message = "second")), 1),
            ChatViewModel.ChatMutation.Append(103L, listOf(ChatMessage(message = "third")), 1),
        )

        val coalesced = coalesceChatAppendMutations(mutations)

        assertEquals(103L, coalesced.revision)
        assertEquals(2, coalesced.trimCount)
        assertEquals(listOf("first", "second", "third"), coalesced.messages.map { it.message })
    }

    @Test
    fun revisionGapRequestsSnapshotRecovery() {
        assertEquals(ChatMutationAction.SYNCHRONIZE_SNAPSHOT, chatMutationAction(displayedRevision = 10, mutationRevision = 12))
        assertEquals(ChatMutationAction.SYNCHRONIZE_SNAPSHOT, chatMutationAction(displayedRevision = 10, mutationRevision = 138))
        assertEquals(ChatMutationAction.SYNCHRONIZE_SNAPSHOT, chatMutationAction(displayedRevision = 100, mutationRevision = 103))
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
