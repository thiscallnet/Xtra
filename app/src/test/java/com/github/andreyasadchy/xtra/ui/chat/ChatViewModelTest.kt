package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.repository.parseSTVEntitledEmoteSetIds
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage as V2ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReply
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec

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
    fun pickerOnlyUsesTheMatchingV2ChannelSession() {
        val active = LiveChatSessionSpec("channel-a", "StreamerA")
        assertTrue(matchesV2PickerSession(active, "channel-a", "streamera"))
        assertFalse(matchesV2PickerSession(active, "channel-b", "streamerb"))
        assertFalse(matchesV2PickerSession(null, "channel-a", "streamera"))
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

    @Test
    fun v2UserHistoryUsesIdAndIncludesReplyParents() {
        val selected = v2Message("selected", ChatUser("user-1", "alice", "Alice", null))
        val sameUser = v2Message("same", ChatUser("user-1", "different-login", "Alice", null))
        val otherUser = v2Message("other", ChatUser("user-2", "alice", "Alice", null))
        val replyToUser = v2Message(
            "reply",
            ChatUser("user-2", "bob", "Bob", null),
            ChatReply(
                parentMessageId = ChatMessageId("selected"),
                parentMessageBody = "hello",
                parentUserId = "user-1",
                parentUserName = "Alice",
                parentUserLogin = "alice",
                threadMessageId = null,
                threadUserId = null,
                threadUserName = null,
                threadUserLogin = null,
            ),
        )

        val history = listOf(selected, sameUser, otherUser, replyToUser)
        assertEquals(
            listOf("selected", "same", "reply"),
            history.filter { matchesV2MessageUser(it, selected) }.map { it.id.value },
        )
    }

    @Test
    fun v2UserHistoryFallsBackToLoginWhenSelectedIdIsMissing() {
        val selected = v2Message("selected", ChatUser(null, "Alice", "Alice", null))
        val sameLogin = v2Message("same", ChatUser("user-1", "alice", "Alice", null))
        val other = v2Message("other", ChatUser("user-2", "bob", "Bob", null))

        assertEquals(
            listOf("selected", "same"),
            listOf(selected, sameLogin, other)
                .filter { matchesV2MessageUser(it, selected) }
                .map { it.id.value },
        )
    }

    @Test
    fun personalSevenTvHydrationUsesEntitledSetIds() {
        val response = JSONObject(
            """
            {"data":{"userByConnection":{"emote_sets":[
              {"id":"personal-set"},{"id":"special-set"}
            ]}}}
            """.trimIndent(),
        ).toString()

        assertEquals(listOf("personal-set", "special-set"), parseSTVEntitledEmoteSetIds(response))
    }

    private fun v2Message(id: String, user: ChatUser, reply: ChatReply? = null) = V2ChatMessage(
        id = ChatMessageId(id),
        channelId = "channel",
        timestampMs = 1,
        user = user,
        badges = emptyList(),
        segments = emptyList(),
        kind = ChatMessageKind.CHAT,
        reply = reply,
    )
}
