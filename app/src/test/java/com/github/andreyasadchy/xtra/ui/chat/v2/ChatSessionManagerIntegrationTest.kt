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
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatModerationDisplayMode
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatUser
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatCatalogRefreshGate
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionManager
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionFactory
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatTimelineDelta
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import com.github.andreyasadchy.xtra.ui.chat.v2.session.VersionedTimelineSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ChatSessionManagerIntegrationTest {
    @Test
    fun newChatSessionRefreshesCatalogOnlyAfterTheChannelCooldown() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val refreshModes = CopyOnWriteArrayList<Boolean>()
        val source = object : ChatCatalogSource {
            override suspend fun load(): ChatCatalogLoadResult = EMPTY_CATALOG_SOURCE.load()

            override suspend fun load(force: Boolean): ChatCatalogLoadResult {
                refreshModes += force
                return EMPTY_CATALOG_SOURCE.load()
            }
        }
        var now = 0L
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { FakeTransport() },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, source) },
            automaticCatalogRefreshGate = ChatCatalogRefreshGate(
                cooldownMs = 1_000L,
                nowMs = { now },
            ),
        )
        val spec = LiveChatSessionSpec("channel-id", "channel-login")

        manager.start(spec)
        withTimeout(1_000) { while (refreshModes.size < 1) delay(1) }
        assertEquals(listOf(true), refreshModes.toList())

        manager.stop()
        manager.start(spec)
        withTimeout(1_000) { while (refreshModes.size < 2) delay(1) }
        assertEquals(listOf(true, false), refreshModes.toList())

        now = 1_000L
        manager.stop()
        manager.start(spec)
        withTimeout(1_000) { while (refreshModes.size < 3) delay(1) }
        assertEquals(listOf(true, false, true), refreshModes.toList())

        manager.close()
        parent.cancel()
    }

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

        val canonical = withTimeout(2_000) {
            var result: List<ChatMessage>? = null
            while (result == null) {
                val current = active.session.snapshot()
                if (current.lastOrNull()?.id?.value == "499") result = current
                else delay(1)
            }
            checkNotNull(result)
        }
        assertEquals((0 until 500).map(Int::toString), canonical.map { it.id.value })

        // There is deliberately no UI collector while the transport fills the canonical tail.
        assertEquals(1, transport.activeCollectors)
        val publication = withTimeout(2_000) { active.session.attachUi().first() }
        assertEquals(null, publication.delta)
        assertEquals((0 until 500).map(Int::toString), publication.messages.map { it.id.value })

        manager.close()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        parent.cancel()
    }

    @Test
    fun uiCollectorReconstructsBacklogFromAppendDeltas() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = MessageDeliveryGate(blockAfterMessages = 1)
        val transport = FakeTransport(gate)
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
            maxTimelineSize = 600,
        )
        val active = manager.start(LiveChatSessionSpec("channel-id", "channel-login"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }

        val sender = launch {
            repeat(500) { transport.send(active.key, message(it)) }
        }
        withTimeout(1_000) { gate.blocked.await() }
        withTimeout(1_000) { sender.join() }

        val local = ArrayDeque<ChatMessage>()
        val initial = CompletableDeferred<VersionedTimelineSnapshot>()
        val complete = CompletableDeferred<Unit>()
        val appendPublications = AtomicInteger()
        val collector = launch {
            active.session.attachUi().collect { publication ->
                when (val delta = publication.delta) {
                    null -> {
                        local.clear()
                        local.addAll(publication.messages)
                        initial.complete(publication)
                    }
                    ChatTimelineDelta.Full -> {
                        local.clear()
                        local.addAll(publication.messages)
                    }
                    is ChatTimelineDelta.Append -> {
                        assertTrue(publication.messages.isEmpty())
                        repeat(delta.evictedCount) { local.removeFirst() }
                        local.addAll(delta.messages)
                        assertEquals(delta.resultingSize, local.size)
                        appendPublications.incrementAndGet()
                    }
                }
                if (local.map { it.id.value } == (0 until 500).map(Int::toString)) {
                    complete.complete(Unit)
                }
            }
        }

        val initialPublication = withTimeout(2_000) { initial.await() }
        assertEquals(null, initialPublication.delta)
        gate.release.complete(Unit)
        withTimeout(5_000) { complete.await() }

        assertEquals((0 until 500).map(Int::toString), local.map { it.id.value })
        assertTrue(appendPublications.get() > 0)

        collector.cancelAndJoin()
        manager.close()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        parent.cancel()
    }

    @Test
    fun uiCollectorAppliesHeadEvictionWhileCatchingUp() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
            maxTimelineSize = 3,
        )
        val active = manager.start(LiveChatSessionSpec("channel-id", "channel-login"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        repeat(3) { transport.send(active.key, message(it)) }
        awaitLast(active.session, "2")

        val local = ArrayDeque<ChatMessage>()
        val initial = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        var evicted = 0
        val collector = launch {
            active.session.attachUi().collect { publication ->
                when (val delta = publication.delta) {
                    null -> {
                        local.clear()
                        local.addAll(publication.messages)
                        initial.complete(Unit)
                    }
                    ChatTimelineDelta.Full -> {
                        local.clear()
                        local.addAll(publication.messages)
                    }
                    is ChatTimelineDelta.Append -> {
                        assertTrue(publication.messages.isEmpty())
                        repeat(delta.evictedCount) { local.removeFirst() }
                        local.addAll(delta.messages)
                        evicted += delta.evictedCount
                        assertEquals(delta.resultingSize, local.size)
                    }
                }
                if (local.map { it.id.value } == listOf("3", "4", "5")) complete.complete(Unit)
            }
        }
        withTimeout(2_000) { initial.await() }
        (3..5).forEach { transport.send(active.key, message(it)) }
        withTimeout(2_000) { complete.await() }

        assertEquals(listOf("3", "4", "5"), local.map { it.id.value })
        assertEquals(3, evicted)

        collector.cancelAndJoin()
        manager.close()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        parent.cancel()
    }

    @Test
    fun reattachingUiGetsCanonicalTailAfterDetachedMessages() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
            maxTimelineSize = 5,
        )
        val active = manager.start(LiveChatSessionSpec("channel-id", "channel-login"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }

        val first = withTimeout(2_000) { active.session.attachUi().first() }
        assertEquals(null, first.delta)
        repeat(8) { transport.send(active.key, message(it)) }
        val canonical = awaitLast(active.session, "7")
        assertEquals(listOf("3", "4", "5", "6", "7"), canonical.map { it.id.value })
        assertEquals(1, transport.activeCollectors)

        val reattached = withTimeout(2_000) { active.session.attachUi().first() }
        assertEquals(null, reattached.delta)
        assertEquals(canonical.map { it.id.value }, reattached.messages.map { it.id.value })

        manager.close()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        parent.cancel()
    }

    @Test
    fun moderationFullPublicationReplacesLocalWindowAfterAppendDeltas() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
        )
        val active = manager.start(LiveChatSessionSpec("channel-id", "channel-login"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }

        val local = ArrayDeque<ChatMessage>()
        val initial = CompletableDeferred<Unit>()
        val appended = CompletableDeferred<Unit>()
        val full = CompletableDeferred<VersionedTimelineSnapshot>()
        val collector = launch {
            active.session.attachUi().collect { publication ->
                when (val delta = publication.delta) {
                    null -> {
                        local.clear()
                        local.addAll(publication.messages)
                        initial.complete(Unit)
                    }
                    ChatTimelineDelta.Full -> {
                        local.clear()
                        local.addAll(publication.messages)
                        full.complete(publication)
                    }
                    is ChatTimelineDelta.Append -> {
                        assertTrue(publication.messages.isEmpty())
                        repeat(delta.evictedCount) { local.removeFirst() }
                        local.addAll(delta.messages)
                        assertEquals(delta.resultingSize, local.size)
                        if (local.map { it.id.value } == (0 until 5).map(Int::toString)) {
                            appended.complete(Unit)
                        }
                    }
                }
            }
        }
        withTimeout(2_000) { initial.await() }
        repeat(5) { transport.send(active.key, message(it)) }
        withTimeout(2_000) { appended.await() }

        transport.send(
            active.key,
            ChatEvent.Delete(
                messageId = ChatMessageId("2"),
                eventId = "delete-2",
                receivedAtMs = 10_000L,
                displayMode = ChatModerationDisplayMode.HIDE,
            ),
        )
        val fullPublication = withTimeout(2_000) { full.await() }
        assertEquals(listOf("0", "1", "3", "4"), fullPublication.messages.map { it.id.value })
        assertEquals(listOf("0", "1", "3", "4"), local.map { it.id.value })

        collector.cancelAndJoin()
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

    @Test
    fun factorySessionReconcilesHistoryAgainAfterTransportDisconnect() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val historyCalls = AtomicInteger()
        val factory = ChatSessionFactory(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
            recentHistory = {
                historyCalls.incrementAndGet()
                listOf(message(7))
            },
        )

        val handle = factory.createLive(LiveChatSessionSpec("channel-id", "channel-login"))
        delay(50)
        assertEquals(0, transport.activeCollectors)
        assertEquals(0, historyCalls.get())
        handle.start()
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        withTimeout(1_000) { while (historyCalls.get() < 1) delay(1) }
        transport.sendDisconnect(handle.active.key)
        withTimeout(1_000) { while (historyCalls.get() < 2) delay(1) }
        assertEquals(listOf("7"), handle.active.session.snapshot().map { it.id.value })

        handle.close()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        parent.cancel()
    }

    @Test
    fun factoryHandlesRemainDormantUntilStartedAndStopIndependently() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val factory = ChatSessionFactory(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
        )
        val first = factory.createLive(LiveChatSessionSpec("a", "a"))
        val second = factory.createLive(LiveChatSessionSpec("b", "b"))

        delay(50)
        assertEquals(0, transport.activeCollectors)
        first.start()
        second.start()
        withTimeout(1_000) { while (transport.activeCollectors != 2) delay(1) }
        second.stop()
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        first.stop()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }

        first.close()
        second.close()
        parent.cancel()
    }

    @Test
    fun factoryStartStopRaceCannotReopenTransport() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val factory = ChatSessionFactory(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
        )
        val handle = factory.createLive(LiveChatSessionSpec("race", "race"))
        repeat(10) {
            handle.start()
            handle.stop()
        }
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        handle.close()
        parent.cancel()
    }

    @Test
    fun chatMessageAndHermesRedemptionAreCorrelatedIntoOneRow() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = FakeTransport()
        val manager = ChatSessionManager(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
        )
        val active = manager.start(LiveChatSessionSpec("channel-id", "channel-login"))
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        val user = ChatUser("user", "viewer", "Viewer", null)
        val normal = message(1).copy(
            id = ChatMessageId("chat-message"),
            timestampMs = 10_000L,
            user = user,
            rawText = null,
            rewardId = "reward-1",
        )
        val hermes = normal.copy(
            id = ChatMessageId("reward-redemption"),
            rewardRedemptionId = "redemption-1",
        )
        transport.send(active.key, normal)
        transport.send(active.key, hermes)
        withTimeout(1_000) {
            while (active.session.snapshot().size < 1) delay(1)
        }
        delay(50)
        assertEquals(listOf("chat-message"), active.session.snapshot().map { it.id.value })
        manager.close()
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

    private class MessageDeliveryGate(private val blockAfterMessages: Int) {
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var deliveredMessages = 0

        suspend fun after(event: ChatEvent) {
            if (event !is ChatEvent.Message) return
            deliveredMessages++
            if (deliveredMessages == blockAfterMessages) {
                blocked.complete(Unit)
                release.await()
            }
        }
    }

    private class FakeTransport(
        private val deliveryGate: MessageDeliveryGate? = null,
    ) : ChatTransport {
        private val feeds = ConcurrentHashMap<ChatSessionKey, MutableSharedFlow<ChatEvent>>()
        val activeCollectors: Int
            get() = feeds.values.sumOf { it.subscriptionCount.value }
        val requested = CopyOnWriteArrayList<ChatSessionKey>()

        override fun events(session: ChatSessionKey): Flow<ChatEvent> = flow {
            requested += session
            feeds.computeIfAbsent(session) { MutableSharedFlow(extraBufferCapacity = 1_024) }
                .collect {
                    emit(it)
                    deliveryGate?.after(it)
                }
        }

        suspend fun send(key: ChatSessionKey, message: ChatMessage) = send(key, ChatEvent.Message(message))

        suspend fun send(key: ChatSessionKey, event: ChatEvent) {
            feeds.computeIfAbsent(key) { MutableSharedFlow(extraBufferCapacity = 1_024) }
                .emit(event)
        }

        suspend fun sendDisconnect(key: ChatSessionKey) {
            feeds.computeIfAbsent(key) { MutableSharedFlow(extraBufferCapacity = 1_024) }
                .emit(ChatEvent.TransportDisconnected("test"))
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
