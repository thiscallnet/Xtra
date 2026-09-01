package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageKind
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSession
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ChatSessionLifecycleTest {
    @Test
    fun generationSwitchDropsLateOldTransportEvents() = runBlocking {
        val parent = CoroutineScope(Dispatchers.Default)
        val transport = FakeTransport()
        val session = ChatSession(parent, transport, maxTimelineSize = 600)
        val first = ChatSessionKey("first", 1)
        val second = ChatSessionKey("second", 2)

        session.start(first)
        withTimeout(1_000) { while (transport.requested.size < 1) delay(1) }
        transport.send(first, message(1, "first"))
        awaitLast(session, "1")

        session.start(second)
        withTimeout(1_000) { while (transport.requested.size < 2) delay(1) }
        transport.send(first, message(2, "first-late"))
        transport.send(second, message(3, "second"))
        assertEquals(listOf("3"), awaitLast(session, "3").map { it.id.value })

        session.close()
        parent.cancel()
    }

    @Test
    fun uiAttachmentGetsCurrentStateWithoutReplayingEvents() = runBlocking {
        val parent = CoroutineScope(Dispatchers.Default)
        val transport = FakeTransport()
        val session = ChatSession(parent, transport, maxTimelineSize = 600)
        val key = ChatSessionKey("channel", 1)
        session.start(key)
        withTimeout(1_000) { while (transport.requested.isEmpty()) delay(1) }
        repeat(500) { transport.send(key, message(it, "live")) }
        awaitLast(session, "499")

        val attached = session.attachUi()
        val current = withTimeout(1_000) { attached.first() }
        assertEquals(500, current.messages.size)
        session.close()
        parent.cancel()
    }

    @Test
    fun concurrentStartsLeaveOnlyNewestTransportCollector() = runBlocking {
        val parent = CoroutineScope(Dispatchers.Default)
        val transport = FakeTransport()
        val session = ChatSession(parent, transport)
        val first = ChatSessionKey("first", 1)
        val second = ChatSessionKey("second", 2)

        val a = async { session.start(first) }
        val b = async { session.start(second) }
        a.await()
        b.await()
        withTimeout(1_000) { while (transport.activeCollectors != 1) delay(1) }
        assertEquals(second, transport.requested.last())
        session.close()
        withTimeout(1_000) { while (transport.activeCollectors != 0) delay(1) }
        parent.cancel()
    }

    @Test
    fun delayedOlderStartCannotReplaceAlreadyActiveNewerGeneration() = runBlocking {
        val parent = CoroutineScope(Dispatchers.Default)
        val transport = FakeTransport()
        val session = ChatSession(parent, transport)
        val newer = ChatSessionKey("newer", 2)
        val older = ChatSessionKey("older", 1)
        session.start(newer)
        async { session.start(older) }.await()
        assertEquals(newer, transport.requested.last())
        assertEquals(1, transport.activeCollectors)
        session.close()
        parent.cancel()
    }

    @Test
    fun closeReturnsWhenParentCancelledBeforeSessionClose() = runBlocking {
        val parentJob = kotlinx.coroutines.SupervisorJob()
        val parent = CoroutineScope(parentJob + Dispatchers.Default)
        val transport = FakeTransport()
        val session = ChatSession(parent, transport)

        session.start(ChatSessionKey("channel", 1))
        parentJob.cancel()

        withTimeout(1_000) { session.close() }
    }

    private suspend fun awaitLast(session: ChatSession, id: String): List<ChatMessage> = withTimeout(2_000) {
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

    private fun message(index: Int, channel: String) = ChatMessage(
        id = ChatMessageId(index.toString()), channelId = channel, timestampMs = index.toLong(),
        user = null, badges = emptyList(), segments = emptyList(), kind = ChatMessageKind.CHAT,
    )

    private class FakeTransport : ChatTransport {
        val requested = CopyOnWriteArrayList<ChatSessionKey>()
        private val active = AtomicInteger()
        val activeCollectors: Int get() = active.get()
        private val feeds = ConcurrentHashMap<ChatSessionKey, MutableSharedFlow<ChatEvent>>()

        override fun events(session: ChatSessionKey): Flow<ChatEvent> = flow {
            requested += session
            active.incrementAndGet()
            try {
                feeds.computeIfAbsent(session) { MutableSharedFlow(extraBufferCapacity = 64) }.collect { emit(it) }
            } finally {
                active.decrementAndGet()
            }
        }

        suspend fun send(key: ChatSessionKey, message: ChatMessage) {
            feeds.computeIfAbsent(key) { MutableSharedFlow(extraBufferCapacity = 64) }.emit(ChatEvent.Message(message))
        }
    }
}
