package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreview
import com.github.andreyasadchy.xtra.ui.chat.v2.preview.ChatClipPreviewRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
            val repository = ChatClipPreviewRepository(scope) {
                if (++attempts == 1) null else preview
            }
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
            gate.complete(preview)
            withTimeout(5_000) {
                while (repository.peek("slug") == null) delay(10)
            }
            assertEquals(0, calls)
        } finally {
            scope.cancel()
        }
    }
}
