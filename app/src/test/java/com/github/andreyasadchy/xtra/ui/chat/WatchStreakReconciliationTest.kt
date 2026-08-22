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
    fun ordinaryWatchDoesNotCreateStreakInvalidation() {
        assertNull(
            watchStreakInvalidationForPointsEarned(
                activeChannelId = "channel-100",
                messageChannelId = "channel-100",
                reasonCode = "WATCH",
                currentCount = 4,
            ),
        )
    }

    @Test
    fun watchStreakForActiveChannelCapturesPreEventCount() {
        assertEquals(
            WatchStreakReconciliation(
                source = WatchStreakReconciliationSource.POINTS_EARNED,
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
            source = WatchStreakReconciliationSource.POINTS_EARNED,
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
            source = WatchStreakReconciliationSource.POINTS_EARNED,
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
            source = WatchStreakReconciliationSource.POINTS_EARNED,
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
            source = WatchStreakReconciliationSource.POINTS_EARNED,
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
}
