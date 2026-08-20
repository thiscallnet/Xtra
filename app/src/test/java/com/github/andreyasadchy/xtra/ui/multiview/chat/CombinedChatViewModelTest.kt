package com.github.andreyasadchy.xtra.ui.multiview.chat

import android.content.ContextWrapper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedChatViewModelTest {
    @Test
    fun updateSignalKeepsOnlyOnePendingNotification() = runBlocking {
        val viewModel = CombinedChatViewModel(ContextWrapper(null)) {
            error("The test does not create chat sessions")
        }
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
}
