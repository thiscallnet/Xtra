package com.github.andreyasadchy.xtra.ui.multiview.chat

import android.content.ContextWrapper
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogLoadResult
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogProviderUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSource
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogBadge
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionFactory
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedChatViewModelTest {
    @Test
    fun multiviewStartsNewChannelsAndStopsOnlyTheRemovedOrPausedSessions() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = CountingTransport()
        val factory = ChatSessionFactory(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
        )
        val viewModel = CombinedChatViewModel(
            applicationContext = ContextWrapper(null),
            createSession = factory::createLive,
            keepChatOpen = { false },
            sessionScope = parent,
        )
        val first = stream("first")
        val second = stream("second")

        // A session created while stopped is dormant until the lifecycle starts.
        viewModel.ensureStreams(listOf(first))
        delay(50)
        assertEquals(0, transport.activeCount)

        viewModel.onStart()
        awaitActive(transport, 1)

        // Adding a channel while started starts only the new independent handle.
        viewModel.ensureStreams(listOf(first, second))
        awaitActive(transport, 2)

        // Removing one channel leaves the other alive.
        viewModel.ensureStreams(listOf(first))
        awaitActive(transport, 1)

        // Stopping Multiview closes the remaining network session as well.
        viewModel.onStop()
        awaitActive(transport, 0)

        parent.cancel()
    }

    @Test
    fun updateSignalKeepsOnlyOnePendingNotification() = runBlocking {
        val viewModel = CombinedChatViewModel(
            applicationContext = ContextWrapper(null),
            createSession = { _: LiveChatSessionSpec ->
                error("The test does not create chat sessions")
            },
        )
        val received = AtomicInteger()
        val collectorReady = CompletableDeferred<Unit>()
        val firstNotification = CompletableDeferred<Unit>()
        val releaseCollector = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Default) {
            viewModel.updates
                .onSubscription { collectorReady.complete(Unit) }
                .collect {
                    received.incrementAndGet()
                    firstNotification.complete(Unit)
                    releaseCollector.await()
                }
        }

        collectorReady.await()
        viewModel.ensureStreams(emptyList())
        firstNotification.await()
        repeat(64) {
            viewModel.ensureStreams(emptyList())
        }

        releaseCollector.complete(Unit)
        withTimeout(2_000) {
            while (received.get() < 2) yield()
        }
        delay(100)
        collector.cancelAndJoin()

        assertEquals(2, received.get())
    }

    private fun stream(name: String) = Stream(
        id = "stream-$name",
        channelId = name,
        channelLogin = name,
        channelName = name,
    )

    private suspend fun awaitActive(transport: CountingTransport, expected: Int) {
        withTimeout(2_000) {
            while (transport.activeCount != expected) delay(1)
        }
    }

    private class CountingTransport : ChatTransport {
        private val feeds = mutableMapOf<ChatSessionKey, MutableSharedFlow<ChatEvent>>()
        private val active = AtomicInteger()
        val activeCount: Int get() = active.get()

        override fun events(session: ChatSessionKey): Flow<ChatEvent> = flow {
            active.incrementAndGet()
            try {
                val feed = synchronized(feeds) {
                    feeds.getOrPut(session) { MutableSharedFlow(extraBufferCapacity = 16) }
                }
                feed.collect { emit(it) }
            } finally {
                active.decrementAndGet()
            }
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
