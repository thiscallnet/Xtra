package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction

/**
 * Prediction lifecycle semantics shared by Hermes, Helix, cache restoration,
 * and the UI.
 *
 * ACTIVE means that betting may still be possible. LOCKED means that betting
 * has ended but the broadcaster has not produced a result yet. The state
 * ordering is deliberately monotonic so a delayed ACTIVE event cannot reopen
 * a LOCKED prediction, and a delayed progress event cannot replace a final
 * result.
 */
object PredictionState {
    private val finalStatuses = setOf(
        "RESOLVED",
        "COMPLETED",
        "TERMINATED",
        "ARCHIVED",
        "CANCELED",
        "CANCELLED",
        "REFUNDED",
    )
    private val pendingStatuses = setOf("CANCEL_PENDING", "RESOLVE_PENDING")

    fun status(prediction: Prediction?): String = prediction?.status.orEmpty().uppercase()

    fun isFinalStatus(status: String?): Boolean = status?.uppercase() in finalStatuses

    /** True only while the prediction can accept a wager. */
    fun isBettingOpen(prediction: Prediction?, now: Long = System.currentTimeMillis()): Boolean {
        if (status(prediction) != "ACTIVE") return false
        val endsAt = bettingEndsAt(prediction) ?: return true
        return endsAt > now
    }

    /** True while Twitch has not reported a final result or cancellation. */
    fun isOngoing(prediction: Prediction?): Boolean = when (status(prediction)) {
        "ACTIVE", "LOCKED", "CANCEL_PENDING", "RESOLVE_PENDING" -> true
        else -> false
    }

    fun isFinal(prediction: Prediction?): Boolean = isFinalStatus(status(prediction))

    /**
     * Converts a locally or cache-observed ACTIVE snapshot whose betting
     * window has elapsed into a complete LOCKED snapshot instead of dropping
     * it from the activity surface.
     */
    fun normalizeForNow(prediction: Prediction, now: Long = System.currentTimeMillis()): Prediction {
        if (status(prediction) != "ACTIVE") return prediction
        val locksAt = bettingEndsAt(prediction)
        return when {
            locksAt != null && locksAt <= now -> prediction.copy(
                status = "LOCKED",
                lockedAt = prediction.lockedAt ?: locksAt,
            )
            locksAt == null -> {
                // A restored ACTIVE snapshot without timing cannot prove that
                // it is still bettable. Keep its data, but fail closed.
                prediction.copy(status = "LOCKED")
            }
            else -> prediction
        }
    }

    /** Cached ACTIVE snapshots are normalized with the same lifecycle rules. */
    fun normalizeCached(prediction: Prediction, now: Long = System.currentTimeMillis()): Prediction =
        normalizeForNow(prediction, now)

    fun merge(current: Prediction?, incoming: Prediction?): Prediction? {
        if (incoming?.id.isNullOrBlank()) return current
        if (current == null || current.id.isNullOrBlank()) return incoming
        if (current.id != incoming.id) {
            return if (isNewer(incoming, current)) incoming else current
        }

        val currentPhase = phase(current)
        val incomingPhase = phase(incoming)
        if (incomingPhase < currentPhase) return current
        if (incoming.observedAt != null && current.observedAt != null &&
            incoming.observedAt < current.observedAt && incomingPhase <= currentPhase
        ) {
            return current
        }

        return incoming.copy(
            broadcastId = incoming.broadcastId ?: current.broadcastId,
            createdAt = incoming.createdAt ?: current.createdAt,
            startedAt = incoming.startedAt ?: current.startedAt,
            lockedAt = incoming.lockedAt ?: current.lockedAt,
            endedAt = incoming.endedAt ?: current.endedAt,
            title = incoming.title ?: current.title,
            status = incoming.status ?: current.status,
            winningOutcomeId = incoming.winningOutcomeId ?: current.winningOutcomeId,
            predictionWindowSeconds = incoming.predictionWindowSeconds ?: current.predictionWindowSeconds,
            outcomes = mergeOutcomes(current.outcomes, incoming.outcomes),
            observedAt = max(current.observedAt, incoming.observedAt),
        )
    }

    private fun bettingEndsAt(prediction: Prediction?): Long? {
        if (prediction == null) return null
        return prediction.lockedAt ?: run {
            val start = prediction.startedAt ?: prediction.createdAt
            val duration = prediction.predictionWindowSeconds
            start?.let { duration?.let { seconds -> it + seconds * 1_000L } }
        }
    }

    private fun phase(prediction: Prediction): Int = when {
        isFinal(prediction) -> 3
        status(prediction) in pendingStatuses -> 2
        status(prediction) == "LOCKED" -> 1
        status(prediction) == "ACTIVE" -> 0
        else -> 0
    }

    private fun mergeOutcomes(
        current: List<Prediction.PredictionOutcome>?,
        incoming: List<Prediction.PredictionOutcome>?,
    ): List<Prediction.PredictionOutcome>? {
        if (current.isNullOrEmpty()) return incoming
        if (incoming.isNullOrEmpty()) return current
        val currentByKey = current.associateBy { it.id ?: it.title }
        val incomingKeys = incoming.map { it.id ?: it.title }.toSet()
        val merged = incoming.map { outcome ->
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
        return merged + current.filter { (it.id ?: it.title) !in incomingKeys }
    }

    private fun max(first: Long?, second: Long?): Long? = when {
        first == null -> second
        second == null -> first
        else -> kotlin.math.max(first, second)
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
