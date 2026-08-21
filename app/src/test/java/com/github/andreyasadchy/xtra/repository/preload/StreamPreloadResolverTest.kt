package com.github.andreyasadchy.xtra.repository.preload

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreloadResolverTest {

    @Test
    fun playbackJoinsAnInFlightResolveWithoutStartingAnotherRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val resolver = resolver(scope, activeStreams = setOf("stream"))

        val preload = scope.async {
            resolver.preload("Creator", "stream", "config") {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                "url"
            }
        }
        started.await()

        resolver.promoteForPlayback("creator", "config")
        val playback = scope.async(start = CoroutineStart.UNDISPATCHED) { resolver.joinForPlayback("creator", "config") }
        release.complete(Unit)

        assertEquals("url", preload.await())
        assertEquals("url", playback.await())
        assertEquals(1, calls.get())
        scope.cancel()
    }

    @Test
    fun promotedFlightSurvivesBrowsingOwnerCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val resolver = resolver(scope, activeStreams = setOf("stream"))

        val preload = scope.async {
            resolver.preload("creator", "stream", "config") {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                "url"
            }
        }
        started.await()
        assertTrue(resolver.promoteForPlayback("creator", "config"))

        preload.cancel()
        val playback = scope.async(start = CoroutineStart.UNDISPATCHED) { resolver.joinForPlayback("creator", "config") }
        release.complete(Unit)

        assertEquals("url", playback.await())
        assertEquals(1, calls.get())
        scope.cancel()
    }

    @Test
    fun promotedFlightSurvivesBrowsingLifecycleCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val resolver = resolver(scope, activeStreams = setOf("stream"))

        val preload = scope.async {
            resolver.preload("creator", "stream", "config") {
                calls.incrementAndGet()
                started.complete(Unit)
                release.await()
                "url"
            }
        }
        started.await()
        assertTrue(resolver.promoteForPlayback("creator", "config"))

        cancelBrowsingFlights(resolver, configurationFingerprint = "config")
        val playback = scope.async(start = CoroutineStart.UNDISPATCHED) {
            resolver.joinForPlayback("creator", "config")
        }
        release.complete(Unit)

        assertEquals("url", preload.await())
        assertEquals("url", playback.await())
        assertEquals(1, calls.get())
        scope.cancel()
    }

    @Test
    fun promotedQueuedFlightBypassesPreloadGatesAfterPlaybackTakesOver() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val activeStreams = mutableSetOf("first", "selected")
        var canStart = true
        val calls = AtomicInteger()
        val resolver = resolver(
            scope = scope,
            activeStreams = activeStreams,
            maxConcurrency = 1,
            canStart = { canStart },
        )

        val first = scope.async {
            resolver.preload("first", "first", "config") {
                calls.incrementAndGet()
                firstStarted.complete(Unit)
                releaseFirst.await()
                "first-url"
            }
        }
        firstStarted.await()
        assertTrue(resolver.promoteForPlayback("first", "config"))
        val selected = scope.async {
            resolver.preload("selected", "selected", "config") {
                calls.incrementAndGet()
                "selected-url"
            }
        }
        withTimeout(1_000L) {
            while (!resolver.hasFlight("selected", "config")) kotlinx.coroutines.delay(1)
        }

        assertTrue(resolver.promoteForPlayback("selected", "config"))
        activeStreams.remove("selected")
        canStart = false
        resolver.cancelObsolete("config", activeLogins = setOf("first"))
        resolver.cancelAll(keepLogins = setOf("selected"), configurationFingerprint = "config")
        val playback = scope.async(start = CoroutineStart.UNDISPATCHED) { resolver.joinForPlayback("selected", "config") }
        assertTrue(runCatching { first.await() }.isFailure)
        assertEquals("selected-url", selected.await())
        assertEquals("selected-url", playback.await())
        assertEquals(2, calls.get())
        scope.cancel()
    }

    @Test
    fun aQueuedResolveIsCancelledBeforeItReachesTheNetwork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val activeStreams = mutableSetOf("first", "second")
        val resolver = resolver(scope, activeStreams, maxConcurrency = 1)

        val first = scope.async {
            resolver.preload("first", "first", "config") {
                calls.incrementAndGet()
                firstStarted.complete(Unit)
                releaseFirst.await()
                "first-url"
            }
        }
        firstStarted.await()
        val second = scope.async {
            resolver.preload("second", "second", "config") {
                calls.incrementAndGet()
                "second-url"
            }
        }
        withTimeout(1_000L) {
            while (!resolver.hasFlight("second", "config")) kotlinx.coroutines.delay(1)
        }

        activeStreams.remove("second")
        resolver.cancelObsolete("config", activeStreams)
        releaseFirst.complete(Unit)

        assertEquals("first-url", first.await())
        assertTrue(runCatching { second.await() }.isFailure)
        assertEquals(1, calls.get())
        scope.cancel()
    }

    @Test
    fun cancellingTheLastOwnerStopsAnActiveResolve() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val resolver = resolver(scope, activeStreams = setOf("stream"))

        val preload = scope.async {
            resolver.preload("creator", "stream", "config") {
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    cancelled.complete(Unit)
                }
                "never"
            }
        }
        started.await()
        resolver.cancelObsolete("config", emptySet())

        withTimeout(1_000L) { cancelled.await() }
        assertTrue(runCatching { preload.await() }.isFailure)
        scope.cancel()
    }

    @Test
    fun failedResolvesHaveAShortCooldown() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val resolver = resolver(scope, activeStreams = setOf("stream"), failureBackoffMs = 10_000L)

        assertNull(resolver.preload("creator", "stream", "config") {
            calls.incrementAndGet()
            error("offline")
        })
        assertNull(resolver.preload("creator", "stream", "config") {
            calls.incrementAndGet()
            "unexpected"
        })
        assertEquals(1, calls.get())
        scope.cancel()
    }

    @Test
    fun selectedFlightSurvivesCancellationOfOtherFlights() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val release = CompletableDeferred<Unit>()
        val resolver = resolver(scope, activeStreams = setOf("keep", "drop"), maxConcurrency = 2)
        val keep = scope.async {
            resolver.preload("keep", "keep", "config") {
                release.await()
                "keep-url"
            }
        }
        val drop = scope.async {
            resolver.preload("drop", "drop", "config") {
                CompletableDeferred<Unit>().await()
                "drop-url"
            }
        }
        withTimeout(1_000L) {
            while (!resolver.hasFlight("keep", "config") || !resolver.hasFlight("drop", "config")) {
                kotlinx.coroutines.delay(1)
            }
        }

        resolver.cancelObsolete("config", activeLogins = emptySet(), keepLogins = setOf("keep"))
        release.complete(Unit)

        assertEquals("keep-url", keep.await())
        assertTrue(runCatching { drop.await() }.isFailure)
        scope.cancel()
    }

    @Test
    fun playbackDoesNotJoinAFlightFromAnOldConfiguration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val resolver = resolver(scope, activeStreams = setOf("stream"))
        val oldFlight = scope.async {
            resolver.preload("creator", "stream", "old-config") {
                started.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    cancelled.complete(Unit)
                }
                "old-url"
            }
        }
        started.await()

        resolver.cancelObsolete("new-config", activeLogins = setOf("stream"))

        assertNull(resolver.join("creator", "new-config"))
        withTimeout(1_000L) { cancelled.await() }
        assertTrue(runCatching { oldFlight.await() }.isFailure)
        scope.cancel()
    }

    private fun resolver(
        scope: CoroutineScope,
        activeStreams: Set<String>,
        maxConcurrency: Int = 2,
        failureBackoffMs: Long = 5_000L,
        canStart: () -> Boolean = { true },
    ) = StreamPreloadResolver(
        scope = scope,
        maxConcurrency = maxConcurrency,
        failureBackoffMs = failureBackoffMs,
        elapsedRealtimeMs = { 0L },
        canStart = canStart,
        isEligible = { it in activeStreams },
    )
}
