package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction

/**
 * Keeps prediction snapshots monotonic when Hermes and Helix overlap. A late
 * begin/progress event must not reopen a prediction after its terminal result
 * has been observed.
 */
object PredictionState {
    private val terminalStatuses = setOf(
        "RESOLVED",
        "COMPLETED",
        "TERMINATED",
        "ARCHIVED",
        "CANCELED",
        "CANCELLED",
        "REFUNDED",
        // These states are closed for betting. A later RESOLVED event may
        // still replace them, but a delayed ACTIVE/progress event may not.
        "LOCKED",
        "CANCEL_PENDING",
        "RESOLVE_PENDING",
    )

    fun isTerminal(prediction: Prediction?): Boolean =
        prediction?.status?.uppercase() in terminalStatuses

    fun isActive(prediction: Prediction?, now: Long = System.currentTimeMillis()): Boolean {
        if (prediction?.status?.uppercase() != "ACTIVE") return false
        val start = prediction.startedAt ?: prediction.createdAt
        val duration = prediction.predictionWindowSeconds
        if (start != null && duration != null && start + duration * 1_000L <= now) return false
        return true
    }

    /** Cached ACTIVE predictions whose betting window elapsed become LOCKED. */
    fun normalizeCached(prediction: Prediction, now: Long = System.currentTimeMillis()): Prediction {
        if (prediction.status?.uppercase() != "ACTIVE") return prediction
        val start = prediction.startedAt ?: prediction.createdAt
        val duration = prediction.predictionWindowSeconds
        val locksAt = start?.let { duration?.let { seconds -> it + seconds * 1_000L } }
        return if (locksAt != null && locksAt <= now) {
            prediction.copy(
                status = "LOCKED",
                lockedAt = prediction.lockedAt ?: locksAt,
            )
        } else if (locksAt == null) {
            // A cached ACTIVE snapshot without timing cannot prove that the
            // betting window is still open after the app was reopened.
            prediction.copy(status = "LOCKED")
        } else {
            prediction
        }
    }

    fun merge(current: Prediction?, incoming: Prediction?): Prediction? {
        if (incoming?.id.isNullOrBlank()) return current
        if (current == null || current.id.isNullOrBlank()) return incoming
        if (current.id != incoming.id) {
            return if (isNewer(incoming, current)) incoming else current
        }

        if (isTerminal(current) && !isTerminal(incoming)) return current
        if (incoming.observedAt != null && current.observedAt != null && incoming.observedAt < current.observedAt) {
            return current
        }

        return incoming.copy(
            createdAt = incoming.createdAt ?: current.createdAt,
            startedAt = incoming.startedAt ?: current.startedAt,
            lockedAt = incoming.lockedAt ?: current.lockedAt,
            endedAt = incoming.endedAt ?: current.endedAt,
            title = incoming.title ?: current.title,
            status = if (isTerminal(current) && !isTerminal(incoming)) current.status else incoming.status ?: current.status,
            winningOutcomeId = incoming.winningOutcomeId ?: current.winningOutcomeId,
            predictionWindowSeconds = incoming.predictionWindowSeconds ?: current.predictionWindowSeconds,
            outcomes = mergeOutcomes(current.outcomes, incoming.outcomes),
            observedAt = incoming.observedAt ?: current.observedAt,
        )
    }

    private fun mergeOutcomes(
        current: List<Prediction.PredictionOutcome>?,
        incoming: List<Prediction.PredictionOutcome>?,
    ): List<Prediction.PredictionOutcome>? {
        if (current.isNullOrEmpty()) return incoming
        if (incoming.isNullOrEmpty()) return current
        val currentByKey = current.associateBy { it.id ?: it.title }
        return incoming.map { outcome ->
            val previous = currentByKey[outcome.id ?: outcome.title]
            if (previous == null) {
                outcome
            } else {
                outcome.copy(
                    title = outcome.title ?: previous.title,
                    totalPoints = max(previous.totalPoints, outcome.totalPoints),
                    totalUsers = max(previous.totalUsers, outcome.totalUsers),
                    color = outcome.color ?: previous.color,
                )
            }
        }
    }

    private fun max(first: Int?, second: Int?): Int? = when {
        first == null -> second
        second == null -> first
        else -> kotlin.math.max(first, second)
    }

    private fun isNewer(incoming: Prediction, current: Prediction): Boolean {
        val incomingStart = incoming.startedAt ?: incoming.createdAt
        val currentStart = current.startedAt ?: current.createdAt
        if (incomingStart != null && currentStart != null && incomingStart != currentStart) {
            return incomingStart > currentStart
        }
        return when {
            incoming.observedAt != null && current.observedAt != null -> incoming.observedAt >= current.observedAt
            incoming.observedAt != null -> true
            current.observedAt != null -> false
            else -> true
        }
    }
}
