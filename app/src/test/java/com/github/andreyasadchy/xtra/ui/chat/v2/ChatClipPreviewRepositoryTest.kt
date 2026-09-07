package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreview
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ChatClipPreviewRepositoryTest {

    private val preview = ChatClipPreview(
        title = "title",
        broadcasterName = "broadcaster",
        creatorName = "creator",
        thumbnailUrl = null,
        gameName = "game",
        durationSeconds = 30,
        createdAt = null,
    )

    @Test
    fun failedLoadStaysRetryable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var attempts = 0
            val repository = ChatClipPreviewRepository(
                scope = scope,
                loader = { if (++attempts == 1) null else preview },
                negativeTtlMs = 0,
            )
            val first = CompletableDeferred<Unit>()
            repository.observe("slug") { first.complete(Unit) }
            withTimeout(5_000) { first.await() }
            assertNull(repository.peek("slug"))
            val second = CompletableDeferred<Unit>()
            repository.observe("slug") { second.complete(Unit) }
            withTimeout(5_000) { second.await() }
            assertEquals(preview, repository.peek("slug"))
            assertEquals(2, attempts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun removedObserverIsNotCalled() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<ChatClipPreview?>()
            val repository = ChatClipPreviewRepository(scope) { gate.await() }
            var calls = 0
            val listener: () -> Unit = { calls++ }
            repository.observe("slug", listener)
            repository.removeObserver("slug", listener)
            assertEquals(0, repository.listenerCount("slug"))
            gate.complete(preview)
            withTimeout(5_000) {
                while (repository.peek("slug") == null) delay(10)
            }
            assertEquals(0, calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun freshHitReturnsImmediatelyWithoutAnotherLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 0L
            var attempts = 0
            val repository = ChatClipPreviewRepository(
                scope = scope,
                loader = { attempts++; preview },
                successTtlMs = 100,
                negativeTtlMs = 10,
                nowMs = { now },
            )
            val first = CompletableDeferred<Unit>()
            val listener: () -> Unit = { first.complete(Unit) }
            repository.observe("slug", listener)
            withTimeout(5_000) { first.await() }
            repository.removeObserver("slug", listener)

            now = 99
            val second = CompletableDeferred<Unit>()
            repository.observe("slug") { second.complete(Unit) }
            withTimeout(5_000) { second.await() }
            assertEquals(preview, repository.peek("slug"))
            assertEquals(1, attempts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun staleHitIsReturnedBeforeOneBackgroundRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 0L
            var attempts = 0
            val refreshGate = CompletableDeferred<ChatClipPreview?>()
            val repository = ChatClipPreviewRepository(
                scope = scope,
                loader = {
                    if (++attempts == 1) preview else refreshGate.await()
                },
                successTtlMs = 100,
                negativeTtlMs = 10,
                nowMs = { now },
            )
            val first = CompletableDeferred<Unit>()
            val firstListener: () -> Unit = { first.complete(Unit) }
            repository.observe("slug", firstListener)
            withTimeout(5_000) { first.await() }
            repository.removeObserver("slug", firstListener)

            now = 101
            var callbacks = 0
            repository.observe("slug") { callbacks++ }
            assertEquals(preview, repository.peek("slug"))
            withTimeout(5_000) { while (attempts < 2) delay(1) }
            assertEquals(1, callbacks)
            assertEquals(1, repository.inFlightCount())
            refreshGate.complete(preview)
            withTimeout(5_000) { while (repository.inFlightCount() != 0) delay(1) }
            assertEquals(1, callbacks)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun changedRefreshNotifiesInterestedRowsOnly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 0L
            var attempts = 0
            val changed = preview.copy(title = "changed")
            val repository = ChatClipPreviewRepository(
                scope = scope,
                loader = { if (++attempts == 1) preview else changed },
                successTtlMs = 100,
                nowMs = { now },
            )
            val first = CompletableDeferred<Unit>()
            val firstListener: () -> Unit = { first.complete(Unit) }
            repository.observe("slug", firstListener)
            withTimeout(5_000) { first.await() }
            repository.removeObserver("slug", firstListener)
            now = 101

            var callbacks = 0
            repository.observe("slug") { callbacks++ }
            withTimeout(5_000) { while (repository.peek("slug") != changed) delay(1) }
            assertEquals(2, callbacks)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentObserversShareOneLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gate = CompletableDeferred<ChatClipPreview?>()
            var attempts = 0
            val repository = ChatClipPreviewRepository(scope) {
                attempts++
                gate.await()
            }
            var firstCalls = 0
            var secondCalls = 0
            repository.observe("slug") { firstCalls++ }
            repository.observe("slug") { secondCalls++ }
            withTimeout(5_000) { while (attempts < 1) delay(1) }
            assertEquals(1, attempts)
            assertEquals(ChatClipPreviewState.Loading, repository.peekState("slug"))
            gate.complete(preview)
            withTimeout(5_000) { while (repository.peek("slug") == null) delay(1) }
            assertEquals(1, firstCalls)
            assertEquals(1, secondCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun negativeEntriesUseShortTtlAndBecomeRetryable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var now = 0L
            var attempts = 0
            val repository = ChatClipPreviewRepository(
                scope = scope,
                loader = { if (++attempts == 1) null else preview },
                negativeTtlMs = 10,
                nowMs = { now },
            )
            val first = CompletableDeferred<Unit>()
            val firstListener: () -> Unit = { first.complete(Unit) }
            repository.observe("slug", firstListener)
            withTimeout(5_000) { first.await() }
            repository.removeObserver("slug", firstListener)
            assertEquals(ChatClipPreviewState.Ready(null), repository.peekState("slug"))

            now = 9
            repository.observe("slug") {}
            assertEquals(1, attempts)
            now = 10
            repository.observe("slug") {}
            withTimeout(5_000) { while (attempts < 2 || repository.peek("slug") == null) delay(1) }
            assertTrue(repository.peekState("slug") is ChatClipPreviewState.Ready)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lruEvictsOldestEntryAtConfiguredBound() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = ChatClipPreviewRepository(scope, { preview }, maxEntries = 2)
            for (slug in listOf("one", "two", "three")) {
                val done = CompletableDeferred<Unit>()
                val listener: () -> Unit = { done.complete(Unit) }
                repository.observe(slug, listener)
                withTimeout(5_000) { done.await() }
                repository.removeObserver(slug, listener)
            }
            assertEquals(2, repository.cacheSize())
            assertNull(repository.peek("one"))
            assertEquals(preview, repository.peek("three"))
        } finally {
            scope.cancel()
        }
    }
}
