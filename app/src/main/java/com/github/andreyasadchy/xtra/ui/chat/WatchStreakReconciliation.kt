package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.ui.WatchStreak

internal enum class WatchStreakReconciliationSource {
    PERIODIC,
    WATCH_CREDIT,
    WATCH_STREAK_CREDIT,
    LIVE_NOTIFICATION,
}

internal data class WatchStreakReconciliation(
    val source: WatchStreakReconciliationSource,
    val countBeforeEvent: Int?,
    val observedLiveCount: Int? = null,
)

internal val watchStreakReconciliationRetryDelaysMillis = listOf(
    3_000L,
    10_000L,
)

internal const val WATCH_STREAK_REFRESH_INTERVAL_MILLIS = 3 * 60 * 1_000L
internal const val WATCH_STREAK_EVENT_RECONCILIATION_THROTTLE_MILLIS = 30_000L

internal fun watchStreakReconciliationPriority(
    source: WatchStreakReconciliationSource,
): Int = when (source) {
    WatchStreakReconciliationSource.PERIODIC -> 0
    WatchStreakReconciliationSource.WATCH_CREDIT -> 1
    WatchStreakReconciliationSource.WATCH_STREAK_CREDIT -> 2
    WatchStreakReconciliationSource.LIVE_NOTIFICATION -> 3
}

/** Coalesces requests while retaining the strongest and newest invalidation. */
internal class WatchStreakReconciliationQueue<T>(
    private val reconciliationOf: (T) -> WatchStreakReconciliation,
) {
    private var pending: T? = null

    @Synchronized
    fun enqueue(request: T) {
        val current = pending
        if (current == null ||
            watchStreakReconciliationPriority(reconciliationOf(request).source) >=
                watchStreakReconciliationPriority(reconciliationOf(current).source)
        ) {
            pending = request
        }
    }

    @Synchronized
    fun take(): T? = pending.also { pending = null }

    @Synchronized
    fun hasHigherPriorityThan(reconciliation: WatchStreakReconciliation): Boolean =
        pending?.let {
            watchStreakReconciliationPriority(reconciliationOf(it).source) >
                watchStreakReconciliationPriority(reconciliation.source)
        } == true

    @Synchronized
    fun clear() {
        pending = null
    }
}

internal fun shouldContinueWatchStreakRefresh(
    expectedSession: Long,
    currentSession: Long,
    expectedChannelId: String?,
    currentChannelId: String?,
    expectedChannelLogin: String?,
    currentChannelLogin: String?,
    expectedUserId: String?,
    currentUserId: String?,
    gqlToken: String?,
): Boolean = expectedSession == currentSession &&
        expectedChannelId == currentChannelId &&
        expectedChannelLogin == currentChannelLogin &&
        expectedUserId == currentUserId &&
        !expectedChannelId.isNullOrBlank() &&
        !expectedUserId.isNullOrBlank() &&
        !gqlToken.isNullOrBlank()

internal fun watchStreakInvalidationForPointsEarned(
    activeChannelId: String?,
    messageChannelId: String?,
    reasonCode: String?,
    currentCount: Int?,
    nowMs: Long? = null,
    lastReconciliationAtMs: Long? = null,
): WatchStreakReconciliation? {
    if (activeChannelId.isNullOrBlank() ||
        messageChannelId.isNullOrBlank() ||
        activeChannelId != messageChannelId ||
        !(reasonCode.equals("WATCH_STREAK", ignoreCase = true) ||
            reasonCode.equals("WATCH", ignoreCase = true))
    ) {
        return null
    }
    val isWatchStreakCredit = reasonCode.equals("WATCH_STREAK", ignoreCase = true)
    if (!isWatchStreakCredit && nowMs != null && lastReconciliationAtMs != null &&
        nowMs >= lastReconciliationAtMs &&
        nowMs - lastReconciliationAtMs < WATCH_STREAK_EVENT_RECONCILIATION_THROTTLE_MILLIS
    ) {
        return null
    }
    return WatchStreakReconciliation(
        source = if (isWatchStreakCredit) {
            WatchStreakReconciliationSource.WATCH_STREAK_CREDIT
        } else {
            WatchStreakReconciliationSource.WATCH_CREDIT
        },
        countBeforeEvent = currentCount,
    )
}

internal fun shouldRetryWatchStreakReconciliation(
    reconciliation: WatchStreakReconciliation,
    responseCount: Int?,
    retryAttempt: Int,
): Boolean {
    if (retryAttempt !in 0 until watchStreakReconciliationRetryDelaysMillis.size) return false
    return when (reconciliation.source) {
        WatchStreakReconciliationSource.WATCH_STREAK_CREDIT -> when {
            responseCount == null -> true
            reconciliation.countBeforeEvent == null -> false
            else -> responseCount <= reconciliation.countBeforeEvent
        }
        WatchStreakReconciliationSource.LIVE_NOTIFICATION ->
            reconciliation.observedLiveCount?.let {
                responseCount == null || responseCount < it
            } == true
        WatchStreakReconciliationSource.PERIODIC,
        WatchStreakReconciliationSource.WATCH_CREDIT,
        -> false
    }
}

internal fun watchStreakSnapshotStatus(
    reconciliation: WatchStreakReconciliation,
    responseCount: Int?,
): String {
    if (responseCount == null) return "missing"
    val baseline = reconciliation.countBeforeEvent ?: return "advanced"
    return when {
        responseCount > baseline -> "advanced"
        responseCount == baseline -> "unchanged"
        else -> "stale"
    }
}

internal fun mergeWatchStreakState(
    previous: WatchStreak?,
    incoming: WatchStreak,
    realtime: Boolean = false,
): WatchStreak {
    if (previous == null) return incoming

    val staleCount = incoming.streakCount < previous.streakCount
    val streakCount = maxOf(previous.streakCount, incoming.streakCount)
    val nextMilestone = incoming.nextMilestone ?: previous.nextMilestone
    val milestoneReached = previous.nextMilestone?.let {
        previous.streakCount < it && streakCount >= it
    } == true
    val milestoneChanged = realtime && (incoming.pointsAwarded != null || milestoneReached)
    return incoming.copy(
        streakCount = streakCount,
        nextMilestone = nextMilestone,
        rewardPoints = incoming.rewardPoints ?: previous.rewardPoints,
        pointsAwarded = if (staleCount) previous.pointsAwarded else incoming.pointsAwarded ?: previous.pointsAwarded,
        milestoneId = when {
            staleCount -> previous.milestoneId
            milestoneChanged -> incoming.milestoneId
            else -> incoming.milestoneId ?: previous.milestoneId
        },
        shareStatus = when {
            staleCount -> previous.shareStatus
            milestoneChanged -> incoming.shareStatus
            else -> incoming.shareStatus ?: previous.shareStatus
        },
    )
}
