package com.github.andreyasadchy.xtra.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessLocalFeedSnapshotTest {

    @Test
    fun evictionInvalidatesAnInFlightRoomBootstrap() = runBlocking {
        val snapshot = ProcessLocalFeedSnapshot<String>()
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Default) {
            snapshot.flow("feed", Int.MAX_VALUE) {
                loadStarted.complete(Unit)
                releaseLoad.await()
                listOf("stale-room-row")
            }.collect { }
        }

        withTimeout(1_000L) { loadStarted.await() }
        snapshot.evict("feed")
        assertEquals(emptyList<String>(), snapshot.current("feed"))

        releaseLoad.complete(Unit)
        collector.cancelAndJoin()
        assertNull(snapshot.current("feed"))
    }

    @Test
    fun anActiveCollectorStaysConnectedToPublishAfterEviction() = runBlocking {
        val snapshot = ProcessLocalFeedSnapshot<String>()
        val initialSeen = CompletableDeferred<Unit>()
        val evictionSeen = CompletableDeferred<Unit>()
        val freshSeen = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Default) {
            snapshot.flow("feed", Int.MAX_VALUE) {
                listOf("initial")
            }.collect { items ->
                when {
                    items == listOf("initial") -> initialSeen.complete(Unit)
                    items.isEmpty() -> evictionSeen.complete(Unit)
                    items == listOf("fresh") -> freshSeen.complete(Unit)
                }
            }
        }

        withTimeout(1_000L) { initialSeen.await() }
        snapshot.evict("feed")
        withTimeout(1_000L) { evictionSeen.await() }
        snapshot.publish("feed", listOf("fresh"))
        withTimeout(1_000L) { freshSeen.await() }
        assertEquals(listOf("fresh"), snapshot.current("feed"))

        collector.cancelAndJoin()
        assertNull(snapshot.current("feed"))
    }

    @Test
    fun pendingEvictionRemovesTheEntryWhenTheLastCollectorEnds() = runBlocking {
        val snapshot = ProcessLocalFeedSnapshot<String>()
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.Default) {
            snapshot.flow("feed", Int.MAX_VALUE) {
                loadStarted.complete(Unit)
                releaseLoad.await()
                listOf("room-row")
            }.first()
        }

        withTimeout(1_000L) { loadStarted.await() }
        snapshot.evict("feed")
        releaseLoad.complete(Unit)
        collector.join()

        assertNull(snapshot.current("feed"))
    }

    @Test
    fun aNewCollectorBootstrapsAfterTrueEviction() = runBlocking {
        val snapshot = ProcessLocalFeedSnapshot<String>()
        snapshot.publish("feed", listOf("old"))
        snapshot.evict("feed")

        var loads = 0
        val result = snapshot.flow("feed", Int.MAX_VALUE) {
            loads++
            listOf("fresh")
        }.first()

        assertEquals(listOf("fresh"), result)
        assertEquals(1, loads)
    }
}
