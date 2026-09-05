package com.github.andreyasadchy.xtra.ui.main

internal const val FULL_EVENTSUB_RECONCILE_INTERVAL_MS = 15 * 60 * 1000L
internal const val PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS = 60 * 1000L
internal const val NETWORK_RETRY_INTERVAL_MS = 60 * 1000L
internal const val RATE_LIMIT_RETRY_INTERVAL_MS = 30 * 1000L
internal const val MIN_RATE_LIMIT_RETRY_DELAY_MS = 1_000L
internal const val RATE_LIMIT_SAFETY_MARGIN_MS = 1_000L

internal fun reconcileIntervalMs(
    desiredChannelCount: Int,
    activeEventSubChannelCount: Int,
    eventSubConnected: Boolean,
    eventSubSuspended: Boolean,
): Long {
    val completeCoverage =
        eventSubConnected &&
            !eventSubSuspended &&
            desiredChannelCount > 0 &&
            activeEventSubChannelCount >= desiredChannelCount
    return if (completeCoverage) {
        FULL_EVENTSUB_RECONCILE_INTERVAL_MS
    } else {
        PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS
    }
}

internal fun liveNotificationFailureRetryDelayMs(
    statusCode: Int,
    rateLimitResetEpochSeconds: Long?,
    nowEpochMs: Long,
): Long = rateLimitResetEpochSeconds?.let {
    (it * 1_000L - nowEpochMs + RATE_LIMIT_SAFETY_MARGIN_MS)
        .coerceAtLeast(MIN_RATE_LIMIT_RETRY_DELAY_MS)
} ?: if (statusCode == 429) {
    RATE_LIMIT_RETRY_INTERVAL_MS
} else {
    NETWORK_RETRY_INTERVAL_MS
}

internal fun applyHelixMinimumDelay(
    coverageDelayMs: Long,
    helixMinimumDelayMs: Long?,
): Long = maxOf(coverageDelayMs, helixMinimumDelayMs ?: 0L)

internal fun shouldSkipLiveNotificationWorker(hasHealthyRealtimeOwner: Boolean): Boolean =
    hasHealthyRealtimeOwner
