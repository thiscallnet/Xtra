package com.github.andreyasadchy.xtra.util.chat

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Persists only confirmed viewer participation for the latest event per
 * channel. Pending requests are deliberately never written here.
 */
internal object ViewerParticipationCache {
    data class PollVote(
        val pollId: String,
        val choiceId: String,
        val userId: String,
    )

    data class PredictionBet(
        val predictionId: String,
        val outcomeId: String,
        val amount: Int,
        val userId: String,
        val broadcastId: String?,
    )

    private const val POLL_PREFIX = "viewer_poll_vote_"
    private const val PREDICTION_PREFIX = "viewer_prediction_bet_"

    fun loadPollVote(
        preferences: SharedPreferences,
        channelId: String,
        userId: String?,
    ): PollVote? {
        if (channelId.isBlank() || userId.isNullOrBlank()) return null
        val key = POLL_PREFIX + channelId
        val vote = preferences.getString(key, null)?.let(::decodePollVote)
        if (vote == null) {
            if (preferences.contains(key)) preferences.edit().remove(key).apply()
            return null
        }
        if (vote.userId != userId) {
            preferences.edit().remove(key).apply()
            return null
        }
        return vote
    }

    fun savePollVote(
        preferences: SharedPreferences,
        channelId: String,
        pollId: String,
        choiceId: String,
        userId: String,
    ) {
        if (channelId.isBlank() || pollId.isBlank() || choiceId.isBlank() || userId.isBlank()) return
        preferences.edit()
            .putString(
                POLL_PREFIX + channelId,
                JSONObject()
                    .put("poll_id", pollId)
                    .put("choice_id", choiceId)
                    .put("user_id", userId)
                    .toString(),
            )
            .apply()
    }

    fun clearPollVote(preferences: SharedPreferences, channelId: String) {
        if (channelId.isBlank()) return
        preferences.edit().remove(POLL_PREFIX + channelId).apply()
    }

    fun loadPredictionBet(
        preferences: SharedPreferences,
        channelId: String,
        userId: String?,
        broadcastId: String? = null,
    ): PredictionBet? {
        if (channelId.isBlank() || userId.isNullOrBlank()) return null
        val key = PREDICTION_PREFIX + channelId
        val bet = preferences.getString(key, null)?.let(::decodePredictionBet)
        if (bet == null) {
            if (preferences.contains(key)) preferences.edit().remove(key).apply()
            return null
        }
        if (bet.userId != userId ||
            (!broadcastId.isNullOrBlank() && !bet.broadcastId.isNullOrBlank() && bet.broadcastId != broadcastId)
        ) {
            preferences.edit().remove(key).apply()
            return null
        }
        return bet
    }

    fun savePredictionBet(
        preferences: SharedPreferences,
        channelId: String,
        predictionId: String,
        outcomeId: String,
        amount: Int,
        userId: String,
        broadcastId: String?,
    ) {
        if (channelId.isBlank() || predictionId.isBlank() || outcomeId.isBlank() || amount <= 0 || userId.isBlank()) return
        preferences.edit()
            .putString(
                PREDICTION_PREFIX + channelId,
                JSONObject()
                    .put("prediction_id", predictionId)
                    .put("outcome_id", outcomeId)
                    .put("amount", amount)
                    .put("user_id", userId)
                    .apply { broadcastId?.let { put("broadcast_id", it) } }
                    .toString(),
            )
            .apply()
    }

    fun clearPredictionBet(preferences: SharedPreferences, channelId: String) {
        if (channelId.isBlank()) return
        preferences.edit().remove(PREDICTION_PREFIX + channelId).apply()
    }

    private fun decodePollVote(value: String): PollVote? = runCatching {
        JSONObject(value).let {
            PollVote(
                pollId = it.requiredString("poll_id"),
                choiceId = it.requiredString("choice_id"),
                userId = it.requiredString("user_id"),
            )
        }
    }.getOrNull()

    private fun decodePredictionBet(value: String): PredictionBet? = runCatching {
        JSONObject(value).let {
            PredictionBet(
                predictionId = it.requiredString("prediction_id"),
                outcomeId = it.requiredString("outcome_id"),
                amount = it.optInt("amount").takeIf { amount -> amount > 0 } ?: error("Invalid prediction amount"),
                userId = it.requiredString("user_id"),
                broadcastId = it.optionalString("broadcast_id"),
            )
        }
    }.getOrNull()

    private fun JSONObject.requiredString(key: String): String =
        optionalString(key) ?: error("Missing $key")

    private fun JSONObject.optionalString(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }
}
