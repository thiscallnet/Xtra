package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogProviderUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSource
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogLoadResult
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionManager
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ChatSessionManagerIntegrationTest {
    @Test
    fun initialHistoryReconcilesLiveMessagesWithoutDuplicates() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val historyStarted = CompletableDeferred<Unit>()
        val releaseHistory = CompletableDeferred<List<ChatMessage>>()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
            recentHistory = {
                historyStarted.complete(Unit)
                releaseHistory.await()
            },
            maxTimelineSize = 200,
        )

        val active = manager.start(LiveChatSessionSpec("channel-id", "channel-login"))
        withTimeout(1_000) { historyStarted.await() }
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }

        transport.send(active.key, message(101))
        transport.send(active.key, message(102))
        awaitLast(active.session, "102")

        releaseHistory.complete((1..101).map(::message))
        val final = withTimeout(2_000) {
            var result: List<ChatMessage>? = null
            while (result == null) {
                val current = active.session.snapshot()
                if (current.lastOrNull()?.id?.value == "102" && current.size == 102) result = current
                else delay(1)
            }
            result
        }

        assertEquals((1..102).map(Int::toString), final.map { it.id.value })
        assertEquals(1, final.count { it.id.value == "101" })

        manager.close()
        parent.cancel()
    }

    @Test
    fun failedInitialHistoryDoesNotStopLiveTransport() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
            recentHistory = { error("history unavailable") },
        )

        val active = manager.start(LiveChatSessionSpec("channel-id", "channel-login"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        transport.send(active.key, message(1))

        assertEquals(listOf("1"), awaitLast(active.session, "1").map { it.id.value })
        manager.close()
        parent.cancel()
    }

    @Test
    fun lateInitialHistoryCannotContaminateNewChannel() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val oldHistoryStarted = CompletableDeferred<Unit>()
        val releaseOldHistory = CompletableDeferred<List<ChatMessage>>()
        val oldHistoryFinished = CompletableDeferred<Unit>()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
            recentHistory = { spec ->
                if (spec.channelId == "old") {
                    oldHistoryStarted.complete(Unit)
                    try {
                        releaseOldHistory.await()
                    } finally {
                        oldHistoryFinished.complete(Unit)
                    }
                } else {
                    emptyList()
                }
            },
        )

        val old = manager.start(LiveChatSessionSpec("old", "old"))
        withTimeout(1_000) { oldHistoryStarted.await() }
        val current = manager.start(LiveChatSessionSpec("new", "new"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        transport.send(current.key, message(2))
        awaitLast(current.session, "2")

        releaseOldHistory.complete(listOf(message(1)))
        withTimeout(1_000) { oldHistoryFinished.await() }

        assertEquals(listOf("2"), current.session.snapshot().map { it.id.value })
        manager.close()
        parent.cancel()
    }

    @Test
    fun playbackOwnedSessionKeepsCurrentTailWhileUiIsDetached() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
            maxTimelineSize = 600,
        )
        val spec = LiveChatSessionSpec("channel-id", "channel-login")
        val active = manager.start(spec)

        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        repeat(500) { transport.send(active.key, message(it)) }

        val snapshot = withTimeout(2_000) {
            active.session.attachUi().first { it.messages.lastOrNull()?.id?.value == "499" }
        }
        assertEquals((0 until 500).map(Int::toString), snapshot.messages.map { it.id.value })
        assertEquals(1, transport.activeCollectors)

        manager.close()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        parent.cancel()
    }

    @Test
    fun switchingPlaybackSessionsLeavesOnlyNewestChannelInManager() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
        )

        val first = manager.start(LiveChatSessionSpec("first-id", "first"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        transport.send(first.key, message(1))
        awaitLast(first.session, "1")

        val second = manager.start(LiveChatSessionSpec("second-id", "second"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        transport.send(first.key, message(2))
        transport.send(second.key, message(3))
        assertEquals(listOf("3"), awaitLast(second.session, "3").map { it.id.value })
        assertEquals(second.key, manager.active.value?.key)

        manager.close()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        parent.cancel()
    }

    private suspend fun awaitLast(session: com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSession, id: String): List<ChatMessage> =
        withTimeout(2_000) {
            var result: List<ChatMessage>? = null
            while (true) {
                val current = session.snapshot()
                if (current.lastOrNull()?.id?.value == id) {
                    result = current
                    break
                }
                delay(1)
            }
            checkNotNull(result)
        }

    private fun message(index: Int) = ChatMessage(
        id = ChatMessageId(index.toString()),
        channelId = "channel",
        timestampMs = index.toLong(),
        user = null,
        badges = emptyList(),
        segments = emptyList(),
        kind = ChatMessageKind.CHAT,
    )

    private class FakeTransport : ChatTransport {
        private val feeds = ConcurrentHashMap<ChatSessionKey, MutableSharedFlow<ChatEvent>>()
        private val active = AtomicInteger()
        val activeCollectors: Int get() = active.get()
        val requested = CopyOnWriteArrayList<ChatSessionKey>()

        override fun events(session: ChatSessionKey): Flow<ChatEvent> = flow {
            requested += session
            active.incrementAndGet()
            try {
                feeds.computeIfAbsent(session) { MutableSharedFlow(extraBufferCapacity = 1_024) }
                    .collect { emit(it) }
            } finally {
                active.decrementAndGet()
            }
        }

        suspend fun send(key: ChatSessionKey, message: ChatMessage) {
            feeds.computeIfAbsent(key) { MutableSharedFlow(extraBufferCapacity = 1_024) }
                .emit(ChatEvent.Message(message))
        }
    }

    private companion object {
        val EMPTY_CATALOG_SOURCE = ChatCatalogSource {
            ChatCatalogLoadResult(
                twitch = ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>(emptyMap()),
                sevenTv = ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>(emptyMap()),
                bttv = ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>(emptyMap()),
                ffz = ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>(emptyMap()),
                badges = ChatCatalogProviderUpdate<Map<String, ChatCatalogBadge>>(emptyMap()),
            )
        }
    }
}
