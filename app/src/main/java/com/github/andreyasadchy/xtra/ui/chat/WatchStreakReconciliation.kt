package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.ui.WatchStreak

internal enum class WatchStreakReconciliationSource {
    POINTS_EARNED,
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

internal fun watchStreakInvalidationForPointsEarned(
    activeChannelId: String?,
    messageChannelId: String?,
    reasonCode: String?,
    currentCount: Int?,
): WatchStreakReconciliation? {
    if (activeChannelId.isNullOrBlank() ||
        messageChannelId.isNullOrBlank() ||
        activeChannelId != messageChannelId ||
        !reasonCode.equals("WATCH_STREAK", ignoreCase = true)
    ) {
        return null
    }
    return WatchStreakReconciliation(
        source = WatchStreakReconciliationSource.POINTS_EARNED,
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
        WatchStreakReconciliationSource.POINTS_EARNED -> when {
            responseCount == null -> true
            reconciliation.countBeforeEvent == null -> false
            else -> responseCount <= reconciliation.countBeforeEvent
        }
        WatchStreakReconciliationSource.LIVE_NOTIFICATION ->
            reconciliation.observedLiveCount?.let {
                responseCount == null || responseCount < it
            } == true
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
