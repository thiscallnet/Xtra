package com.github.andreyasadchy.xtra.model.chat

data class Prediction(
    val id: String?,
    val createdAt: Long?,
    val outcomes: List<PredictionOutcome>?,
    val predictionWindowSeconds: Int?,
    val status: String?,
    val title: String?,
    val winningOutcomeId: String?,
    val startedAt: Long? = createdAt,
    val locksAt: Long? = null,
    val lockedAt: Long? = null,
    val endedAt: Long? = null,
    val observedAt: Long? = null,
    /** Optional live-session identity used to discard unresolved predictions from an older broadcast. */
    val broadcastId: String? = null,
) {
    data class PredictionOutcome(
        val id: String?,
        val title: String?,
        val totalPoints: Int?,
        val totalUsers: Int?,
        val color: String? = null,
        val badgeSetId: String? = null,
        val badgeVersion: String? = null,
        val badgeUrl: String? = null,
    )
}
