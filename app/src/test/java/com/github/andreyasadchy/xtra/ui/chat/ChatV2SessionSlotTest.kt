package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogLoadResult
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSource
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatEvent
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatSessionKey
import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatSessionFactory
import com.github.andreyasadchy.xtra.ui.chat.v2.session.LiveChatSessionSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.transport.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChatV2SessionSlotTest {
    @Test
    fun reconnectKeepsThePublishedTileSessionAndReplacementInvalidatesOldStart() = runBlocking {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = CountingTransport()
        val factory = ChatSessionFactory(
            parentScope = parent,
            transportFactory = { transport },
            catalogFactory = { _, scope -> ChatCatalogRepository(scope, EMPTY_CATALOG_SOURCE) },
        )
        val slot = ChatV2SessionSlot()
        val firstSpec = LiveChatSessionSpec("channel-a", "channel-a")
        val first = slot.getOrCreate(firstSpec, factory::createLive)
        val firstGeneration = slot.generation()
        val publishedFirst = slot.activeSession.value

        try {
            first.start()
            await { transport.startCount == 1 && transport.activeCollectors == 1 }

            // These requests are intentionally issued back-to-back. The start must not win
            // the handle mutex before the pending stop and then no-op while the session stops.
            slot.requestStop()
            slot.requestStart()
            await { transport.startCount == 2 && transport.activeCollectors == 1 }

            val reconnected = slot.getOrCreate(firstSpec, factory::createLive)

            assertSame(first, reconnected)
            assertSame(publishedFirst, slot.activeSession.value)
            assertSame(first, slot.current())
            assertFalse(slot.isCurrent(first, firstGeneration - 1))
            assertTrue(slot.isCurrent(first, firstGeneration))

            val second = slot.getOrCreate(
                LiveChatSessionSpec("channel-b", "channel-b"),
                factory::createLive,
            )
            val secondGeneration = slot.generation()
            assertFalse(slot.isCurrent(first, firstGeneration))
            assertSame(second, slot.current())
            assertSame(second.active, slot.activeSession.value)

            slot.invalidate()
            assertNull(slot.activeSession.value)
            assertFalse(slot.isCurrent(second, secondGeneration))
        } finally {
            slot.invalidate()
            parent.cancel()
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(1)
        }
    }

    private class CountingTransport : ChatTransport {
        private val starts = AtomicInteger()
        private val active = AtomicInteger()

        val startCount: Int get() = starts.get()
        val activeCollectors: Int get() = active.get()

        override fun events(session: ChatSessionKey): Flow<ChatEvent> = flow {
            starts.incrementAndGet()
            active.incrementAndGet()
            try {
                awaitCancellation()
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private companion object {
        val EMPTY_CATALOG_SOURCE = ChatCatalogSource { ChatCatalogLoadResult() }
    }
}
