package com.github.andreyasadchy.xtra.repository.auth

import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.repository.MissingAuthenticationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class IntegrityAwareGqlExecutorTest {
    @Test
    fun missingIdentityIsAcquiredBeforeTheFirstRequest() = runBlocking {
        val identity = identity("fresh")
        var current: GeckoGqlRequest? = null
        var refreshCount = 0
        val sentHeaders = mutableListOf<Map<String, String>>()
        val executor = executor(
            current = { current },
            refresh = {
                refreshCount++
                current = request(identity)
                true
            },
        )

        val result = executor.execute(
            fallbackHeaders = request(identity).headers,
            requireActiveWebSession = true,
            isFailedIntegrityCheck = { it == "failed" },
            send = { headers ->
                sentHeaders += headers
                "ok"
            },
        )

        assertEquals("ok", result)
        assertEquals(1, refreshCount)
        assertEquals(listOf(request(identity).headers), sentHeaders)
    }

    @Test
    fun staleIdentityRefreshesAndRetriesExactlyOnce() = runBlocking {
        val stale = identity("stale")
        val fresh = identity("fresh")
        var current: GeckoGqlRequest? = request(stale)
        var refreshCount = 0
        var sendCount = 0
        val executor = executor(
            current = { current },
            refresh = {
                refreshCount++
                current = request(fresh)
                true
            },
            invalidate = { identity ->
                if (current?.identity == identity) {
                    current = null
                    true
                } else {
                    false
                }
            },
        )

        val result = executor.execute(
            fallbackHeaders = request(stale).headers,
            requireActiveWebSession = true,
            isFailedIntegrityCheck = { it == "failed" },
            send = { headers ->
                sendCount++
                if (headers == request(stale).headers) "failed" else "ok"
            },
        )

        assertEquals("ok", result)
        assertEquals(2, sendCount)
        assertEquals(1, refreshCount)
    }

    @Test
    fun refreshFailureAfterWebSessionDisappearsRequiresReauthentication() = runBlocking {
        val stale = identity("stale")
        var current: GeckoGqlRequest? = request(stale)
        var webSessionActive = true
        val executor = executor(
            isWebSessionActive = { webSessionActive },
            current = { current },
            refresh = {
                webSessionActive = false
                current = null
                false
            },
            invalidate = { identity ->
                if (current?.identity == identity) {
                    current = null
                    true
                } else {
                    false
                }
            },
        )

        var requiresReauthentication = false
        try {
            executor.execute(
                fallbackHeaders = request(stale).headers,
                requireActiveWebSession = true,
                isFailedIntegrityCheck = { it == "failed" },
                send = { "failed" },
            )
        } catch (_: MissingAuthenticationException) {
            requiresReauthentication = true
        }

        assertTrue(requiresReauthentication)
    }

    @Test
    fun lateStaleResponsePreservesNewerIdentity() = runBlocking {
        val stale = identity("stale")
        val fresh = identity("fresh")
        var current: GeckoGqlRequest? = request(stale)
        val refreshCount = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val refreshInstalled = CompletableDeferred<Unit>()
        val sendCount = AtomicInteger()
        val executor = executor(
            current = { current },
            refresh = {
                refreshCount.incrementAndGet()
                current = request(fresh)
                refreshInstalled.complete(Unit)
                true
            },
            invalidate = { identity ->
                if (current?.identity == identity) {
                    current = null
                    true
                } else {
                    false
                }
            },
        )
        val execute = suspend {
            executor.execute(
                fallbackHeaders = request(stale).headers,
                requireActiveWebSession = true,
                isFailedIntegrityCheck = { it == "failed" },
                send = { headers ->
                    when (sendCount.incrementAndGet()) {
                        1 -> {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                            "failed"
                        }
                        2 -> {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                            "failed"
                        }
                        else -> "ok"
                    }
                },
            )
        }

        val first = async { execute() }
        firstStarted.await()
        val second = async { execute() }
        secondStarted.await()
        releaseFirst.complete(Unit)
        refreshInstalled.await()
        releaseSecond.complete(Unit)

        assertEquals("ok", first.await())
        assertEquals("ok", second.await())
        assertEquals(1, refreshCount.get())
        assertEquals(fresh, current?.identity)
    }

    private fun executor(
        isWebSessionActive: () -> Boolean = { true },
        current: () -> GeckoGqlRequest?,
        refresh: suspend () -> Boolean,
        invalidate: (GeckoGqlIdentity) -> Boolean = { true },
    ) = IntegrityAwareGqlExecutor<String>(
        isWebSessionActive = isWebSessionActive,
        isCurrentAuthorization = { it == "OAuth token-stale" || it == "OAuth token-fresh" },
        currentRequest = current,
        refresh = refresh,
        invalidateIfCurrent = invalidate,
    )

    private fun request(identity: GeckoGqlIdentity) = GeckoGqlRequest(
        identity = identity,
        headers = mapOf(C.HEADER_TOKEN to identity.authorization),
    )

    private fun identity(suffix: String) = GeckoGqlIdentity(
        authorization = "OAuth token-$suffix",
        clientId = "client-$suffix",
        clientIntegrity = "integrity-$suffix",
        xDeviceId = "device-$suffix",
        clientSessionId = null,
        userId = "user-1",
        authTokenFingerprint = "fingerprint-$suffix",
        capturedAt = System.currentTimeMillis(),
    )
}
