package com.github.andreyasadchy.xtra.ui.inbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Cancellation ends the operation; it must never become an inbox error or a failed send. */
internal suspend inline fun <T> runCatchingInboxRequest(block: () -> T): Result<T> = try {
    val value = block()
    currentCoroutineContext().ensureActive()
    Result.success(value)
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    currentCoroutineContext().ensureActive()
    Result.failure(error)
}
