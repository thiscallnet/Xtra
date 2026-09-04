package com.github.andreyasadchy.xtra.ui.chat.v2

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogCache
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogLoadResult
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogProviderUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSource
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogState
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatDecorationUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ExpiringSingleFlightCache
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopeUpdate
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    fun singleFlightCacheKeepsDifferentProviderKeysParallelAndJoinsDuplicates() = runBlocking {
        val cache = ExpiringSingleFlightCache<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()

        val first = async(Dispatchers.Default) {
            cache.get("seven-tv") {
                calls.incrementAndGet()
                firstStarted.complete(Unit)
                release.await()
                "seven-tv-value"
            }
        }
        val second = async(Dispatchers.Default) {
            cache.get("bttv") {
                calls.incrementAndGet()
                secondStarted.complete(Unit)
                release.await()
                "bttv-value"
            }
        }
        withTimeout(1_000) {
            firstStarted.await()
            secondStarted.await()
        }
        assertEquals(2, calls.get())
        release.complete(Unit)
        assertEquals("seven-tv-value", first.await())
        assertEquals("bttv-value", second.await())

        val duplicateCalls = AtomicInteger()
        val duplicateRelease = CompletableDeferred<Unit>()
        val duplicateFirst = async(Dispatchers.Default) {
            cache.get("ffz") {
                duplicateCalls.incrementAndGet()
                duplicateRelease.await()
                "ffz-value"
            }
        }
        // The duplicate must join the first load rather than win the race to own it.
        withTimeout(1_000) {
            while (duplicateCalls.get() < 1) delay(1)
        }
        val duplicateSecond = async(Dispatchers.Default) {
            cache.get("ffz") {
                duplicateCalls.incrementAndGet()
                "unexpected"
            }
        }
        assertEquals(1, duplicateCalls.get())
        duplicateRelease.complete(Unit)
        assertEquals("ffz-value", duplicateFirst.await())
        assertEquals("ffz-value", duplicateSecond.await())
    }

    @Test
    fun newlyObservedPersonalSetIsLoadedOnceAndAttachedToItsSetId() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val personal = ChatCatalogEmote(
            name = "VIPWave",
            asset = ChatAssetSpec(ChatAssetKey("vip"), 28, 28, 28),
            provider = ChatAssetProvider.SEVEN_TV,
            animated = false,
            scope = ChatEmoteScope.PERSONAL,
        )
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    twitch = ChatCatalogProviderUpdate(emptyMap()),
                    sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                    bttv = ChatCatalogProviderUpdate(emptyMap()),
                    ffz = ChatCatalogProviderUpdate(emptyMap()),
                    badges = ChatCatalogProviderUpdate(emptyMap()),
                    cheermotes = ChatCatalogProviderUpdate(emptyMap()),
                )
            },
            personalEmoteSetLoader = { setId ->
                calls.incrementAndGet()
                delay(50)
                mapOf(personal.name to personal)
            },
        )

        repository.applyDecorationUpdate(ChatDecorationUpdate.User("user", personalEmoteSetId = "set-a"))
        repository.applyDecorationUpdate(ChatDecorationUpdate.User("another", personalEmoteSetId = "set-a"))
        withTimeout(1_000) {
            while (repository.state.value.snapshot.sevenTv.personal["set-a"].isNullOrEmpty()) delay(1)
        }

        assertEquals(1, calls.get())
        assertEquals(personal, repository.state.value.snapshot.sevenTv.personal["set-a"]?.get("VIPWave"))
        repository.close()
        scope.cancel()
    }

    @Test
    fun liveSevenTvSetUpdatesUseChannelIdentityAndKeepUnknownSetsIsolated() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    twitch = ChatCatalogProviderUpdate(emptyMap()),
                    sevenTv = ChatCatalogProviderUpdate(
                        value = emptyMap(),
                        global = ScopeUpdate.Success(emptyMap()),
                        channel = ScopeUpdate.Success(emptyMap()),
                        personal = ScopeUpdate.Success(emptyMap()),
                        channelSetId = "channel-set",
                    ),
                    bttv = ChatCatalogProviderUpdate(emptyMap()),
                    ffz = ChatCatalogProviderUpdate(emptyMap()),
                    badges = ChatCatalogProviderUpdate(emptyMap()),
                    cheermotes = ChatCatalogProviderUpdate(emptyMap()),
                )
            },
        )
        repository.refresh()
        withTimeout(1_000) {
            while (repository.state.value.snapshot.sevenTvChannelSetId != "channel-set") delay(1)
        }

        repository.applyDecorationUpdate(ChatDecorationUpdate.EmoteSet(
            setId = "channel-set",
            added = mapOf("ChannelLive" to emote("ChannelLive", ChatAssetProvider.SEVEN_TV)),
        ))
        assertEquals(ChatEmoteScope.CHANNEL, repository.state.value.snapshot.sevenTv.channel["ChannelLive"]?.scope)
        assertTrue(repository.state.value.snapshot.sevenTv.pending.isEmpty())

        repository.applyDecorationUpdate(ChatDecorationUpdate.User("user", personalEmoteSetId = "personal-set"))
        repository.applyDecorationUpdate(ChatDecorationUpdate.EmoteSet(
            setId = "personal-set",
            added = mapOf("PersonalLive" to emote("PersonalLive", ChatAssetProvider.SEVEN_TV)),
        ))
        assertEquals(ChatEmoteScope.PERSONAL, repository.state.value.snapshot.sevenTv.personal["personal-set"]?.get("PersonalLive")?.scope)
        assertTrue(repository.state.value.snapshot.sevenTv.channel["PersonalLive"] == null)

        repository.applyDecorationUpdate(ChatDecorationUpdate.EmoteSet(
            setId = "unseen-personal-set",
            added = mapOf("PendingLive" to emote("PendingLive", ChatAssetProvider.SEVEN_TV)),
        ))
        assertTrue(repository.state.value.snapshot.sevenTv.channel["PendingLive"] == null)
        assertTrue(repository.state.value.snapshot.sevenTv.pending["unseen-personal-set"]?.containsKey("PendingLive") == true)

        repository.applyDecorationUpdate(ChatDecorationUpdate.User("other-user", personalEmoteSetId = "unseen-personal-set"))
        assertEquals(ChatEmoteScope.PERSONAL, repository.state.value.snapshot.sevenTv.personal["unseen-personal-set"]?.get("PendingLive")?.scope)
        assertTrue(repository.state.value.snapshot.sevenTv.pending["unseen-personal-set"].isNullOrEmpty())

        repository.close()
        scope.cancel()
    }

    @Test
    fun thirdPartyFailureRecoveryIsPublishedWhenBadgesKeepFailing() = runBlocking {
        val attempts = AtomicInteger()
        val firstWait = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                if (attempts.incrementAndGet() == 1) {
                    ChatCatalogLoadResult(
                        twitch = ChatCatalogProviderUpdate(emptyMap()),
                        badges = null,
                    )
                } else {
                    ChatCatalogLoadResult(
                        twitch = ChatCatalogProviderUpdate(emptyMap()),
                        sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                        bttv = ChatCatalogProviderUpdate(emptyMap()),
                        ffz = ChatCatalogProviderUpdate(emptyMap()),
                        badges = null,
                    )
                }
            },
            wait = { delayMs ->
                if (delayMs == 1_000L) {
                    firstWait.complete(Unit)
                    releaseRetry.await()
                } else {
                    awaitCancellation()
                }
            },
        )
        repository.refresh()
        withTimeout(1_000) { firstWait.await() }
        assertTrue(repository.state.value.thirdPartyRefreshFailed)
        releaseRetry.complete(Unit)
        withTimeout(1_000) {
            while (attempts.get() < 2 || repository.state.value.thirdPartyRefreshFailed) delay(1)
        }

        assertTrue(repository.state.value.refreshFailed)
        assertFalse(repository.state.value.thirdPartyRefreshFailed)
        repository.close()
        scope.cancel()
    }

    @Test
    fun badgeFailureDoesNotMarkThirdPartyPickerAsFailed() {
        val result = ChatCatalogLoadResult(
            sevenTv = ChatCatalogProviderUpdate(emptyMap()),
            bttv = ChatCatalogProviderUpdate(emptyMap()),
            ffz = ChatCatalogProviderUpdate(emptyMap()),
            // Badges are unrelated to the third-party emote picker.
            badges = null,
        )

        assertFalse(result.hasFailedThirdPartyProvider)
        assertTrue(result.hasFailedProvider)
    }

    @Test
    fun failedChannelScopeKeepsLastGoodChannelEmotesAndRetries() = runBlocking {
        val oldGlobal = emote("old-global", ChatAssetProvider.SEVEN_TV).copy(scope = ChatEmoteScope.GLOBAL)
        val oldChannel = emote("old-channel", ChatAssetProvider.SEVEN_TV).copy(scope = ChatEmoteScope.CHANNEL)
        val newGlobal = emote("new-global", ChatAssetProvider.SEVEN_TV).copy(scope = ChatEmoteScope.GLOBAL)
        val attempts = AtomicInteger()
        val waitStarted = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val retryInvocation = CompletableDeferred<Unit>()
        val waits = CopyOnWriteArrayList<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                if (attempts.incrementAndGet() == 2) retryInvocation.complete(Unit)
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
                    sevenTv = ScopedEmoteCatalog(global = mapOf("old-global" to oldGlobal), channel = mapOf("old-channel" to oldChannel)),
                )
                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
            wait = { delayMs ->
                if (waits.isEmpty()) {
                    waits += delayMs
                    waitStarted.complete(Unit)
                    releaseRetry.await()
                } else {
                    awaitCancellation()
                }
            },
        )

        withTimeout(1_000) {
            while (!repository.state.value.hydrated) delay(1)
        }
        repository.refresh()
        withTimeout(1_000) { waitStarted.await() }
        withTimeout(1_000) {
            while (repository.state.value.snapshot.sevenTv["new-global"] != newGlobal) delay(1)
        }

        assertEquals(newGlobal, repository.state.value.snapshot.sevenTv["new-global"])
        assertEquals(oldChannel, repository.state.value.snapshot.sevenTv["old-channel"])
        assertTrue(repository.state.value.refreshFailed)
        assertEquals(1, attempts.get())
        releaseRetry.complete(Unit)
        withTimeout(1_000) { retryInvocation.await() }
        assertEquals(2, attempts.get())
        assertEquals(listOf(1_000L), waits)
        repository.close()
        scope.cancel()
    }

    @Test
    fun sameAliasKeepsLastGoodChannelWhenGlobalRefreshSucceeds() = runBlocking {
        val oldChannel = emote("same-channel", ChatAssetProvider.SEVEN_TV).copy(
            name = "same",
            scope = ChatEmoteScope.CHANNEL,
        )
        val newGlobal = emote("same-global", ChatAssetProvider.SEVEN_TV).copy(
            name = "same",
            scope = ChatEmoteScope.GLOBAL,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    twitch = ChatCatalogProviderUpdate(emptyMap()),
                    sevenTv = ChatCatalogProviderUpdate(
                        value = mapOf("same" to newGlobal),
                        global = ScopeUpdate.Success(mapOf("same" to newGlobal)),
                        channel = ScopeUpdate.Failed,
                    ),
                    bttv = ChatCatalogProviderUpdate(emptyMap()),
                    ffz = ChatCatalogProviderUpdate(emptyMap()),
                    badges = ChatCatalogProviderUpdate(emptyMap()),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read() = ChatCatalogSnapshot(
                    revision = 1,
                    sevenTv = ScopedEmoteCatalog(
                        global = mapOf("same" to emote("same-global-old", ChatAssetProvider.SEVEN_TV).copy(name = "same", scope = ChatEmoteScope.GLOBAL)),
                        channel = mapOf("same" to oldChannel),
                    ),
                )
                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
            wait = { awaitCancellation() },
        )

        withTimeout(1_000) { while (!repository.state.value.hydrated) delay(1) }
        repository.refresh()
        withTimeout(1_000) {
            while (repository.state.value.snapshot.sevenTv.global["same"] != newGlobal) delay(1)
        }

        assertEquals(oldChannel, repository.state.value.snapshot.sevenTv.channel["same"])
        assertEquals(oldChannel, repository.state.value.snapshot.sevenTv["same"])
        repository.close()
        scope.cancel()
    }

    @Test
    fun sameAliasKeepsLastGoodGlobalWhenChannelBecomesEmpty() = runBlocking {
        val oldGlobal = emote("same-global", ChatAssetProvider.BTTV).copy(
            name = "same",
            scope = ChatEmoteScope.GLOBAL,
        )
        val oldChannel = emote("same-channel", ChatAssetProvider.BTTV).copy(
            name = "same",
            scope = ChatEmoteScope.CHANNEL,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource {
                ChatCatalogLoadResult(
                    twitch = ChatCatalogProviderUpdate(emptyMap()),
                    sevenTv = ChatCatalogProviderUpdate(emptyMap()),
                    bttv = ChatCatalogProviderUpdate(
                        value = mapOf("same" to oldGlobal),
                        global = ScopeUpdate.Failed,
                        channel = ScopeUpdate.Success(emptyMap()),
                    ),
                    ffz = ChatCatalogProviderUpdate(emptyMap()),
                    badges = ChatCatalogProviderUpdate(emptyMap()),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read() = ChatCatalogSnapshot(
                    revision = 1,
                    bttv = ScopedEmoteCatalog(
                        global = mapOf("same" to oldGlobal),
                        channel = mapOf("same" to oldChannel),
                    ),
                )
                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
            wait = { awaitCancellation() },
        )

        withTimeout(1_000) { while (!repository.state.value.hydrated) delay(1) }
        repository.refresh()
        withTimeout(1_000) {
            while (repository.state.value.snapshot.bttv.channel.isNotEmpty()) delay(1)
        }

        assertEquals(oldGlobal, repository.state.value.snapshot.bttv.global["same"])
        assertEquals(oldGlobal, repository.state.value.snapshot.bttv["same"])
        repository.close()
        scope.cancel()
    }

    @Test
    fun scopedCacheRestoresShadowedAliasesAfterRestart() = runBlocking {
        val global = emote("global", ChatAssetProvider.FFZ).copy(name = "same", scope = ChatEmoteScope.GLOBAL)
        val channel = emote("channel", ChatAssetProvider.FFZ).copy(name = "same", scope = ChatEmoteScope.CHANNEL)
        val cached = ChatCatalogSnapshot(
            revision = 5,
            ffz = ScopedEmoteCatalog(global = mapOf("same" to global), channel = mapOf("same" to channel)),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = ChatCatalogRepository(
            scope = scope,
            source = ChatCatalogSource { ChatCatalogLoadResult() },
            cache = object : ChatCatalogCache {
                override suspend fun read() = cached
                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
        )

        withTimeout(1_000) { while (!repository.state.value.hydrated) delay(1) }
        assertEquals(global, repository.state.value.snapshot.ffz.global["same"])
        assertEquals(channel, repository.state.value.snapshot.ffz.channel["same"])
        assertEquals(channel, repository.state.value.snapshot.ffz["same"])
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
                    cheermotes = ChatCatalogProviderUpdate(emptyMap()),
                )
            },
            cache = object : ChatCatalogCache {
                override suspend fun read() = ChatCatalogSnapshot(
                    revision = 1,
                    bttv = ScopedEmoteCatalog(global = mapOf("global" to oldGlobal), channel = mapOf("channel" to oldChannel)),
                )
                override suspend fun write(snapshot: ChatCatalogSnapshot) = Unit
            },
        )
        withTimeout(1_000) { while (!repository.state.value.hydrated) delay(1) }
        repository.refresh()
        withTimeout(1_000) { while (repository.state.value.snapshot.revision < 2) delay(1) }

        assertEquals(oldGlobal, repository.state.value.snapshot.bttv["global"])
        assertTrue("channel" !in repository.state.value.snapshot.bttv.effective)
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
            sevenTv = ScopedEmoteCatalog(global = mapOf("Party" to emote("party", ChatAssetProvider.SEVEN_TV))),
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
            sevenTv = ScopedEmoteCatalog(global = mapOf("cached" to emote("cached", ChatAssetProvider.SEVEN_TV))),
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
                        sevenTv = ScopedEmoteCatalog(global = mapOf("cached" to emote("cached-seven", ChatAssetProvider.SEVEN_TV))),
                        bttv = ScopedEmoteCatalog(global = mapOf("cached" to cachedBttv)),
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
                    sevenTv = ChatCatalogProviderUpdate(
                        value = emptyMap(),
                        global = ScopeUpdate.Success(emptyMap()),
                        channel = ScopeUpdate.Success(emptyMap()),
                    ),
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
                        sevenTv = ScopedEmoteCatalog(
                            legacyCombined = mapOf(
                                "stale" to emote("stale", ChatAssetProvider.SEVEN_TV)
                                    .copy(scope = ChatEmoteScope.LEGACY_COMBINED),
                            ),
                        ),
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

        assertTrue(repository.state.value.snapshot.sevenTv.global.isEmpty())
        assertTrue(repository.state.value.snapshot.sevenTv.channel.isEmpty())
        assertTrue(repository.state.value.snapshot.sevenTv.legacyCombined.isEmpty())
        assertTrue("stale" !in repository.state.value.snapshot.sevenTv)
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
                    sevenTv = ScopedEmoteCatalog(global = mapOf("old" to oldSevenTv)),
                    bttv = ScopedEmoteCatalog(global = mapOf("old-bttv" to oldBttv)),
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
                        cheermotes = ChatCatalogProviderUpdate(emptyMap()),
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
