package com.github.andreyasadchy.xtra.ui.chat.v2.catalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

sealed interface ScopeUpdate<out T> {
    data class Success<T>(val value: T) : ScopeUpdate<T>
    data object Failed : ScopeUpdate<Nothing>
}

/**
 * A provider may publish its global and channel scopes independently. The
 * legacy value-only form remains supported for sources that have no scope
 * distinction.
 */
data class ChatCatalogProviderUpdate<T>(
    val value: T,
    val global: ScopeUpdate<T>? = null,
    val channel: ScopeUpdate<T>? = null,
) {
    val isScoped: Boolean
        get() = global != null || channel != null

    val hasFailedScope: Boolean
        get() = global is ScopeUpdate.Failed || channel is ScopeUpdate.Failed
}

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
        get() = twitch == null || sevenTv == null || sevenTv.hasFailedScope ||
                bttv == null || bttv.hasFailedScope || ffz == null || ffz.hasFailedScope || badges == null
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
    private val networkEmoteScopesObserved = mutableMapOf<Provider, MutableSet<ChatEmoteScope>>()

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
                            if (networkProvidersObserved.isEmpty() && networkEmoteScopesObserved.isEmpty()) {
                                // A refresh may already be in flight while disk hydration is pending.
                                // Publish the last-good cache before that refresh completes.
                                _state.value = ChatCatalogState(cached, hydrated = true)
                            } else {
                                // Hydrate only providers that have not produced a network result yet.
                                // A partial refresh must not erase cached data for providers that are
                                // still retrying, while a successful provider must remain authoritative.
                                val merged = current.copy(
                                    twitch = if (Provider.TWITCH in networkProvidersObserved) current.twitch else cached.twitch,
                                    sevenTv = hydrateUnobservedScopes(current.sevenTv, cached.sevenTv, Provider.SEVEN_TV),
                                    bttv = hydrateUnobservedScopes(current.bttv, cached.bttv, Provider.BTTV),
                                    ffz = hydrateUnobservedScopes(current.ffz, cached.ffz, Provider.FFZ),
                                    badges = if (Provider.BADGES in networkProvidersObserved) current.badges else cached.badges,
                                )
                                if (merged != current) {
                                    _state.value = ChatCatalogState(
                                        merged.copy(revision = current.revision + 1),
                                        hydrated = true,
                                        refreshFailed = currentState.refreshFailed,
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
                if (!_state.value.refreshFailed) {
                    _state.value = _state.value.copy(refreshFailed = true)
                }
                refreshJob = launchRefresh(requestGeneration, attempt + 1, retryDelay(attempt))
                return@synchronized
            }
            networkProvidersObserved = buildSet {
                addAll(networkProvidersObserved)
                if (result.twitch != null) add(Provider.TWITCH)
                if (result.sevenTv?.isScoped != true && result.sevenTv != null) add(Provider.SEVEN_TV)
                if (result.bttv?.isScoped != true && result.bttv != null) add(Provider.BTTV)
                if (result.ffz?.isScoped != true && result.ffz != null) add(Provider.FFZ)
                if (result.badges != null) add(Provider.BADGES)
            }
            observeEmoteScopes(Provider.SEVEN_TV, result.sevenTv)
            observeEmoteScopes(Provider.BTTV, result.bttv)
            observeEmoteScopes(Provider.FFZ, result.ffz)
            val currentState = _state.value
            val current = currentState.snapshot
            val merged = current.copy(
                twitch = result.twitch?.value ?: current.twitch,
                sevenTv = result.sevenTv?.let { mergeEmoteScopes(current.sevenTv, it) } ?: current.sevenTv,
                bttv = result.bttv?.let { mergeEmoteScopes(current.bttv, it) } ?: current.bttv,
                ffz = result.ffz?.let { mergeEmoteScopes(current.ffz, it) } ?: current.ffz,
                badges = result.badges?.value ?: current.badges,
            )
            val changed = merged != current
            val next = if (changed) merged.copy(revision = current.revision + 1) else current
            val nextState = ChatCatalogState(
                next,
                hydrated = currentState.hydrated,
                refreshFailed = result.hasFailedProvider,
            )
            if (changed) {
                _state.value = nextState
                enqueuePersistence(next)
            } else if (currentState.refreshFailed != nextState.refreshFailed) {
                _state.value = nextState
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

    private fun mergeEmoteScopes(
        current: Map<String, ChatCatalogEmote>,
        update: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>,
    ): Map<String, ChatCatalogEmote> {
        if (update.global == null && update.channel == null) return update.value
        val merged = current.toMutableMap()
        val completeScopedRefresh =
            update.global is ScopeUpdate.Success && update.channel is ScopeUpdate.Success
        if (completeScopedRefresh) {
            merged.entries.removeAll { it.value.scope == ChatEmoteScope.LEGACY_COMBINED }
        }
        fun apply(scope: ChatEmoteScope, result: ScopeUpdate<Map<String, ChatCatalogEmote>>?) {
            when (result) {
                is ScopeUpdate.Success -> {
                    merged.entries.removeAll { it.value.scope == scope }
                    merged.putAll(result.value)
                }
                ScopeUpdate.Failed, null -> Unit
            }
        }
        apply(ChatEmoteScope.GLOBAL, update.global)
        apply(ChatEmoteScope.CHANNEL, update.channel)
        return merged
    }

    private fun observeEmoteScopes(
        provider: Provider,
        update: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>?,
    ) {
        if (update == null) return
        val observed = networkEmoteScopesObserved.getOrPut(provider) { mutableSetOf() }
        if (update.global is ScopeUpdate.Success) observed += ChatEmoteScope.GLOBAL
        if (update.channel is ScopeUpdate.Success) observed += ChatEmoteScope.CHANNEL
        if (observed.isEmpty()) networkEmoteScopesObserved.remove(provider)
    }

    private fun hydrateUnobservedScopes(
        current: Map<String, ChatCatalogEmote>,
        cached: Map<String, ChatCatalogEmote>,
        provider: Provider,
    ): Map<String, ChatCatalogEmote> {
        if (provider in networkProvidersObserved) return current
        val observed = networkEmoteScopesObserved[provider].orEmpty()
        val merged = current.toMutableMap()
        for (scope in ChatEmoteScope.entries) {
            if (scope in observed) continue
            merged.entries.removeAll { it.value.scope == scope }
            merged.putAll(cached.filterValues { it.scope == scope })
        }
        return merged
    }
}
