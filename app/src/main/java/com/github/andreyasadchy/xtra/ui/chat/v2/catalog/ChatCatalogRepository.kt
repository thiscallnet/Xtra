package com.github.andreyasadchy.xtra.ui.chat.v2.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

data class ChatCatalogProviderUpdate<T>(val value: T)

data class ChatCatalogLoadResult(
    val twitch: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>? = null,
    val sevenTv: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>? = null,
    val bttv: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>? = null,
    val ffz: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>? = null,
    val badges: ChatCatalogProviderUpdate<Map<String, ChatCatalogBadge>>? = null,
) {
    val hasSuccessfulProvider: Boolean
        get() = twitch != null || sevenTv != null || bttv != null || ffz != null || badges != null

    /** Sources must return an explicit empty update for a provider that successfully has no entries. */
    val hasFailedProvider: Boolean
        get() = twitch == null || sevenTv == null || bttv == null || ffz == null || badges == null
}

fun interface ChatCatalogSource { suspend fun load(): ChatCatalogLoadResult }

/** A last-good cache. A provider is absent from a result when its refresh failed. */
interface ChatCatalogCache {
    suspend fun read(): ChatCatalogSnapshot?
    suspend fun write(snapshot: ChatCatalogSnapshot)
}

/** Replaces scattered mutable provider lists with one atomically published catalog revision. */
class ChatCatalogRepository(
    private val scope: CoroutineScope,
    private val source: ChatCatalogSource,
    private val cache: ChatCatalogCache? = null,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private enum class Provider { TWITCH, SEVEN_TV, BTTV, FFZ, BADGES }

    private val _state = MutableStateFlow(
        ChatCatalogState(
            snapshot = ChatCatalogSnapshot(revision = 0),
            hydrated = cache == null,
        ),
    )

    /** Catalog data and local-cache hydration are published atomically. */
    val state: StateFlow<ChatCatalogState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var generation = 0L
    private var closed = false
    private var cacheJob: Job? = null
    private var persistenceJob: Job? = null
    private var networkProvidersObserved = emptySet<Provider>()

    init {
        cache?.let { persistence ->
            cacheJob = scope.launch {
                val cached = try {
                    persistence.read()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                synchronized(this@ChatCatalogRepository) {
                    if (!closed) {
                        val currentState = _state.value
                        val current = currentState.snapshot
                        if (cached != null) {
                            if (networkProvidersObserved.isEmpty() && current.revision == 0L) {
                                // A refresh may already be in flight while disk hydration is pending.
                                // Publish the last-good cache before that refresh completes.
                                _state.value = ChatCatalogState(cached, hydrated = true)
                            } else {
                                // Hydrate only providers that have not produced a network result yet.
                                // A partial refresh must not erase cached data for providers that are
                                // still retrying, while a successful provider must remain authoritative.
                                val merged = current.copy(
                                    twitch = if (Provider.TWITCH in networkProvidersObserved) current.twitch else cached.twitch,
                                    sevenTv = if (Provider.SEVEN_TV in networkProvidersObserved) current.sevenTv else cached.sevenTv,
                                    bttv = if (Provider.BTTV in networkProvidersObserved) current.bttv else cached.bttv,
                                    ffz = if (Provider.FFZ in networkProvidersObserved) current.ffz else cached.ffz,
                                    badges = if (Provider.BADGES in networkProvidersObserved) current.badges else cached.badges,
                                )
                                if (merged != current) {
                                    _state.value = ChatCatalogState(
                                        merged.copy(revision = current.revision + 1),
                                        hydrated = true,
                                    )
                                } else {
                                    _state.value = currentState.copy(hydrated = true)
                                }
                            }
                        } else {
                            _state.value = currentState.copy(hydrated = true)
                        }
                    }
                }
            }
        }
    }

    @Synchronized fun refresh() {
        if (closed) return
        val requestGeneration = ++generation
        refreshJob?.cancel()
        refreshJob = launchRefresh(requestGeneration, attempt = 1, delayMs = 0L)
    }

    @Synchronized fun close() {
        closed = true
        cacheJob?.cancel()
        cacheJob = null
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun launchRefresh(requestGeneration: Long, attempt: Int, delayMs: Long): Job = scope.launch {
        if (delayMs > 0) wait(delayMs)
        val result = try {
            source.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        synchronized(this@ChatCatalogRepository) {
            if (closed || requestGeneration != generation) return@synchronized
            if (result == null || !result.hasSuccessfulProvider) {
                refreshJob = launchRefresh(requestGeneration, attempt + 1, retryDelay(attempt))
                return@synchronized
            }
            networkProvidersObserved = buildSet {
                addAll(networkProvidersObserved)
                if (result.twitch != null) add(Provider.TWITCH)
                if (result.sevenTv != null) add(Provider.SEVEN_TV)
                if (result.bttv != null) add(Provider.BTTV)
                if (result.ffz != null) add(Provider.FFZ)
                if (result.badges != null) add(Provider.BADGES)
            }
            val currentState = _state.value
            val current = currentState.snapshot
            val merged = current.copy(
                twitch = result.twitch?.value ?: current.twitch,
                sevenTv = result.sevenTv?.value ?: current.sevenTv,
                bttv = result.bttv?.value ?: current.bttv,
                ffz = result.ffz?.value ?: current.ffz,
                badges = result.badges?.value ?: current.badges,
            )
            val changed = merged != current
            val next = if (changed) merged.copy(revision = current.revision + 1) else current
            if (changed) {
                _state.value = ChatCatalogState(next, hydrated = currentState.hydrated)
                enqueuePersistence(next)
            }
            refreshJob = if (result.hasFailedProvider) {
                launchRefresh(requestGeneration, attempt + 1, retryDelay(attempt))
            } else {
                null
            }
        }
    }

    /**
     * Serialize writes in publication order. Independent launches allow an older slow write to
     * finish after a newer one and leave stale metadata on disk.
     */
    private fun enqueuePersistence(next: ChatCatalogSnapshot) {
        val persistence = cache ?: return
        val previous = persistenceJob
        persistenceJob = scope.launch {
            previous?.join()
            try {
                persistence.write(next)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // A cache write failure must not undo a good in-memory revision.
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
}
