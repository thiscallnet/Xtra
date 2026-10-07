package com.github.andreyasadchy.xtra.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SingleFlightRecoveryTest {
    @Test
    fun nullLoaderRunsOnceAndFailedFlightsCanRecover() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val cache = ExpiringSingleFlightCache<String, String>(10_000L, scope, Semaphore(1), maxEntries = 2)
            var calls = 0
            assertNull(withTimeout(5_000L) { cache.get("null") { calls++; null } })
            assertEquals(1, calls)
            try {
                cache.get("failed") { error("Injected network failure") }
            } catch (_: IllegalStateException) {
                // The next caller must not inherit this failed flight.
            }
            assertEquals("recovered", withTimeout(5_000L) { cache.get("failed") { "recovered" } })
            cache.get("second") { "second" }
            cache.get("third") { "third" }
            assertEquals("reloaded", cache.get("failed") { "reloaded" })
        } finally {
            scope.cancel()
        }
    }
}
