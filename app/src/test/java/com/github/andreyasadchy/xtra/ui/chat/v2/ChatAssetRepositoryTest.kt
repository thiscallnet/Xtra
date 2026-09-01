package com.github.andreyasadchy.xtra.ui.chat.v2

import android.graphics.drawable.ColorDrawable
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetLoadException
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetLoader
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatAssetState
import com.github.andreyasadchy.xtra.ui.chat.v2.assets.ChatImageHandle
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAssetRepositoryTest {
    @Test
    fun retriesUseFailureCompletionAndAutomaticBackoff() = runBlocking {
        var now = 0L
        var attempts = 0
        val waits = mutableListOf<Long>()
        val scope = CoroutineScope(Dispatchers.Default)
        val repository = ChatAssetRepository(
            scope,
            ChatAssetLoader {
                attempts++
                now += 30_000L
                if (attempts < 3) null else ChatImageHandle { ColorDrawable(1) }
            },
            nowMs = { now },
            wait = { delayMs -> waits += delayMs; now += delayMs },
        )
        repository.observe(ChatAssetKey("retry")) {}
        withTimeout(2_000) { while (repository.peek(ChatAssetKey("retry")) !is ChatAssetState.Ready) delay(1) }
        assertEquals(3, attempts)
        assertEquals(listOf(1_000L, 3_000L), waits)
        scope.cancel()
    }

    @Test
    fun loaderLocalCancellationRetriesWhenRepositoryIsActive() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val key = ChatAssetKey("cancel")
        var attempts = 0
        var now = 0L
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            if (++attempts == 1) throw CancellationException("loader timeout")
            ChatImageHandle { ColorDrawable(1) }
        }, nowMs = { now }, wait = { now += it })
        repository.observe(key) {}
        withTimeout(2_000) { while (repository.peek(key) !is ChatAssetState.Ready) delay(1) }
        assertEquals(2, attempts)
        scope.cancel()
    }

    @Test
    fun permanentErrorsAreNegativeCachedUntilInvalidated() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val key = ChatAssetKey("missing")
        var attempts = 0
        val repository = ChatAssetRepository(scope, ChatAssetLoader {
            if (++attempts == 1) throw ChatAssetLoadException(404)
            ChatImageHandle { ColorDrawable(1) }
        }, nowMs = { 0L })
        repository.observe(key) {}
        withTimeout(2_000) { while (repository.peek(key) !is ChatAssetState.Failed) delay(1) }
        val failed = repository.peek(key) as ChatAssetState.Failed
        repository.invalidate(setOf(key))
        withTimeout(2_000) { while (repository.peek(key) !is ChatAssetState.Ready) delay(1) }
        assertEquals(2, attempts)
        assertTrue(failed.permanentUntilMs != null)
        scope.cancel()
    }

    @Test
    fun observedEntriesKeepTheirListenersWhenStateCacheOverflows() = runBlocking {
        val attempts = ConcurrentHashMap<ChatAssetKey, Int>()
        val callbacks = ConcurrentHashMap<ChatAssetKey, Int>()
        val scope = CoroutineScope(Dispatchers.Default)
        val repository = ChatAssetRepository(
            scope,
            ChatAssetLoader { key ->
                val attempt = attempts.merge(key, 1, Int::plus) ?: 1
                if (attempt == 1) null else ChatImageHandle { ColorDrawable(1) }
            },
            maxEntries = 2,
            nowMs = { 0L },
            wait = { throw CancellationException("stop retry worker") },
        )
        val keys = (0..4).map { ChatAssetKey("observed-$it") }
        keys.forEach { key -> repository.observe(key) { callbacks.merge(key, 1, Int::plus) } }
        withTimeout(2_000) { while (keys.any { repository.peek(it) !is ChatAssetState.Failed }) delay(1) }
        repository.invalidate(keys.toSet())
        withTimeout(2_000) { while (keys.any { (callbacks[it] ?: 0) < 2 }) delay(1) }
        assertTrue(keys.all { (callbacks[it] ?: 0) >= 2 })
        scope.cancel()
    }

    @Test
    fun inactiveReadyStatesAreBoundedAndDuplicateLoadsAreSuppressed() = runBlocking {
        val attempts = ConcurrentHashMap<ChatAssetKey, Int>()
        val scope = CoroutineScope(Dispatchers.Default)
        val repository = ChatAssetRepository(
            scope,
            ChatAssetLoader { key ->
                attempts.merge(key, 1, Int::plus)
                ChatImageHandle { ColorDrawable(1) }
            },
            maxEntries = 2,
            nowMs = { 0L },
        )
        val keys = (0..4).map { ChatAssetKey("ready-$it") }
        keys.forEach { key ->
            val listener: () -> Unit = {}
            repository.observe(key, listener)
            repository.observe(key, listener)
            withTimeout(1_000) { while (repository.peek(key) !is ChatAssetState.Ready) delay(1) }
            repository.removeObserver(key, listener)
        }
        assertTrue(repository.cachedStateCount() <= 2)
        assertTrue(attempts.values.all { it == 1 })
        scope.cancel()
    }
}
