package com.github.andreyasadchy.xtra.ui.main

import com.github.andreyasadchy.xtra.repository.TwitchApiException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationRunnerTest {

    @Test
    fun rateLimitRetryWaitIgnoresWakeSignal() = runBlocking {
        val wakeSignal = Channel<Unit>(Channel.CONFLATED)

        val completedEarly = withTimeoutOrNull(50L) {
            val retry = launch {
                awaitLiveNotificationRetry(
                    retryDelayMs = 200L,
                    interruptible = false,
                    wakeSignal = wakeSignal,
                )
            }
            wakeSignal.trySend(Unit)
            retry.join()
            true
        }

        assertNull(completedEarly)
    }

    @Test
    fun ordinaryRetryCanBeWoken() = runBlocking {
        val wakeSignal = Channel<Unit>(Channel.CONFLATED)

        val completed = withTimeoutOrNull(200L) {
            val retry = launch {
                awaitLiveNotificationRetry(
                    retryDelayMs = 2_000L,
                    interruptible = true,
                    wakeSignal = wakeSignal,
                )
            }
            wakeSignal.trySend(Unit)
            retry.join()
            true
        }

        assertTrue(completed == true)
    }

    @Test
    fun onlyNonRateLimitFailuresAreInterruptible() {
        assertFalse(isLiveNotificationRetryInterruptible(TwitchApiException(429, null, message = "rate limited")))
        assertFalse(isLiveNotificationRetryInterruptible(TwitchApiException(500, 123L, message = "server error")))
        assertTrue(isLiveNotificationRetryInterruptible(TwitchApiException(500, null, message = "server error")))
    }
}
