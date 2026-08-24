package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.ui.WatchStreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchStreakReconciliationTest {
    @Test
    fun retriesUseTheExtendedBoundedDelaySchedule() {
        assertEquals(
            listOf(3_000L, 10_000L),
            watchStreakReconciliationRetryDelaysMillis,
        )
    }

    @Test
    fun periodicRefreshRunsWithoutAWatchStreakNotice() {
        assertEquals(180_000L, WATCH_STREAK_REFRESH_INTERVAL_MILLIS)
        assertTrue(
            shouldContinueWatchStreakRefresh(
                expectedSession = 7L,
                currentSession = 7L,
                expectedChannelId = "channel-100",
                currentChannelId = "channel-100",
                expectedChannelLogin = "channel",
                currentChannelLogin = "channel",
                expectedUserId = "user-1",
                currentUserId = "user-1",
                gqlToken = "OAuth token",
            ),
        )
    }

    @Test
    fun periodicRefreshStopsAfterChannelOrSessionChanges() {
        assertFalse(
            shouldContinueWatchStreakRefresh(
                expectedSession = 7L,
                currentSession = 8L,
                expectedChannelId = "channel-100",
                currentChannelId = "channel-100",
                expectedChannelLogin = "channel",
                currentChannelLogin = "channel",
                expectedUserId = "user-1",
                currentUserId = "user-1",
                gqlToken = "OAuth token",
            ),
        )
        assertFalse(
            shouldContinueWatchStreakRefresh(
                expectedSession = 7L,
                currentSession = 7L,
                expectedChannelId = "channel-100",
                currentChannelId = "channel-200",
                expectedChannelLogin = "channel",
                currentChannelLogin = "other-channel",
                expectedUserId = "user-1",
                currentUserId = "user-1",
                gqlToken = "OAuth token",
            ),
        )
        assertFalse(
            shouldContinueWatchStreakRefresh(
                expectedSession = 7L,
                currentSession = 7L,
                expectedChannelId = "channel-100",
                currentChannelId = "channel-100",
                expectedChannelLogin = "channel",
                currentChannelLogin = "channel",
                expectedUserId = "user-1",
                currentUserId = null,
                gqlToken = null,
            ),
        )
    }

    @Test
    fun ordinaryWatchCreatesAReconciliation() {
        assertEquals(
            WatchStreakReconciliation(
                source = WatchStreakReconciliationSource.WATCH_CREDIT,
                countBeforeEvent = 4,
            ),
            watchStreakInvalidationForPointsEarned(
                activeChannelId = "channel-100",
                messageChannelId = "channel-100",
                reasonCode = "WATCH",
                currentCount = 4,
            ),
        )
    }

    @Test
    fun watchCreditReconciliationIsThrottled() {
        assertNull(
            watchStreakInvalidationForPointsEarned(
                activeChannelId = "channel-100",
                messageChannelId = "channel-100",
                reasonCode = "WATCH",
                currentCount = 4,
                nowMs = 125_000L,
                lastReconciliationAtMs = 100_000L,
            ),
        )
        assertEquals(
            WatchStreakReconciliation(
                source = WatchStreakReconciliationSource.WATCH_CREDIT,
                countBeforeEvent = 4,
            ),
            watchStreakInvalidationForPointsEarned(
                activeChannelId = "channel-100",
                messageChannelId = "channel-100",
                reasonCode = "WATCH",
                currentCount = 4,
                nowMs = 130_000L,
                lastReconciliationAtMs = 100_000L,
            ),
        )
    }

    @Test
    fun watchStreakForActiveChannelCapturesPreEventCount() {
        assertEquals(
            WatchStreakReconciliation(
                source = WatchStreakReconciliationSource.WATCH_STREAK_CREDIT,
                countBeforeEvent = 4,
            ),
            watchStreakInvalidationForPointsEarned(
                activeChannelId = "channel-100",
                messageChannelId = "channel-100",
                reasonCode = "watch_streak",
                currentCount = 4,
            ),
        )
    }

    @Test
    fun watchStreakForAnotherChannelDoesNotCreateInvalidation() {
        assertNull(
            watchStreakInvalidationForPointsEarned(
                activeChannelId = "channel-100",
                messageChannelId = "channel-200",
                reasonCode = "WATCH_STREAK",
                currentCount = 4,
            ),
        )
    }

    @Test
    fun staleFirstSnapshotAndFreshSecondSnapshotUpdateState() {
        val reconciliation = WatchStreakReconciliation(
            source = WatchStreakReconciliationSource.WATCH_STREAK_CREDIT,
            countBeforeEvent = 4,
        )
        assertTrue(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 4, retryAttempt = 0))

        val stale = mergeWatchStreakState(
            previous = WatchStreak(streakCount = 4),
            incoming = WatchStreak(streakCount = 4),
        )
        assertEquals(4, stale.streakCount)

        val updated = mergeWatchStreakState(
            previous = stale,
            incoming = WatchStreak(streakCount = 5),
        )

        assertEquals(5, updated.streakCount)
        assertFalse(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 5, retryAttempt = 1))
    }

    @Test
    fun unchangedSnapshotGetsTwoBoundedRetries() {
        val reconciliation = WatchStreakReconciliation(
            source = WatchStreakReconciliationSource.WATCH_STREAK_CREDIT,
            countBeforeEvent = 4,
        )

        assertTrue(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 4, retryAttempt = 0))
        assertTrue(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 4, retryAttempt = 1))
        assertFalse(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 4, retryAttempt = 2))
        assertEquals("unchanged", watchStreakSnapshotStatus(reconciliation, responseCount = 4))
    }

    @Test
    fun lowerStaleSnapshotStillGetsBoundedRetry() {
        val reconciliation = WatchStreakReconciliation(
            source = WatchStreakReconciliationSource.WATCH_STREAK_CREDIT,
            countBeforeEvent = 4,
        )

        assertTrue(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 3, retryAttempt = 0))
        assertTrue(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 3, retryAttempt = 1))
        assertFalse(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 3, retryAttempt = 2))
        assertEquals("stale", watchStreakSnapshotStatus(reconciliation, responseCount = 3))
    }

    @Test
    fun snapshotStatusDistinguishesAdvancedAndMissingResponses() {
        val reconciliation = WatchStreakReconciliation(
            source = WatchStreakReconciliationSource.WATCH_STREAK_CREDIT,
            countBeforeEvent = 4,
        )

        assertEquals("advanced", watchStreakSnapshotStatus(reconciliation, responseCount = 5))
        assertEquals("missing", watchStreakSnapshotStatus(reconciliation, responseCount = null))
    }

    @Test
    fun staleGraphQlCannotRollBackNewerLiveStreak() {
        val updated = mergeWatchStreakState(
            previous = WatchStreak(streakCount = 5, pointsAwarded = 300),
            incoming = WatchStreak(streakCount = 4),
        )

        assertEquals(5, updated.streakCount)
        assertEquals(300, updated.pointsAwarded)
    }

    @Test
    fun ordinaryWatchDoesNotStartAdvancementRetries() {
        val reconciliation = WatchStreakReconciliation(
            source = WatchStreakReconciliationSource.WATCH_CREDIT,
            countBeforeEvent = 37,
        )

        assertFalse(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = 37, retryAttempt = 0))
        assertFalse(shouldRetryWatchStreakReconciliation(reconciliation, responseCount = null, retryAttempt = 0))
    }

    @Test
    fun watchStreakCreditCanBypassOrdinaryWatchThrottle() {
        assertEquals(
            WatchStreakReconciliationSource.WATCH_STREAK_CREDIT,
            watchStreakInvalidationForPointsEarned(
                activeChannelId = "channel-100",
                messageChannelId = "channel-100",
                reasonCode = "WATCH_STREAK",
                currentCount = 37,
                nowMs = 110_000L,
                lastReconciliationAtMs = 100_000L,
            )?.source,
        )
    }

    @Test
    fun queuedEventSupersedesPeriodicRefreshWithoutBeingDropped() {
        val queue = WatchStreakReconciliationQueue<WatchStreakReconciliation> { it }
        val periodic = WatchStreakReconciliation(WatchStreakReconciliationSource.PERIODIC, null)
        val event = WatchStreakReconciliation(WatchStreakReconciliationSource.WATCH_STREAK_CREDIT, 37)

        queue.enqueue(periodic)
        queue.enqueue(event)

        assertEquals(event, queue.take())
        assertNull(queue.take())
    }

    @Test
    fun liveNotificationTakesPrecedenceOverQueuedWatchCredit() {
        val queue = WatchStreakReconciliationQueue<WatchStreakReconciliation> { it }
        val watchCredit = WatchStreakReconciliation(WatchStreakReconciliationSource.WATCH_CREDIT, 37)
        val liveNotification = WatchStreakReconciliation(
            source = WatchStreakReconciliationSource.LIVE_NOTIFICATION,
            countBeforeEvent = 37,
            observedLiveCount = 38,
        )

        queue.enqueue(watchCredit)
        queue.enqueue(liveNotification)

        assertEquals(liveNotification, queue.take())
    }
}
