package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.os.SystemClock
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedHashMap

/** Bounded async cache. A request can only invalidate interested attached views. */
class ChatAssetRepository(
    private val scope: CoroutineScope,
    private val loader: ChatAssetLoader,
    private val maxEntries: Int = 512,
    private val maxConcurrentLoads: Int = 8,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val repositoryJob: Job? = scope.coroutineContext[Job]
    private val states = LinkedHashMap<ChatAssetKey, ChatAssetState>(maxEntries, .75f, true)
    private val listeners = HashMap<ChatAssetKey, MutableSet<() -> Unit>>()
    private val retryJobs = HashMap<ChatAssetKey, Job>()
    private val loadJobs = HashMap<ChatAssetKey, Job>()
    private val loadPermits = Semaphore(maxConcurrentLoads.coerceAtLeast(1))
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

    fun removeObserver(key: ChatAssetKey, listener: () -> Unit) {
        var loadJob: Job? = null
        synchronized(this) {
            listeners[key]?.remove(listener)
            if (listeners[key].isNullOrEmpty()) {
                listeners.remove(key)
                retryJobs.remove(key)?.cancel()
                loadJob = loadJobs.remove(key)
                if (states[key] is ChatAssetState.Loading) states.remove(key)
            }
            trimCacheLocked()
        }
        loadJob?.cancel()
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
        val loadJob = synchronized(this) {
            val attempt = when (val state = states[key]) {
                is ChatAssetState.Ready, ChatAssetState.Loading -> return
                is ChatAssetState.Failed if state.nextRetryAtMs > now -> {
                    scheduleRetry(key, state.nextRetryAtMs - now)
                    return
                }
                is ChatAssetState.Failed -> state.attempts + 1
                ChatAssetState.Missing, null -> 1
            }.also { states[key] = ChatAssetState.Loading }
            scope.launch(start = CoroutineStart.LAZY) {
                val currentJob = coroutineContext[Job]
                try {
                    var completedAt: Long
                    val state = try {
                        val loaded = loadPermits.withPermit { loader.load(key) }
                        completedAt = nowMs()
                        loaded?.let(ChatAssetState::Ready)
                            ?: ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
                    } catch (e: CancellationException) {
                        // A loader-local timeout is retryable while the repository is alive. A
                        // load canceled after its last observer disappears is discarded instead.
                        val stillObserved = synchronized(this@ChatAssetRepository) {
                            !listeners[key].isNullOrEmpty()
                        }
                        if (repositoryJob?.isActive == false) throw e
                        if (!stillObserved) {
                            synchronized(this@ChatAssetRepository) {
                                if (
                                    loadJobs[key] === currentJob &&
                                    listeners[key].isNullOrEmpty() &&
                                    states[key] is ChatAssetState.Loading
                                ) {
                                    states.remove(key)
                                }
                            }
                            return@launch
                        }
                        completedAt = nowMs()
                        ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
                    } catch (e: ChatAssetLoadException) {
                        completedAt = nowMs()
                        if (e.statusCode == 400 || e.statusCode == 404 || e.statusCode == 410) {
                            ChatAssetState.Failed(completedAt + 5 * 60_000L, attempt, completedAt + 5 * 60_000L)
                        } else ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
                    } catch (_: Throwable) {
                        completedAt = nowMs()
                        ChatAssetState.Failed(completedAt + retryDelay(attempt), attempt)
                    }
                    val callbacks = synchronized(this@ChatAssetRepository) {
                        if (loadJobs[key] !== currentJob) null else {
                            states[key] = state
                            retryJobs.remove(key)
                            trimCacheLocked()
                            listeners[key]?.toList().orEmpty()
                        }
                    } ?: return@launch
                    _changes.value = key
                    callbacks.forEach { it() }
                    if (state is ChatAssetState.Failed) scheduleRetry(key, state.nextRetryAtMs - nowMs())
                } finally {
                    synchronized(this@ChatAssetRepository) {
                        if (loadJobs[key] === currentJob) loadJobs.remove(key)
                    }
                }
            }.also { loadJobs[key] = it }
        }
        if (!loadJob.start()) {
            synchronized(this) {
                if (loadJobs[key] === loadJob) {
                    loadJobs.remove(key)
                    if (listeners[key].isNullOrEmpty() && states[key] is ChatAssetState.Loading) {
                        states.remove(key)
                    }
                }
            }
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
