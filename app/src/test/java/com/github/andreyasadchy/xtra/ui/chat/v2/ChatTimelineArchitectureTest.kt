package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatEventProcessor
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatTimelineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTimelineArchitectureTest {
    @Test
    fun processorKeepsOrderAndDeduplicatesDelivery() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 2)
        val processor = ChatEventProcessor(scope, store)
        val sessionKey = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", 1)
        processor.activate(sessionKey)
        repeat(10_000) { index ->
            val message = message(index)
            processor.submit(sessionKey, ChatEvent.Message(message, eventId = message.id.value, receivedAtMs = index.toLong()))
            if (index == 99) processor.submit(sessionKey, ChatEvent.Message(message, eventId = message.id.value, receivedAtMs = index.toLong()))
        }
        val latest = snapshotAfter(store, "9999")
        assertEquals(listOf("9998", "9999"), latest.map { it.id.value })
        scope.cancel()
    }

    @Test
    fun semanticMessageDoesNotContainAssetReadiness() {
        val message = message(1)
        assertEquals(ChatMessageKind.CHAT, message.kind)
        assertEquals("1", message.id.value)
    }

    @Test
    fun tenThousandEventsAreLosslessWhenRetained() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 12_000)
        val processor = ChatEventProcessor(scope, store)
        val key = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", 1)
        processor.activate(key)
        repeat(10_000) { index -> processor.submit(key, ChatEvent.Message(message(index), receivedAtMs = index.toLong())) }
        val result = snapshotAfter(store, "9999")
        assertEquals((0..9_999).map(Int::toString), result.map { it.id.value })
        scope.cancel()
    }

    @Test
    fun channelGenerationReplacesOldTail() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 600)
        val processor = ChatEventProcessor(scope, store)
        val first = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("one", 1)
        val second = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("two", 2)
        processor.activate(first)
        processor.submit(first, ChatEvent.Message(message(1), receivedAtMs = 1))
        processor.activate(second)
        processor.submit(first, ChatEvent.Message(message(2), receivedAtMs = 2))
        processor.submit(second, ChatEvent.Message(message(3), receivedAtMs = 3))
        assertEquals(listOf("3"), snapshotAfter(store, "3").map { it.id.value })
        scope.cancel()
    }

    @Test
    fun realisticBoundedTailsRemainExact() = runBlocking {
        for (limit in listOf(600, 2_000)) {
            val scope = CoroutineScope(Dispatchers.Default)
            val store = ChatTimelineStore(scope, maxSize = limit)
            val processor = ChatEventProcessor(scope, store)
            val key = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", limit.toLong())
            processor.activate(key)
            repeat(10_000) { index -> processor.submit(key, ChatEvent.Message(message(index), receivedAtMs = index.toLong())) }
            assertEquals(((10_000 - limit)..9_999).map(Int::toString), snapshotAfter(store, "9999").map { it.id.value })
            scope.cancel()
        }
    }

    @Test
    fun deletionClearUserAndClearAreCanonicalOperations() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 600)
        store.apply(com.github.andreyasadchy.xtra.ui.chat.v2.session.TimelineOperation.Append(listOf(message(1, "u1"), message(2, "u2"), message(3, "u1"))))
        store.apply(com.github.andreyasadchy.xtra.ui.chat.v2.session.TimelineOperation.Delete(ChatMessageId("2"), atMs = 4))
        store.apply(com.github.andreyasadchy.xtra.ui.chat.v2.session.TimelineOperation.ClearUser("u1", atMs = 4))
        assertEquals(emptyList<String>(), store.snapshot().map { it.id.value })
        scope.cancel()
    }

    @Test
    fun deletingASeenMessageIsNotFilteredAsADuplicate() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 600)
        val processor = ChatEventProcessor(scope, store)
        val key = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", 1)
        processor.activate(key)
        processor.submit(key, ChatEvent.Message(message(1), eventId = "1", receivedAtMs = 1))
        awaitSnapshot(store) { it.lastOrNull()?.id?.value == "1" }

        processor.submit(key, ChatEvent.Delete(ChatMessageId("1"), eventId = "1", receivedAtMs = 2))
        assertEquals(emptyList<String>(), awaitSnapshot(store) { it.isEmpty() }.map { it.id.value })
        scope.cancel()
    }

    @Test
    fun repeatedClearUserEventsAreBothApplied() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 600)
        val processor = ChatEventProcessor(scope, store)
        val key = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", 1)
        processor.activate(key)
        processor.submit(key, ChatEvent.Message(message(1, "u1"), receivedAtMs = 1))
        awaitSnapshot(store) { it.lastOrNull()?.id?.value == "1" }

        processor.submit(key, ChatEvent.ClearUser("u1", eventId = "u1", receivedAtMs = 2))
        awaitSnapshot(store) { it.isEmpty() }
        processor.submit(key, ChatEvent.Message(message(3, "u1"), receivedAtMs = 3))
        awaitSnapshot(store) { it.lastOrNull()?.id?.value == "3" }

        processor.submit(key, ChatEvent.ClearUser("u1", eventId = "u1", receivedAtMs = 4))
        assertEquals(emptyList<String>(), awaitSnapshot(store) { it.isEmpty() }.map { it.id.value })
        scope.cancel()
    }

    @Test
    fun deletedMessageDoesNotReturnFromStaleReconciliation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 600)
        val processor = ChatEventProcessor(scope, store)
        val key = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", 1)
        processor.activate(key)
        val deleted = message(1)
        processor.submit(key, ChatEvent.Message(deleted, receivedAtMs = 1))
        awaitSnapshot(store) { it.lastOrNull()?.id?.value == "1" }
        processor.submit(key, ChatEvent.Delete(deleted.id, eventId = "1", receivedAtMs = 2))
        awaitSnapshot(store) { it.isEmpty() }

        processor.reconcile(key, listOf(deleted))
        assertEquals(emptyList<String>(), store.snapshot().map { it.id.value })
        scope.cancel()
    }

    @Test
    fun clearedUserMessagesDoNotReturnFromStaleReconciliation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 600)
        val processor = ChatEventProcessor(scope, store)
        val key = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", 1)
        processor.activate(key)
        val oldMessages = listOf(message(1, "u1"), message(2, "u1"))
        oldMessages.forEach { processor.submit(key, ChatEvent.Message(it, receivedAtMs = it.timestampMs)) }
        awaitSnapshot(store) { it.size == 2 }
        processor.submit(key, ChatEvent.ClearUser("u1", eventId = "u1", receivedAtMs = 3))
        awaitSnapshot(store) { it.isEmpty() }

        processor.reconcile(key, oldMessages)
        assertEquals(emptyList<String>(), store.snapshot().map { it.id.value })
        scope.cancel()
    }

    @Test
    fun messagesBeforeGlobalClearDoNotReturnFromStaleReconciliation() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 600)
        val processor = ChatEventProcessor(scope, store)
        val key = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", 1)
        processor.activate(key)
        val oldMessages = listOf(message(1), message(2))
        oldMessages.forEach { processor.submit(key, ChatEvent.Message(it, receivedAtMs = it.timestampMs)) }
        awaitSnapshot(store) { it.size == 2 }
        processor.submit(key, ChatEvent.Clear(eventId = "clear", receivedAtMs = 3))
        awaitSnapshot(store) { it.isEmpty() }

        processor.reconcile(key, oldMessages)
        assertEquals(emptyList<String>(), store.snapshot().map { it.id.value })
        scope.cancel()
    }

    @Test
    fun reconciliationCannotOverwriteAConcurrentLiveAppend() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 200)
        val processor = ChatEventProcessor(scope, store)
        val key = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("channel", 1)
        processor.activate(key)
        repeat(100) { processor.submit(key, ChatEvent.Message(message(it), receivedAtMs = it.toLong())) }
        val reconciliation = launch {
            processor.reconcile(key, (80..100).map { message(it) })
        }
        processor.submit(key, ChatEvent.Message(message(101), receivedAtMs = 101))
        reconciliation.join()
        assertEquals("101", snapshotAfter(store, "101").last().id.value)
        scope.cancel()
    }

    @Test
    fun historyForAnOldGenerationIsDiscarded() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val store = ChatTimelineStore(scope, maxSize = 200)
        val processor = ChatEventProcessor(scope, store)
        val first = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("first", 1)
        val second = com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey("second", 2)
        processor.activate(first)
        processor.activate(second)
        processor.reconcile(first, listOf(message(1, "old-history")))
        assertEquals(emptyList<String>(), store.snapshot().map { it.id.value })
        scope.cancel()
    }

    private fun message(index: Int, userId: String? = null) = ChatMessage(
        id = ChatMessageId(index.toString()),
        channelId = "channel",
        timestampMs = index.toLong(),
        user = userId?.let { com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser(it, it, it, null) },
        badges = emptyList(),
        segments = emptyList(),
        kind = ChatMessageKind.CHAT,
    )

    private suspend fun snapshotAfter(store: ChatTimelineStore, id: String): List<ChatMessage> {
        return withTimeout(5_000) {
            var result: List<ChatMessage>? = null
            while (true) {
                val snapshot = store.snapshot()
                if (snapshot.lastOrNull()?.id?.value == id) {
                    result = snapshot
                    break
                }
                delay(1)
            }
            checkNotNull(result)
        }
    }

    private suspend fun awaitSnapshot(
        store: ChatTimelineStore,
        predicate: (List<ChatMessage>) -> Boolean,
    ): List<ChatMessage> = withTimeout(5_000) {
        var result: List<ChatMessage>? = null
        while (result == null) {
            val snapshot = store.snapshot()
            if (predicate(snapshot)) result = snapshot else delay(1)
        }
        checkNotNull(result)
    }
}
