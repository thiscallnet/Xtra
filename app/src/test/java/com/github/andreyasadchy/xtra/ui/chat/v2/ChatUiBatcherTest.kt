package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.session.ChatUiBatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ChatUiBatcherTest {
    @Test
    fun attachmentGetsOneImmediateSnapshotAndCoalescesBurst() = runBlocking {
        val version = MutableStateFlow(0L)
        val copies = AtomicInteger()
        val snapshots = ChatUiBatcher(version, { copies.incrementAndGet() }, { it.toLong() }, frameMs = 20).flow()
        val values = mutableListOf<Int>()
        val collector = launch { snapshots.take(2).toList(values) }
        withTimeout(1_000) { while (copies.get() < 1) delay(1) }
        repeat(37) { version.value++ }
        collector.join()
        assertEquals(2, copies.get())
    }

    @Test
    fun cancellingCollectorStopsAllFutureSnapshotWork() = runBlocking {
        val version = MutableStateFlow(0L)
        val copies = AtomicInteger()
        val snapshots = ChatUiBatcher(version, { copies.incrementAndGet() }, { it.toLong() }, frameMs = 20).flow()
        snapshots.take(1).collect { }
        repeat(100) { version.value++ }
        delay(50)
        assertEquals(1, copies.get())
    }

    @Test
    fun mutationDuringSnapshotProducesAnotherSnapshot() = runBlocking {
        data class Versioned(val version: Long)
        val version = MutableStateFlow(0L)
        var currentVersion = 0L
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val snapshots = ChatUiBatcher(
            version,
            {
                val call = calls.incrementAndGet()
                if (call == 2) gate.await()
                Versioned(currentVersion)
            },
            Versioned::version,
            frameMs = 10,
        ).flow()
        val values = mutableListOf<Versioned>()
        val collector = launch { snapshots.take(2).toList(values) }
        withTimeout(1_000) { while (calls.get() < 1) delay(1) }
        currentVersion = 1
        version.value = 1
        withTimeout(1_000) { while (calls.get() < 2) delay(1) }
        currentVersion = 2
        version.value = 2
        gate.complete(Unit)
        collector.join()
        assertEquals(listOf(0L, 2L), values.map { it.version })
    }
}
