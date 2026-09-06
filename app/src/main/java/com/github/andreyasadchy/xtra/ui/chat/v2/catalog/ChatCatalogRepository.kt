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
    /** Only emote providers use this; each entry is keyed by a sender-owned 7TV set ID. */
    val personal: ScopeUpdate<Map<String, Map<String, ChatCatalogEmote>>>? = null,
    /** The channel's actual 7TV emote-set ID, when this is the 7TV provider update. */
    val channelSetId: String? = null,
) {
    val isScoped: Boolean
        get() = global != null || channel != null || personal != null

    val hasFailedScope: Boolean
        get() = global is ScopeUpdate.Failed || channel is ScopeUpdate.Failed || personal is ScopeUpdate.Failed
}

data class ChatCatalogLoadResult(
    val twitch: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>? = null,
    val sevenTv: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>? = null,
    val bttv: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>? = null,
    val ffz: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>? = null,
    val badges: ChatCatalogProviderUpdate<Map<String, ChatCatalogBadge>>? = null,
    val cheermotes: ChatCatalogProviderUpdate<Map<String, ChatCatalogCheermote>>? = null,
) {
    val hasSuccessfulProvider: Boolean
        get() = twitch != null || sevenTv != null || bttv != null || ffz != null || badges != null || cheermotes != null

    val hasFailedThirdPartyProvider: Boolean
        get() = sevenTv == null || sevenTv.hasFailedScope ||
                bttv == null || bttv.hasFailedScope || ffz == null || ffz.hasFailedScope

    /** Sources must return an explicit empty update for a provider that successfully has no entries. */
    val hasFailedProvider: Boolean
        get() = twitch == null || sevenTv == null || sevenTv.hasFailedScope ||
                bttv == null || bttv.hasFailedScope || ffz == null || ffz.hasFailedScope ||
                badges == null || cheermotes == null

    fun hasFailedProviderIgnoringBadges(): Boolean = twitch == null || sevenTv == null || sevenTv.hasFailedScope ||
            bttv == null || bttv.hasFailedScope || ffz == null || ffz.hasFailedScope || cheermotes == null
}

fun interface ChatCatalogSource {
    suspend fun load(): ChatCatalogLoadResult

    suspend fun load(force: Boolean): ChatCatalogLoadResult = load()

    /** Changes when provider-related settings or the signed-in emote scope changes. */
    val catalogConfigFingerprint: String
        get() = ""

    /** True when Twitch badges are published independently of the aggregate catalog request. */
    val hasIndependentBadgeProvider: Boolean
        get() = false

    /** Returns the Twitch badge result as soon as that provider finishes. */
    suspend fun loadBadges(): ChatCatalogProviderUpdate<Map<String, ChatCatalogBadge>>? = null

    suspend fun loadBadges(force: Boolean): ChatCatalogProviderUpdate<Map<String, ChatCatalogBadge>>? = loadBadges()
}

data class ChatCatalogCacheEntry(
    val snapshot: ChatCatalogSnapshot,
    val fetchedAtMs: Long,
    val badgesFetchedAtMs: Long = 0L,
    val catalogConfigFingerprint: String? = null,
    val badgeConfigFingerprint: String? = null,
)

/** A last-good cache. A provider is absent from a result when its refresh failed. */
interface ChatCatalogCache {
    suspend fun read(): ChatCatalogSnapshot?
    suspend fun readFetchedAtMs(): Long = 0L
    suspend fun readEntry(): ChatCatalogCacheEntry? = read()?.let { snapshot ->
        ChatCatalogCacheEntry(snapshot, readFetchedAtMs())
    }
    suspend fun write(snapshot: ChatCatalogSnapshot)
    suspend fun write(snapshot: ChatCatalogSnapshot, fetchedAtMs: Long) = write(snapshot)
    suspend fun write(
        snapshot: ChatCatalogSnapshot,
        fetchedAtMs: Long,
        badgesFetchedAtMs: Long,
        catalogConfigFingerprint: String?,
        badgeConfigFingerprint: String?,
    ) = write(snapshot, fetchedAtMs)
}

/** Replaces scattered mutable provider lists with one atomically published catalog revision. */
class ChatCatalogRepository(
    private val scope: CoroutineScope,
    private val source: ChatCatalogSource,
    private val cache: ChatCatalogCache? = null,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val personalEmoteSetLoader: (suspend (String) -> Map<String, ChatCatalogEmote>)? = null,
    private val cacheFreshnessMs: Long = 60 * 60 * 1000L,
) {
    private enum class Provider { TWITCH, SEVEN_TV, BTTV, FFZ, BADGES, CHEERMOTES }

    private val _state = MutableStateFlow(
        ChatCatalogState(
            snapshot = ChatCatalogSnapshot(revision = 0),
            hydrated = cache == null,
        ),
    )

    /** Catalog data and local-cache hydration are published atomically. */
    val state: StateFlow<ChatCatalogState> = _state.asStateFlow()
    private var refreshJob: Job? = null
    private var badgeRefreshJob: Job? = null
    private var badgeRefreshGeneration: Long? = null
    private var generation = 0L
    private var closed = false
    private var cacheJob: Job? = null
    private var persistenceJob: Job? = null
    private var cacheFetchedAtMs: Long? = null
    private var badgesFetchedAtMs: Long? = null
    private var cacheCatalogConfigFingerprint: String? = null
    private var cacheBadgeConfigFingerprint: String? = null
    private val personalEmoteSetJobs = mutableMapOf<String, Job>()
    private val loadedPersonalEmoteSets = mutableSetOf<String>()
    private var networkProvidersObserved = emptySet<Provider>()
    private val networkEmoteScopesObserved = mutableMapOf<Provider, MutableSet<ChatEmoteScope>>()
    private var runtimeDecorations = ChatDecorationSnapshot()

    /** Applies live 7TV cosmetics without replacing the provider-loaded emote catalog. */
    @Synchronized
    fun applyDecorationUpdate(update: ChatDecorationUpdate) {
        if (closed) return
        if (update is ChatDecorationUpdate.EmoteSet) {
            applyEmoteSetUpdate(update)
            return
        }
        runtimeDecorations = runtimeDecorations.apply(update)
        val currentState = _state.value
        val current = currentState.snapshot
        val next = current.withRuntimeDecorations(runtimeDecorations)
        if (next != current) {
            _state.value = currentState.copy(snapshot = next.copy(revision = current.revision + 1))
        }
        update.userPersonalEmoteSetId()?.let { setId ->
            promotePendingPersonalSet(setId)
            loadPersonalEmoteSet(setId)
        }
    }

    private fun applyEmoteSetUpdate(update: ChatDecorationUpdate.EmoteSet) {
        val currentState = _state.value
        val current = currentState.snapshot
        val sevenTv = current.sevenTv
        val channelSet = !current.sevenTvChannelSetId.isNullOrBlank() &&
                update.setId == current.sevenTvChannelSetId
        val personalSet = !channelSet && (
                update.setId in sevenTv.personal ||
                        current.userDecorations.values.any { it.personalEmoteSetId == update.setId }
                )
        val pending = sevenTv.pending[update.setId].orEmpty()
        val addedFor = { scope: ChatEmoteScope ->
            update.added.mapValues { (_, emote) -> emote.copy(scope = scope) }
        }
        val nextSevenTv = when {
            channelSet -> sevenTv.copy(
                channel = (sevenTv.channel + pending.mapValues { (_, emote) -> emote.copy(scope = ChatEmoteScope.CHANNEL) } +
                        addedFor(ChatEmoteScope.CHANNEL)) - update.removedNames,
                pending = sevenTv.pending - update.setId,
            )
            personalSet -> sevenTv.copy(
                personal = sevenTv.personal + (update.setId to (
                        sevenTv.personal[update.setId].orEmpty() +
                                pending.mapValues { (_, emote) -> emote.copy(scope = ChatEmoteScope.PERSONAL) } +
                                addedFor(ChatEmoteScope.PERSONAL) - update.removedNames
                        )),
                pending = sevenTv.pending - update.setId,
            )
            else -> sevenTv.copy(
                // Never guess that an unknown set is channel-wide. It can only be
                // promoted after the channel set ID or a sender entitlement arrives.
                pending = sevenTv.pending + (update.setId to (
                        (pending + addedFor(ChatEmoteScope.PERSONAL)) - update.removedNames
                        )),
            )
        }
        if (nextSevenTv == sevenTv) return
        val next = current.copy(revision = current.revision + 1, sevenTv = nextSevenTv)
        _state.value = currentState.copy(snapshot = next)
        enqueuePersistence(next)
    }

    private fun promotePendingPersonalSet(setId: String) {
        if (setId.isBlank()) return
        val currentState = _state.value
        val current = currentState.snapshot
        val pending = current.sevenTv.pending[setId].orEmpty()
        if (pending.isEmpty()) return
        val personal = current.sevenTv.personal[setId].orEmpty()
        val next = current.copy(
            revision = current.revision + 1,
            sevenTv = current.sevenTv.copy(
                personal = current.sevenTv.personal + (setId to (personal + pending.mapValues { (_, emote) ->
                    emote.copy(scope = ChatEmoteScope.PERSONAL)
                })),
                pending = current.sevenTv.pending - setId,
            ),
        )
        _state.value = currentState.copy(snapshot = next)
        enqueuePersistence(next)
    }

    /** Loads a sender's 7TV set once when the live decoration stream reveals it. */
    @Synchronized
    private fun loadPersonalEmoteSet(setId: String) {
        val loader = personalEmoteSetLoader ?: return
        if (setId.isBlank() || setId in loadedPersonalEmoteSets || setId in personalEmoteSetJobs) return
        personalEmoteSetJobs[setId] = scope.launch {
            val emotes = try {
                loader(setId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyMap()
            }
            synchronized(this@ChatCatalogRepository) {
                personalEmoteSetJobs.remove(setId)
                if (closed || emotes.isEmpty()) return@synchronized
                loadedPersonalEmoteSets += setId
                val currentState = _state.value
                val current = currentState.snapshot
                val existing = current.sevenTv.personal[setId].orEmpty()
                val next = current.copy(
                    revision = current.revision + 1,
                    sevenTv = current.sevenTv.copy(
                        personal = current.sevenTv.personal + (setId to (emotes + existing)),
                    ),
                )
                _state.value = currentState.copy(snapshot = next)
                enqueuePersistence(next)
            }
        }
    }

    private fun ChatDecorationUpdate.userPersonalEmoteSetId(): String? =
        (this as? ChatDecorationUpdate.User)?.personalEmoteSetId

    private fun ChatCatalogSnapshot.withRuntimeDecorations(value: ChatDecorationSnapshot): ChatCatalogSnapshot = copy(
        userDecorations = userDecorations + value.users,
        namePaints = namePaints + value.paints,
        sevenTvBadges = sevenTvBadges + value.badges,
    )

    private fun ChatDecorationSnapshot.apply(update: ChatDecorationUpdate): ChatDecorationSnapshot = when (update) {
        is ChatDecorationUpdate.EmoteSet -> this
        is ChatDecorationUpdate.Paint -> copy(paints = paints + (update.id to update.paint))
        is ChatDecorationUpdate.Badge -> copy(badges = badges + (update.id to update.badge))
        is ChatDecorationUpdate.User -> {
            val previous = users[update.userId] ?: ChatUserDecoration()
            copy(users = users + (update.userId to previous.copy(
                paintId = update.paintId ?: previous.paintId,
                badgeId = update.badgeId ?: previous.badgeId,
                personalEmoteSetId = update.personalEmoteSetId ?: previous.personalEmoteSetId,
            )))
        }
    }

    init {
        cache?.let { persistence ->
            cacheJob = scope.launch {
                val cached = try {
                    persistence.readEntry()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                synchronized(this@ChatCatalogRepository) {
                    if (!closed) {
                        val currentState = _state.value
                        val current = currentState.snapshot
                        val cacheConfigMatches = cached == null ||
                                cached.catalogConfigFingerprint == null ||
                                cached.catalogConfigFingerprint == source.catalogConfigFingerprint
                        if (cached != null && cacheConfigMatches) {
                            cacheFetchedAtMs = cached.fetchedAtMs.takeIf { it > 0L }
                            badgesFetchedAtMs = cached.badgesFetchedAtMs.takeIf { it > 0L }
                            cacheCatalogConfigFingerprint = cached.catalogConfigFingerprint
                            cacheBadgeConfigFingerprint = cached.badgeConfigFingerprint
                            val cachedSnapshot = cached.snapshot
                            val cachedBadgesSettled = !source.hasIndependentBadgeProvider ||
                                    (cached.badgesFetchedAtMs > 0L &&
                                            isFresh(cached.badgesFetchedAtMs, cacheFreshnessMs) &&
                                            cached.badgeConfigFingerprint == source.catalogConfigFingerprint)
                            if (networkProvidersObserved.isEmpty() && networkEmoteScopesObserved.isEmpty()) {
                                // A refresh may already be in flight while disk hydration is pending.
                                // Publish the last-good cache before that refresh completes.
                                _state.value = ChatCatalogState(
                                    cachedSnapshot.withRuntimeDecorations(runtimeDecorations),
                                    hydrated = true,
                                    badgesSettled = currentState.badgesSettled || cachedBadgesSettled,
                                    structuralCatalogSettled = currentState.structuralCatalogSettled,
                                    refreshFailed = currentState.refreshFailed,
                                    thirdPartyRefreshFailed = currentState.thirdPartyRefreshFailed,
                                    forceRefreshRevision = currentState.forceRefreshRevision,
                                )
                            } else {
                                // Hydrate only providers that have not produced a network result yet.
                                // A partial refresh must not erase cached data for providers that are
                                // still retrying, while a successful provider must remain authoritative.
                                val merged = current.copy(
                                    twitch = if (Provider.TWITCH in networkProvidersObserved) current.twitch else cachedSnapshot.twitch,
                                    sevenTv = hydrateUnobservedScopes(current.sevenTv, cachedSnapshot.sevenTv, Provider.SEVEN_TV),
                                    sevenTvChannelSetId = if (
                                        ChatEmoteScope.CHANNEL in networkEmoteScopesObserved[Provider.SEVEN_TV].orEmpty()
                                    ) current.sevenTvChannelSetId else cachedSnapshot.sevenTvChannelSetId,
                                    bttv = hydrateUnobservedScopes(current.bttv, cachedSnapshot.bttv, Provider.BTTV),
                                    ffz = hydrateUnobservedScopes(current.ffz, cachedSnapshot.ffz, Provider.FFZ),
                                    badges = if (Provider.BADGES in networkProvidersObserved) current.badges else cachedSnapshot.badges,
                                    cheermotes = if (Provider.CHEERMOTES in networkProvidersObserved) current.cheermotes else cachedSnapshot.cheermotes,
                                )
                                if (merged != current) {
                                    _state.value = ChatCatalogState(
                                        merged.copy(revision = current.revision + 1),
                                        hydrated = true,
                                        badgesSettled = currentState.badgesSettled || cachedBadgesSettled,
                                        structuralCatalogSettled = currentState.structuralCatalogSettled,
                                        refreshFailed = currentState.refreshFailed,
                                        thirdPartyRefreshFailed = currentState.thirdPartyRefreshFailed,
                                        forceRefreshRevision = currentState.forceRefreshRevision,
                                    )
                                } else {
                                    _state.value = currentState.copy(
                                        hydrated = true,
                                        badgesSettled = currentState.badgesSettled || cachedBadgesSettled,
                                    )
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

    /** Refreshes immediately by default. Pass false for the normal stale-while-revalidate path. */
    @Synchronized fun refresh(force: Boolean = true) {
        if (closed) return
        val requestGeneration = ++generation
        val currentState = _state.value
        val forceRefreshRevision = if (force) {
            currentState.forceRefreshRevision + 1L
        } else {
            null
        }
        _state.value = currentState.copy(
            forceRefreshRevision = forceRefreshRevision ?: currentState.forceRefreshRevision,
        )
        refreshJob?.cancel()
        badgeRefreshJob?.cancel()
        badgeRefreshGeneration = null
        refreshJob = scope.launch {
            if (!force) cacheJob?.join()
            synchronized(this@ChatCatalogRepository) {
                if (closed || requestGeneration != generation) return@synchronized
                val structuralCacheFresh = isCacheFresh()
                val badgeCacheFresh = isBadgeCacheFresh()
                if (!force && structuralCacheFresh) {
                    _state.value = _state.value.copy(
                        structuralCatalogSettled = true,
                        badgesSettled = if (source.hasIndependentBadgeProvider) badgeCacheFresh else true,
                    )
                    if (source.hasIndependentBadgeProvider && !badgeCacheFresh && badgeRefreshJob?.isActive != true) {
                        badgeRefreshGeneration = requestGeneration
                        badgeRefreshJob = launchBadgeRefresh(
                            requestGeneration,
                            attempt = 1,
                            delayMs = 0L,
                            force = false,
                            forceRefreshRevision = null,
                        )
                    }
                    refreshJob = null
                    return@synchronized
                }
                refreshJob = launchRefresh(
                    requestGeneration,
                    attempt = 1,
                    delayMs = 0L,
                    force = force,
                    forceRefreshRevision = forceRefreshRevision,
                )
            }
        }
    }

    /** Stops provider refresh/retry work while an owning session is paused. */
    @Synchronized fun pause() {
        refreshJob?.cancel()
        refreshJob = null
        badgeRefreshJob?.cancel()
        badgeRefreshJob = null
        badgeRefreshGeneration = null
        personalEmoteSetJobs.values.forEach(Job::cancel)
        personalEmoteSetJobs.clear()
    }

    @Synchronized fun close() {
        closed = true
        cacheJob?.cancel()
        cacheJob = null
        refreshJob?.cancel()
        refreshJob = null
        badgeRefreshJob?.cancel()
        badgeRefreshJob = null
        badgeRefreshGeneration = null
        personalEmoteSetJobs.values.forEach(Job::cancel)
        personalEmoteSetJobs.clear()
    }

    private fun launchRefresh(
        requestGeneration: Long,
        attempt: Int,
        delayMs: Long,
        force: Boolean,
        forceRefreshRevision: Long?,
    ): Job = scope.launch {
        if (delayMs > 0) wait(delayMs)
        if (source.hasIndependentBadgeProvider &&
            (force || !isBadgeCacheFresh()) &&
            badgeRefreshGeneration != requestGeneration &&
            badgeRefreshJob?.isActive != true
        ) {
            badgeRefreshGeneration = requestGeneration
            badgeRefreshJob = launchBadgeRefresh(
                requestGeneration,
                attempt,
                delayMs = 0L,
                force = force,
                forceRefreshRevision = forceRefreshRevision,
            )
        }
        val result = try {
            source.load(force)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        synchronized(this@ChatCatalogRepository) {
            if (closed || requestGeneration != generation) return@synchronized
            if (result == null || !result.hasSuccessfulProvider) {
                if (!_state.value.refreshFailed ||
                    !_state.value.thirdPartyRefreshFailed ||
                    (!source.hasIndependentBadgeProvider && !_state.value.badgesSettled) ||
                    !_state.value.structuralCatalogSettled
                ) {
                    _state.value = _state.value.copy(
                        badgesSettled = _state.value.badgesSettled || !source.hasIndependentBadgeProvider,
                        structuralCatalogSettled = true,
                        refreshFailed = true,
                        thirdPartyRefreshFailed = true,
                    )
                }
                refreshJob = launchRefresh(
                    requestGeneration,
                    attempt + 1,
                    retryDelay(attempt),
                    force = false,
                    forceRefreshRevision = forceRefreshRevision,
                )
                return@synchronized
            }
            networkProvidersObserved = buildSet {
                addAll(networkProvidersObserved)
                if (result.twitch != null) add(Provider.TWITCH)
                if (result.sevenTv?.isScoped != true && result.sevenTv != null) add(Provider.SEVEN_TV)
                if (result.bttv?.isScoped != true && result.bttv != null) add(Provider.BTTV)
                if (result.ffz?.isScoped != true && result.ffz != null) add(Provider.FFZ)
                if (result.badges != null) add(Provider.BADGES)
                if (result.cheermotes != null) add(Provider.CHEERMOTES)
            }
            observeEmoteScopes(Provider.SEVEN_TV, result.sevenTv)
            observeEmoteScopes(Provider.BTTV, result.bttv)
            observeEmoteScopes(Provider.FFZ, result.ffz)
            val currentState = _state.value
            val current = currentState.snapshot
            val merged = current.copy(
                twitch = result.twitch?.value ?: current.twitch,
                sevenTv = result.sevenTv?.let { mergeEmoteScopes(current.sevenTv, it) } ?: current.sevenTv,
                sevenTvChannelSetId = result.sevenTv?.let { update ->
                    if (update.channel is ScopeUpdate.Success) update.channelSetId
                    else current.sevenTvChannelSetId
                } ?: current.sevenTvChannelSetId,
                bttv = result.bttv?.let { mergeEmoteScopes(current.bttv, it) } ?: current.bttv,
                ffz = result.ffz?.let { mergeEmoteScopes(current.ffz, it) } ?: current.ffz,
                badges = result.badges?.value ?: current.badges,
                cheermotes = result.cheermotes?.value ?: current.cheermotes,
            )
            val resolved = resolveSevenTvPendingChannelSet(merged)
            val changed = resolved != current
            val next = if (changed) resolved.copy(revision = current.revision + 1) else current
            val nextState = ChatCatalogState(
                next,
                hydrated = currentState.hydrated,
                badgesSettled = currentState.badgesSettled || !source.hasIndependentBadgeProvider,
                structuralCatalogSettled = true,
                refreshFailed = if (source.hasIndependentBadgeProvider) {
                    result.hasFailedProviderIgnoringBadges()
                } else {
                    result.hasFailedProvider
                },
                thirdPartyRefreshFailed = result.hasFailedThirdPartyProvider,
                forceRefreshRevision = currentState.forceRefreshRevision,
            )
            val nextForceRefreshRevision = currentState.forceRefreshRevision +
                    if (forceRefreshRevision != null) 1L else 0L
            val nextStateWithRefresh = nextState.copy(forceRefreshRevision = nextForceRefreshRevision)
            val aggregateFailed = if (source.hasIndependentBadgeProvider) {
                result.hasFailedProviderIgnoringBadges()
            } else {
                result.hasFailedProvider
            }
            if (!aggregateFailed) {
                cacheFetchedAtMs = System.currentTimeMillis()
                cacheCatalogConfigFingerprint = source.catalogConfigFingerprint
            }
            if (changed) {
                _state.value = nextStateWithRefresh
                enqueuePersistence(next)
            } else if (
                currentState.refreshFailed != nextState.refreshFailed ||
                currentState.thirdPartyRefreshFailed != nextState.thirdPartyRefreshFailed ||
                currentState.badgesSettled != nextState.badgesSettled ||
                currentState.structuralCatalogSettled != nextState.structuralCatalogSettled ||
                currentState.forceRefreshRevision != nextStateWithRefresh.forceRefreshRevision
            ) {
                _state.value = nextStateWithRefresh
            }
            if (!aggregateFailed && !changed) enqueuePersistence(next)
            refreshJob = if (aggregateFailed) {
                launchRefresh(
                    requestGeneration,
                    attempt + 1,
                    retryDelay(attempt),
                    force = false,
                    forceRefreshRevision = forceRefreshRevision,
                )
            } else {
                null
            }
        }
    }

    private fun launchBadgeRefresh(
        requestGeneration: Long,
        attempt: Int,
        delayMs: Long,
        force: Boolean,
        forceRefreshRevision: Long?,
    ): Job = scope.launch {
        if (delayMs > 0) wait(delayMs)
        val result = try {
            source.loadBadges(force)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        synchronized(this@ChatCatalogRepository) {
            if (closed || requestGeneration != generation) return@synchronized
            if (result != null) {
                networkProvidersObserved = networkProvidersObserved + Provider.BADGES
                badgesFetchedAtMs = System.currentTimeMillis()
                cacheBadgeConfigFingerprint = source.catalogConfigFingerprint
                val currentState = _state.value
                val current = currentState.snapshot
                val next = current.copy(
                    revision = current.revision + 1,
                    badges = result.value,
                )
                _state.value = currentState.copy(
                    snapshot = next,
                    badgesSettled = true,
                    forceRefreshRevision = currentState.forceRefreshRevision +
                            if (forceRefreshRevision != null) 1L else 0L,
                )
                enqueuePersistence(next)
                badgeRefreshJob = null
            } else {
                _state.value = _state.value.copy(
                    badgesSettled = true,
                    refreshFailed = true,
                )
                badgeRefreshJob = launchBadgeRefresh(
                    requestGeneration,
                    attempt + 1,
                    retryDelay(attempt),
                    force = false,
                    forceRefreshRevision = forceRefreshRevision,
                )
            }
        }
    }

    /**
     * Serialize writes in publication order. Independent launches allow an older slow write to
     * finish after a newer one and leave stale metadata on disk.
     */
    private fun enqueuePersistence(next: ChatCatalogSnapshot) {
        val persistence = cache ?: return
        val fetchedAtMs = cacheFetchedAtMs ?: 0L
        val badgeFetchedAtMs = badgesFetchedAtMs ?: 0L
        val catalogConfigFingerprint = cacheCatalogConfigFingerprint
        val badgeConfigFingerprint = cacheBadgeConfigFingerprint
        val previous = persistenceJob
        persistenceJob = scope.launch {
            previous?.join()
            try {
                persistence.write(
                    next,
                    fetchedAtMs,
                    badgeFetchedAtMs,
                    catalogConfigFingerprint,
                    badgeConfigFingerprint,
                )
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

    private fun isCacheFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        cacheFetchedAtMs?.let { fetchedAt ->
            cacheConfigIsCurrent() && isFresh(fetchedAt, cacheFreshnessMs, nowMs)
        } == true

    private fun isBadgeCacheFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        val fetchedAt = badgesFetchedAtMs ?: return false
        if (cacheBadgeConfigFingerprint != source.catalogConfigFingerprint) return false
        return isFresh(fetchedAt, cacheFreshnessMs, nowMs)
    }

    private fun cacheConfigIsCurrent(): Boolean =
        cacheCatalogConfigFingerprint == source.catalogConfigFingerprint

    private fun isFresh(fetchedAtMs: Long, ttlMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - fetchedAtMs in 0 until ttlMs

    private fun mergeEmoteScopes(
        current: ScopedEmoteCatalog,
        update: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>,
    ): ScopedEmoteCatalog {
        if (update.global == null && update.channel == null && update.personal == null) {
            return ScopedEmoteCatalog.fromEffective(update.value).copy(pending = current.pending)
        }
        var global = current.global
        var channel = current.channel
        var personal = current.personal
        var pending = current.pending
        var legacyCombined = current.legacyCombined
        fun apply(scope: ChatEmoteScope, result: ScopeUpdate<Map<String, ChatCatalogEmote>>?) {
            when (result) {
                is ScopeUpdate.Success -> {
                    when (scope) {
                        ChatEmoteScope.GLOBAL -> global = result.value
                        ChatEmoteScope.CHANNEL -> channel = result.value
                        ChatEmoteScope.PERSONAL -> Unit
                        ChatEmoteScope.LEGACY_COMBINED -> legacyCombined = result.value
                    }
                }
                ScopeUpdate.Failed, null -> Unit
            }
        }
        apply(ChatEmoteScope.GLOBAL, update.global)
        apply(ChatEmoteScope.CHANNEL, update.channel)
        when (val result = update.personal) {
            // Keep sender sets discovered through the live decoration stream; a later account
            // entitlement refresh only knows about the logged-in viewer's sets.
            is ScopeUpdate.Success -> personal = personal + result.value
            ScopeUpdate.Failed, null -> Unit
        }
        if (update.global is ScopeUpdate.Success && update.channel is ScopeUpdate.Success) {
            legacyCombined = emptyMap()
        }
        return ScopedEmoteCatalog(global, channel, personal, pending, legacyCombined)
    }

    private fun resolveSevenTvPendingChannelSet(snapshot: ChatCatalogSnapshot): ChatCatalogSnapshot {
        val channelSetId = snapshot.sevenTvChannelSetId?.takeIf { it.isNotBlank() } ?: return snapshot
        val pending = snapshot.sevenTv.pending[channelSetId].orEmpty()
        if (pending.isEmpty()) return snapshot
        return snapshot.copy(
            sevenTv = snapshot.sevenTv.copy(
                channel = snapshot.sevenTv.channel + pending.mapValues { (_, emote) ->
                    emote.copy(scope = ChatEmoteScope.CHANNEL)
                },
                pending = snapshot.sevenTv.pending - channelSetId,
            ),
        )
    }

    private fun observeEmoteScopes(
        provider: Provider,
        update: ChatCatalogProviderUpdate<Map<String, ChatCatalogEmote>>?,
    ) {
        if (update == null) return
        val observed = networkEmoteScopesObserved.getOrPut(provider) { mutableSetOf() }
        if (update.global is ScopeUpdate.Success) observed += ChatEmoteScope.GLOBAL
        if (update.channel is ScopeUpdate.Success) observed += ChatEmoteScope.CHANNEL
        if (update.personal is ScopeUpdate.Success) observed += ChatEmoteScope.PERSONAL
        if (observed.isEmpty()) networkEmoteScopesObserved.remove(provider)
    }

    private fun hydrateUnobservedScopes(
        current: ScopedEmoteCatalog,
        cached: ScopedEmoteCatalog,
        provider: Provider,
    ): ScopedEmoteCatalog {
        if (provider in networkProvidersObserved) return current
        val observed = networkEmoteScopesObserved[provider].orEmpty()
        val realScopesAuthoritative =
            ChatEmoteScope.GLOBAL in observed && ChatEmoteScope.CHANNEL in observed
        return current.copy(
            global = if (ChatEmoteScope.GLOBAL in observed) current.global else cached.global,
            channel = if (ChatEmoteScope.CHANNEL in observed) current.channel else cached.channel,
            personal = if (ChatEmoteScope.PERSONAL in observed) current.personal else cached.personal,
            legacyCombined = when {
                realScopesAuthoritative -> emptyMap()
                current.legacyCombined.isNotEmpty() -> current.legacyCombined
                else -> cached.legacyCombined
            },
            pending = current.pending,
        )
    }
}
