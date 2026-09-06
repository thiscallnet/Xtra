package com.github.andreyasadchy.xtra.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationPolicyTest {

    @Test
    fun completeEventSubCoverageUsesTheLongSafetyInterval() {
        assertEquals(
            FULL_EVENTSUB_RECONCILE_INTERVAL_MS,
            reconcileIntervalMs(
                desiredChannelCount = 10,
                activeEventSubChannelCount = 10,
                eventSubConnected = true,
                eventSubSuspended = false,
            ),
        )
        assertEquals(
            FULL_EVENTSUB_RECONCILE_INTERVAL_MS,
            reconcileIntervalMs(
                desiredChannelCount = 10,
                activeEventSubChannelCount = 12,
                eventSubConnected = true,
                eventSubSuspended = false,
            ),
        )
    }

    @Test
    fun partialOrUnavailableCoverageUsesTheShortFallbackInterval() {
        assertEquals(
            PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS,
            reconcileIntervalMs(10, 5, eventSubConnected = true, eventSubSuspended = false),
        )
        assertEquals(
            PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS,
            reconcileIntervalMs(10, 0, eventSubConnected = false, eventSubSuspended = false),
        )
        assertEquals(
            PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS,
            reconcileIntervalMs(10, 10, eventSubConnected = false, eventSubSuspended = false),
        )
        assertEquals(
            PARTIAL_EVENTSUB_RECONCILE_INTERVAL_MS,
            reconcileIntervalMs(10, 10, eventSubConnected = true, eventSubSuspended = true),
        )
        assertEquals(
            NO_CHANNELS_RECONCILE_INTERVAL_MS,
            reconcileIntervalMs(0, 0, eventSubConnected = false, eventSubSuspended = false),
        )
    }

    @Test
    fun failureRetryUsesShortFallbackUnlessTwitchProvidesRateLimitTiming() {
        assertEquals(
            NETWORK_RETRY_INTERVAL_MS,
            liveNotificationFailureRetryDelayMs(
                statusCode = 500,
                rateLimitResetEpochSeconds = null,
                nowEpochMs = 1_000_000L,
            ),
        )
        assertEquals(
            RATE_LIMIT_RETRY_INTERVAL_MS,
            liveNotificationFailureRetryDelayMs(
                statusCode = 429,
                rateLimitResetEpochSeconds = null,
                nowEpochMs = 1_000_000L,
            ),
        )
        assertEquals(
            11_000L,
            liveNotificationFailureRetryDelayMs(
                statusCode = 500,
                rateLimitResetEpochSeconds = 1_010L,
                nowEpochMs = 1_000_000L,
            ),
        )
    }

    @Test
    fun rateLimitMinimumDelayTakesPrecedenceWithoutShorteningNormalCadence() {
        assertEquals(
            120_000L,
            applyHelixMinimumDelay(
                coverageDelayMs = 60_000L,
                helixMinimumDelayMs = 120_000L,
            ),
        )
        assertEquals(
            FULL_EVENTSUB_RECONCILE_INTERVAL_MS,
            applyHelixMinimumDelay(
                coverageDelayMs = FULL_EVENTSUB_RECONCILE_INTERVAL_MS,
                helixMinimumDelayMs = RATE_LIMIT_RETRY_INTERVAL_MS,
            ),
        )
    }

    @Test
    fun workerSkipsOnlyWithAHealthyRealtimeOwner() {
        assertTrue(shouldSkipLiveNotificationWorker(hasHealthyRealtimeOwner = true))
        assertFalse(shouldSkipLiveNotificationWorker(hasHealthyRealtimeOwner = false))
    }

    @Test
    fun failedNetworkWakeRegistrationKeepsOfflineRecoveryBounded() {
        assertEquals(
            NETWORK_RETRY_INTERVAL_MS,
            offlineLiveNotificationRetryDelayMs(
                cachedChannelCount = 0,
                networkWakeAvailable = false,
            ),
        )
        assertEquals(
            NO_CHANNELS_RECONCILE_INTERVAL_MS,
            offlineLiveNotificationRetryDelayMs(
                cachedChannelCount = 0,
                networkWakeAvailable = true,
            ),
        )
    }

    @Test
    fun coverageExposesCompleteAndPartialStates() {
        assertTrue(
            LiveEventSubCoverage(10, 10, connected = true, suspended = false).complete,
        )
        assertTrue(
            LiveEventSubCoverage(10, 5, connected = true, suspended = false).partial,
        )
        assertFalse(
            LiveEventSubCoverage(10, 0, connected = false, suspended = false).partial,
        )
    }

    @Test
    fun coverageSeparatesIdleFromIncompleteMonitoring() {
        assertEquals(
            LiveNotificationCoverageState.NO_CHANNELS,
            liveNotificationCoverageState(0, 0, eventSubConnected = false, eventSubSuspended = false),
        )
        assertEquals(
            LiveNotificationCoverageState.PARTIAL,
            liveNotificationCoverageState(10, 8, eventSubConnected = true, eventSubSuspended = false),
        )
        assertEquals(
            LiveNotificationCoverageState.COMPLETE,
            liveNotificationCoverageState(10, 10, eventSubConnected = true, eventSubSuspended = false),
        )
    }

    @Test
    fun notificationUserSyncWaitsUntilTheActualNextMeaningfulDeadline() {
        val now = 100_000L
        assertEquals(0L, nextNotificationUserSyncDelayMs(0L, 0L, now))
        assertEquals(
            LIVE_NOTIFICATION_FOLLOW_SYNC_RETRY_INTERVAL_MS,
            nextNotificationUserSyncDelayMs(
                now - LIVE_NOTIFICATION_FOLLOW_SYNC_INTERVAL_MS,
                now,
                now,
            ),
        )
        assertEquals(
            LIVE_NOTIFICATION_FOLLOW_SYNC_INTERVAL_MS,
            nextNotificationUserSyncDelayMs(now, 0L, now),
        )
    }
}
