package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.os.SystemClock
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedHashMap

/** Bounded async cache. A request can only invalidate interested attached views. */
class ChatAssetRepository(
    private val scope: CoroutineScope,
    private val loader: ChatAssetLoader,
    private val maxEntries: Int = 512,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val repositoryJob: Job? = scope.coroutineContext[Job]
    private val states = LinkedHashMap<ChatAssetKey, ChatAssetState>(maxEntries, .75f, true)
    private val listeners = HashMap<ChatAssetKey, MutableSet<() -> Unit>>()
    private val retryJobs = HashMap<ChatAssetKey, Job>()
    private val _changes = MutableStateFlow<ChatAssetKey?>(null)
    val changes: StateFlow<ChatAssetKey?> = _changes.asStateFlow()

    @Synchronized fun peek(key: ChatAssetKey): ChatAssetState = states[key] ?: ChatAssetState.Missing

    @Synchronized internal fun cachedStateCount(): Int = states.size
    @Synchronized internal fun observerCount(key: ChatAssetKey): Int = listeners[key]?.size ?: 0

    fun observe(key: ChatAssetKey, listener: () -> Unit) {
        synchronized(this) {
            listeners.getOrPut(key) { LinkedHashSet() }.add(listener)
            if (states[key] == null) states[key] = ChatAssetState.Missing
            trimCacheLocked()
        }
        request(key)
    }

    @Synchronized fun removeObserver(key: ChatAssetKey, listener: () -> Unit) {
        listeners[key]?.remove(listener)
        if (listeners[key].isNullOrEmpty()) {
            listeners.remove(key)
            retryJobs.remove(key)?.cancel()
        }
        trimCacheLocked()
    }

    /** Catalog revisions can make a previous negative result valid again. */
    fun invalidate(keys: Set<ChatAssetKey>) {
        val interested = synchronized(this) {
            keys.forEach { key -> if (states[key] is ChatAssetState.Failed) states.remove(key) }
            trimCacheLocked()
            keys.filter { !listeners[it].isNullOrEmpty() }
        }
        interested.forEach(::request)
    }

    private fun request(key: ChatAssetKey) {
        val now = nowMs()
        val attempt = synchronized(this) {
            when (val state = states[key]) {
                is ChatAssetState.Ready, ChatAssetState.Loading -> return
                is ChatAssetState.Failed if state.nextRetryAtMs > now -> {
                    scheduleRetry(key, state.nextRetryAtMs - now)
                    return
                }
                is ChatAssetState.Failed -> state.attempts + 1
                ChatAssetState.Missing, null -> 1
            }.also { states[key] = ChatAssetState.Loading }
        }
        scope.launch {
            var completedAt: Long
            val state = try {
                val loaded = loader.load(key)
                completedAt = nowMs()
                loaded?.let(ChatAssetState::Ready)
                    ?: ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
            } catch (e: CancellationException) {
                // A loader-local timeout is retryable while the repository is alive. Only
                // cancellation of the repository's own scope should terminate the attempt.
                if (repositoryJob?.isActive == false) throw e
                completedAt = nowMs()
                ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
            } catch (e: ChatAssetLoadException) {
                completedAt = nowMs()
                if (e.statusCode == 404 || e.statusCode == 410) {
                    ChatAssetState.Failed(completedAt + 5 * 60_000L, attempt, completedAt + 5 * 60_000L)
                } else ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
            } catch (_: Throwable) {
                completedAt = nowMs()
                ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
            }
            val callbacks = synchronized(this@ChatAssetRepository) {
                states[key] = state
                retryJobs.remove(key)
                trimCacheLocked()
                listeners[key]?.toList().orEmpty()
            }
            _changes.value = key
            callbacks.forEach { it() }
            if (state is ChatAssetState.Failed) scheduleRetry(key, state.nextRetryAtMs - nowMs())
        }
    }

    private fun scheduleRetry(key: ChatAssetKey, delayMs: Long) {
        synchronized(this) {
            if (listeners[key].isNullOrEmpty() || retryJobs[key]?.isActive == true) return
            retryJobs[key] = scope.launch {
                wait(delayMs.coerceAtLeast(0L))
                request(key)
            }
        }
    }

    private fun retryDelay(attempt: Int): Long = when (attempt) {
        1 -> 1_000L
        2 -> 3_000L
        3 -> 10_000L
        4 -> 30_000L
        else -> 60_000L
    }

    private fun trimCacheLocked() {
        if (states.size <= maxEntries) return
        val iterator = states.entries.iterator()
        while (states.size > maxEntries && iterator.hasNext()) {
            val entry = iterator.next()
            if (listeners[entry.key].isNullOrEmpty() && entry.value !is ChatAssetState.Loading) {
                iterator.remove()
            }
        }
    }
}
