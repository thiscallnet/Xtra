package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogCache
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogLoadResult
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogProviderUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSource
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogState
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopeUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ChatCatalogRepositoryTest {
    @Test
    fun failedChannelScopeKeepsLastGoodChannelEmotesAndRetries() = runBlocking {
        val oldGlobal = emote("old-global", ChatAssetProvider.SEVEN_TV).copy(scope = ChatEmoteScope.GLOBAL)
        val oldChannel = emote("old-channel", ChatAssetProvider.SEVEN_TV).copy(scope = ChatEmoteScope.CHANNEL)
        val newGlobal = emote("new-global", ChatAssetProvider.SEVEN_TV).copy(scope = ChatEmoteScope.GLOBAL)
        val retryStarted = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                retryStarted.complete(Unit)
                ChatCatalogLoadResult(
                    sevenTv = ChatCatalogProviderUpdate(
                        value = emptyMap(),
                        global = ScopeUpdate.Success(mapOf("new-global" to newGlobal)),
                        channel = ScopeUpdate.Failed,
                    ),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read() = ChatCatalogSnapshot(
                    revision = 1,
                    sevenTv = mapOf("old-global" to oldGlobal, "old-channel" to oldChannel),
                )
                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
            wait = { awaitCancellation() },
        )

        withTimeout(1_000) {
            while (!repository.state.value.hydrated) delay(1)
        }
        repository.refresh()
        withTimeout(1_000) { retryStarted.await() }
        withTimeout(1_000) {
            while (repository.state.value.snapshot.sevenTv["new-global"] != newGlobal) delay(1)
        }

        assertEquals(newGlobal, repository.state.value.snapshot.sevenTv["new-global"])
        assertEquals(oldChannel, repository.state.value.snapshot.sevenTv["old-channel"])
        assertTrue(repository.state.value.refreshFailed)
        repository.close()
        scope.cancel()
    }

    @Test
    fun failedChannelScopeKeepsSchemaV1CombinedCacheEntries() = runBlocking {
        val legacyChannel = emote("legacy-channel", ChatAssetProvider.SEVEN_TV)
            .copy(scope = ChatEmoteScope.LEGACY_COMBINED)
        val newGlobal = emote("new-global", ChatAssetProvider.SEVEN_TV)
            .copy(scope = ChatEmoteScope.GLOBAL)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    sevenTv = ChatCatalogProviderUpdate(
                        value = emptyMap(),
                        global = ScopeUpdate.Success(mapOf("new-global" to newGlobal)),
                        channel = ScopeUpdate.Failed,
                    ),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read() = ChatCatalogSnapshot(
                    revision = 1,
                    sevenTv = mapOf("legacy-channel" to legacyChannel),
                )

                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
            wait = { awaitCancellation() },
        )

        withTimeout(1_000) {
            while (!repository.state.value.hydrated) delay(1)
        }
        repository.refresh()
        withTimeout(1_000) {
            while (repository.state.value.snapshot.sevenTv["new-global"] != newGlobal) delay(1)
        }

        assertEquals(legacyChannel, repository.state.value.snapshot.sevenTv["legacy-channel"])
        assertEquals(newGlobal, repository.state.value.snapshot.sevenTv["new-global"])
        repository.close()
        scope.cancel()
    }

    @Test
    fun successfulGlobalAndChannelRefreshRetiresLegacyCombinedEntries() = runBlocking {
        val legacyGlobalAlias = emote("A", ChatAssetProvider.SEVEN_TV)
            .copy(scope = ChatEmoteScope.LEGACY_COMBINED)
        val legacyChannelAlias = emote("B", ChatAssetProvider.SEVEN_TV)
            .copy(scope = ChatEmoteScope.LEGACY_COMBINED)
        val staleLegacy = emote("RemovedOldEmote", ChatAssetProvider.SEVEN_TV)
            .copy(scope = ChatEmoteScope.LEGACY_COMBINED)
        val currentGlobal = emote("A", ChatAssetProvider.SEVEN_TV)
            .copy(scope = ChatEmoteScope.GLOBAL)
        val currentChannel = emote("B", ChatAssetProvider.SEVEN_TV)
            .copy(scope = ChatEmoteScope.CHANNEL)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    sevenTv = ChatCatalogProviderUpdate(
                        value = mapOf("A" to currentGlobal, "B" to currentChannel),
                        global = ScopeUpdate.Success(mapOf("A" to currentGlobal)),
                        channel = ScopeUpdate.Success(mapOf("B" to currentChannel)),
                    ),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read() = ChatCatalogSnapshot(
                    revision = 1,
                    sevenTv = mapOf(
                        "A" to legacyGlobalAlias,
                        "B" to legacyChannelAlias,
                        "RemovedOldEmote" to staleLegacy,
                    ),
                )

                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
        )

        withTimeout(1_000) {
            while (!repository.state.value.hydrated) delay(1)
        }
        repository.refresh()
        withTimeout(1_000) {
            while (repository.state.value.snapshot.sevenTv["A"] != currentGlobal) delay(1)
        }

        assertEquals(currentGlobal, repository.state.value.snapshot.sevenTv["A"])
        assertEquals(currentChannel, repository.state.value.snapshot.sevenTv["B"])
        assertTrue("RemovedOldEmote" !in repository.state.value.snapshot.sevenTv)
        repository.close()
        scope.cancel()
    }

    @Test
    fun successfulEmptyChannelScopeClearsOnlyChannelEntries() = runBlocking {
        val oldGlobal = emote("global", ChatAssetProvider.BTTV).copy(scope = ChatEmoteScope.GLOBAL)
        val oldChannel = emote("channel", ChatAssetProvider.BTTV).copy(scope = ChatEmoteScope.CHANNEL)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    twitch = ChatCatalogProviderUpdate(emptyMap()),
                    bttv = ChatCatalogProviderUpdate(
                        value = emptyMap(),
                        global = ScopeUpdate.Success(mapOf("global" to oldGlobal)),
                        channel = ScopeUpdate.Success(emptyMap()),
                    ),
                    sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                    ffz = ChatCatalogProviderUpdate(emptyMap()),
                    badges = ChatCatalogProviderUpdate(emptyMap()),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read() = ChatCatalogSnapshot(
                    revision = 1,
                    bttv = mapOf("global" to oldGlobal, "channel" to oldChannel),
                )
                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
        )
        withTimeout(1_000) { while (!repository.state.value.hydrated) delay(1) }
        repository.refresh()
        withTimeout(1_000) { while (repository.state.value.snapshot.revision < 2) delay(1) }

        assertEquals(oldGlobal, repository.state.value.snapshot.bttv["global"])
        assertTrue("channel" !in repository.state.value.snapshot.bttv)
        assertTrue(!repository.state.value.refreshFailed)
        repository.close()
        scope.cancel()
    }

    @Test
    fun hydrationSignalStaysFalseUntilLastGoodCacheReadCompletes() = runBlocking {
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val cached = ChatCatalogSnapshot(
            revision = 3,
            sevenTv = mapOf("Party" to emote("party", ChatAssetProvider.SEVEN_TV)),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource { ChatCatalogLoadResult() },
            cache = object : ChatCatalogCache {
                override suspend fun read(): ChatCatalogSnapshot {
                    readStarted.complete(Unit)
                    releaseRead.await()
                    return cached
                }

                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
        )

        val observedStates = CopyOnWriteArrayList<ChatCatalogState>()
        val collector = launch {
            repository.state.collect { observedStates += it }
        }

        readStarted.await()
        withTimeout(1_000) { while (observedStates.isEmpty()) delay(1) }
        assertFalse(observedStates.first().hydrated)
        assertFalse(repository.state.value.hydrated)
        assertTrue(repository.state.value.snapshot.sevenTv.isEmpty())

        releaseRead.complete(Unit)
        withTimeout(1_000) {
            while (observedStates.none { it.hydrated }) delay(1)
        }
        val hydratedState = observedStates.first { it.hydrated }
        assertEquals(cached.sevenTv, hydratedState.snapshot.sevenTv)
        assertEquals(cached.sevenTv, repository.state.value.snapshot.sevenTv)
        assertTrue(observedStates.none { it.hydrated && it.snapshot.sevenTv.isEmpty() })

        collector.cancel()
        repository.close()
        scope.cancel()
    }

    @Test
    fun pendingRefreshDoesNotDiscardCacheHydration() = runBlocking {
        val cacheReadStarted = CompletableDeferred<Unit>()
        val releaseCacheRead = CompletableDeferred<Unit>()
        val releaseNetwork = CompletableDeferred<Unit>()
        val cached = ChatCatalogSnapshot(
            revision = 7,
            sevenTv = mapOf("cached" to emote("cached", ChatAssetProvider.SEVEN_TV)),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                releaseNetwork.await()
                ChatCatalogLoadResult(
                    twitch = ChatCatalogProviderUpdate(emptyMap()),
                    sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                    bttv = ChatCatalogProviderUpdate(emptyMap()),
                    ffz = ChatCatalogProviderUpdate(emptyMap()),
                    badges = ChatCatalogProviderUpdate(emptyMap()),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read(): ChatCatalogSnapshot {
                    cacheReadStarted.complete(Unit)
                    releaseCacheRead.await()
                    return cached
                }

                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
        )

        cacheReadStarted.await()
        repository.refresh()
        releaseCacheRead.complete(Unit)

        withTimeout(1_000) {
            while (repository.state.value.snapshot.revision != cached.revision) delay(1)
        }
        assertEquals(cached.sevenTv, repository.state.value.snapshot.sevenTv)

        releaseNetwork.complete(Unit)
        repository.close()
        scope.cancel()
    }

    @Test
    fun lateCacheHydrationFillsProvidersStillRetrying() = runBlocking {
        val cacheReadStarted = CompletableDeferred<Unit>()
        val releaseCacheRead = CompletableDeferred<Unit>()
        val cachedBttv = emote("cached-bttv", ChatAssetProvider.BTTV)
        val networkSevenTv = emote("network-seven", ChatAssetProvider.SEVEN_TV)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    sevenTv = ChatCatalogProviderUpdate(mapOf("network" to networkSevenTv)),
                    // Other providers failed and must remain eligible for cache hydration.
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read(): ChatCatalogSnapshot {
                    cacheReadStarted.complete(Unit)
                    releaseCacheRead.await()
                    return ChatCatalogSnapshot(
                        revision = 4,
                        sevenTv = mapOf("cached" to emote("cached-seven", ChatAssetProvider.SEVEN_TV)),
                        bttv = mapOf("cached" to cachedBttv),
                    )
                }

                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
            wait = { awaitCancellation() },
        )

        cacheReadStarted.await()
        repository.refresh()
        withTimeout(1_000) {
            while (repository.state.value.snapshot.sevenTv["network"] != networkSevenTv) delay(1)
        }

        releaseCacheRead.complete(Unit)
        withTimeout(1_000) {
            while (repository.state.value.snapshot.bttv["cached"] != cachedBttv) delay(1)
        }
        assertEquals(networkSevenTv, repository.state.value.snapshot.sevenTv["network"])

        repository.close()
        scope.cancel()
    }

    @Test
    fun lateCacheCannotOverwriteAnAuthoritativeFullNetworkResult() = runBlocking {
        val cacheReadStarted = CompletableDeferred<Unit>()
        val releaseCacheRead = CompletableDeferred<Unit>()
        val networkFinished = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                val result = ChatCatalogLoadResult(
                    twitch = ChatCatalogProviderUpdate(emptyMap()),
                    sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                    bttv = ChatCatalogProviderUpdate(emptyMap()),
                    ffz = ChatCatalogProviderUpdate(emptyMap()),
                    badges = ChatCatalogProviderUpdate(emptyMap()),
                )
                networkFinished.complete(Unit)
                result
            },
            cache = object : ChatCatalogCache {
                override suspend fun read(): ChatCatalogSnapshot {
                    cacheReadStarted.complete(Unit)
                    releaseCacheRead.await()
                    return ChatCatalogSnapshot(
                        revision = 4,
                        sevenTv = mapOf("stale" to emote("stale", ChatAssetProvider.SEVEN_TV)),
                    )
                }

                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
        )

        cacheReadStarted.await()
        repository.refresh()
        networkFinished.await()
        releaseCacheRead.complete(Unit)
        delay(50)

        assertTrue(repository.state.value.snapshot.sevenTv.isEmpty())
        repository.close()
        scope.cancel()
    }

    @Test
    fun providerFailurePreservesLastGoodMapButSuccessfulEmptyMapReplacesIt() = runBlocking {
        val oldSevenTv = emote("old", ChatAssetProvider.SEVEN_TV)
        val oldBttv = emote("old-bttv", ChatAssetProvider.BTTV)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                    // Null means this provider failed and its last-good map must survive.
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read() = ChatCatalogSnapshot(
                    revision = 7,
                    sevenTv = mapOf("old" to oldSevenTv),
                    bttv = mapOf("old-bttv" to oldBttv),
                )

                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
        )
        withTimeout(1_000) { while (repository.state.value.snapshot.revision != 7L) delay(1) }

        repository.refresh()
        withTimeout(1_000) { while (repository.state.value.snapshot.revision != 8L) delay(1) }

        assertTrue(repository.state.value.snapshot.sevenTv.isEmpty())
        assertEquals(oldBttv, repository.state.value.snapshot.bttv["old-bttv"])
        scope.cancel()
    }

    @Test
    fun failedRefreshRetriesAndPublishesOnlyAfterAProviderSucceeds() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                attempts++
                if (attempts == 1) {
                    ChatCatalogLoadResult()
                } else {
                    ChatCatalogLoadResult(
                        twitch = ChatCatalogProviderUpdate(emptyMap()),
                        sevenTv = ChatCatalogProviderUpdate(mapOf("Party" to emote("party", ChatAssetProvider.SEVEN_TV))),
                        bttv = ChatCatalogProviderUpdate(emptyMap()),
                        ffz = ChatCatalogProviderUpdate(emptyMap()),
                        badges = ChatCatalogProviderUpdate(emptyMap()),
                    )
                }
            },
            wait = { delayMs -> waits += delayMs },
        )

        repository.refresh()
        withTimeout(1_000) { while (repository.state.value.snapshot.sevenTv.isEmpty()) delay(1) }

        assertEquals(2, attempts)
        assertEquals(listOf(1_000L), waits)
        scope.cancel()
    }

    @Test
    fun unchangedPartialRefreshDoesNotCreateRevision() = runBlocking {
        val retryWait = CompletableDeferred<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                    // The other providers failed. The successful provider has no change.
                )
            },
            wait = { delayMs ->
                retryWait.complete(delayMs)
                awaitCancellation()
            },
        )

        repository.refresh()
        withTimeout(1_000) { retryWait.await() }

        assertEquals(0L, repository.state.value.snapshot.revision)
        repository.close()
        scope.cancel()
    }

    @Test
    fun catalogWritesRemainInPublicationOrder() = runBlocking {
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val writes = CopyOnWriteArrayList<Long>()
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                val attempt = attempts.incrementAndGet()
                val name = if (attempt == 1) "first" else "second"
                ChatCatalogLoadResult(
                    twitch = ChatCatalogProviderUpdate(mapOf(name to emote(name, ChatAssetProvider.TWITCH))),
                    sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                    bttv = ChatCatalogProviderUpdate(emptyMap()),
                    ffz = ChatCatalogProviderUpdate(emptyMap()),
                    badges = ChatCatalogProviderUpdate(emptyMap()),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read(): ChatCatalogSnapshot? = null

                override suspend fun write(snapshot: ChatCatalogSnapshot) {
                    if (snapshot.revision == 1L) releaseFirstWrite.await()
                    writes += snapshot.revision
                }
            },
        )

        repository.refresh()
        withTimeout(1_000) { while (attempts.get() < 1) delay(1) }
        withTimeout(1_000) { while (repository.state.value.snapshot.revision < 1L) delay(1) }

        repository.refresh()
        withTimeout(1_000) { while (attempts.get() < 2) delay(1) }
        withTimeout(1_000) { while (repository.state.value.snapshot.revision < 2L) delay(1) }
        assertTrue(writes.isEmpty())

        releaseFirstWrite.complete(Unit)
        withTimeout(1_000) { while (writes.size < 2) delay(1) }
        assertEquals(listOf(1L, 2L), writes)
        repository.close()
        scope.cancel()
    }

    @Test
    fun refreshAfterCloseDoesNotStartSourceWork() = runBlocking {
        val attempts = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                attempts.incrementAndGet()
                ChatCatalogLoadResult()
            },
        )

        repository.close()
        repository.refresh()

        assertEquals(0, attempts.get())
        scope.cancel()
    }

    private fun emote(name: String, provider: ChatAssetProvider) = ChatCatalogEmote(
        name = name,
        asset = ChatAssetSpec(ChatAssetKey(name), 28, 28, 28),
        provider = provider,
        animated = false,
    )
}
