package com.github.andreyasadchy.xtra.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ExpiringSingleFlightCacheTest {
    @Test
    fun cachedValueAvoidsAnotherLoadUntilTtlExpires() = runBlocking {
        var now = 1_000L
        val loads = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cache = ExpiringSingleFlightCache<String, String>(
            ttlMillis = 100L,
            scope = scope,
            nowMillis = { now },
        )

        assertEquals("first", cache.get("channel") { loads.incrementAndGet(); "first" })
        assertEquals("first", cache.get("channel") { loads.incrementAndGet(); "second" })
        assertEquals(1, loads.get())

        now += 100L
        assertEquals("second", cache.get("channel") { loads.incrementAndGet(); "second" })
        assertEquals(2, loads.get())
        scope.cancel()
    }

    @Test
    fun concurrentLoadsForOneChannelShareOneRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cache = ExpiringSingleFlightCache<String, String>(100L, scope)

        val first = async {
            cache.get("channel") {
                loads.incrementAndGet()
                started.complete(Unit)
                release.await()
                "result"
            }
        }
        withTimeout(2_000L) { started.await() }
        val second = async {
            cache.get("channel") { error("duplicate request") }
        }

        release.complete(Unit)
        assertEquals("result", first.await())
        assertEquals("result", second.await())
        assertEquals(1, loads.get())
        scope.cancel()
    }

    @Test
    fun differentChannelsDoNotWaitOnOneAnother() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cache = ExpiringSingleFlightCache<String, String>(100L, scope)

        val first = async {
            cache.get("first") {
                firstStarted.complete(Unit)
                release.await()
                "first"
            }
        }
        val second = async {
            cache.get("second") {
                secondStarted.complete(Unit)
                release.await()
                "second"
            }
        }

        withTimeout(2_000L) {
            firstStarted.await()
            secondStarted.await()
        }
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("second", second.await())
        scope.cancel()
    }

    @Test
    fun forceBypassesAHealthyCachedValue() = runBlocking {
        val loads = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cache = ExpiringSingleFlightCache<String, String>(100L, scope)

        cache.get("channel") { "value-${loads.incrementAndGet()}" }
        assertEquals("value-2", cache.get("channel", force = true) { "value-${loads.incrementAndGet()}" })
        assertEquals(2, loads.get())
        scope.cancel()
    }
}
