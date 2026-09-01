package com.github.andreyasadchy.xtra.util.chat

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.model.chat.Prediction
import org.json.JSONArray
import org.json.JSONObject

/** Bounded per-channel cache for the latest observed prediction. */
object PredictionCache {
    private const val ENTRY_PREFIX = "latest_prediction_"
    private const val INDEX_KEY = "latest_prediction_index"
    private const val MAX_ENTRIES = 32
    private const val MAX_FINAL_AGE_MILLIS = 24L * 60L * 60L * 1_000L
    private const val MAX_UNRESOLVED_AGE_MILLIS = 24L * 60L * 60L * 1_000L

    fun load(
        preferences: SharedPreferences,
        channelId: String,
        now: Long = System.currentTimeMillis(),
        broadcastId: String? = null,
    ): Prediction? {
        val cacheTimestamp = readIndex(preferences)[channelId]
        val prediction = preferences.getString(ENTRY_PREFIX + channelId, null)
            ?.let(::decode)
            ?.let { PredictionState.normalizeCached(it, now) }
            ?.takeIf { isFresh(it, cacheTimestamp, now, broadcastId) }
        if (prediction == null) {
            val index = readIndex(preferences).toMutableMap().apply { remove(channelId) }
            preferences.edit()
                .remove(ENTRY_PREFIX + channelId)
                .putString(INDEX_KEY, JSONObject(index).toString())
                .apply()
        }
        return prediction
    }

    fun save(
        preferences: SharedPreferences,
        channelId: String,
        prediction: Prediction,
        now: Long = System.currentTimeMillis(),
        broadcastId: String? = null,
    ) {
        val index = readIndex(preferences).toMutableMap()
        index[channelId] = now
        val cutoff = now - MAX_FINAL_AGE_MILLIS
        index.filterValues { it < cutoff }.keys.toList().forEach { oldChannelId ->
            index.remove(oldChannelId)
            preferences.edit().remove(ENTRY_PREFIX + oldChannelId).apply()
        }
        while (index.size > MAX_ENTRIES) {
            val oldest = index.minByOrNull { it.value }?.key ?: break
            index.remove(oldest)
            preferences.edit().remove(ENTRY_PREFIX + oldest).apply()
        }
        preferences.edit()
            .putString(
                ENTRY_PREFIX + channelId,
                encode(prediction.copy(broadcastId = broadcastId ?: prediction.broadcastId)),
            )
            .putString(INDEX_KEY, JSONObject(index).toString())
            .apply()
    }

    fun clear(preferences: SharedPreferences, channelId: String) {
        val index = readIndex(preferences).toMutableMap().apply { remove(channelId) }
        preferences.edit()
            .remove(ENTRY_PREFIX + channelId)
            .putString(INDEX_KEY, JSONObject(index).toString())
            .apply()
    }

    /** Kept pure so cache-age and session invalidation rules can be unit tested. */
    internal fun isFresh(
        prediction: Prediction,
        cacheTimestamp: Long?,
        now: Long,
        broadcastId: String? = null,
    ): Boolean {
        if (!broadcastId.isNullOrBlank() && prediction.broadcastId != broadcastId) {
            return false
        }
        val reference = if (PredictionState.isFinal(prediction)) {
            prediction.endedAt ?: prediction.observedAt ?: prediction.startedAt
        } else {
            prediction.lockedAt ?: prediction.locksAt ?: prediction.startedAt ?: prediction.createdAt ?: prediction.observedAt
        } ?: cacheTimestamp ?: return false
        val maxAge = if (PredictionState.isFinal(prediction)) {
            MAX_FINAL_AGE_MILLIS
        } else {
            MAX_UNRESOLVED_AGE_MILLIS
        }
        return reference >= now - maxAge
    }

    internal fun encode(prediction: Prediction): String = JSONObject().apply {
        putNullable("id", prediction.id)
        putNullable("created_at", prediction.createdAt)
        putNullable("started_at", prediction.startedAt)
        putNullable("locks_at", prediction.locksAt)
        putNullable("locked_at", prediction.lockedAt)
        putNullable("ended_at", prediction.endedAt)
        putNullable("prediction_window_seconds", prediction.predictionWindowSeconds)
        putNullable("status", prediction.status)
        putNullable("title", prediction.title)
        putNullable("winning_outcome_id", prediction.winningOutcomeId)
        putNullable("observed_at", prediction.observedAt)
        putNullable("broadcast_id", prediction.broadcastId)
        put("outcomes", JSONArray().apply {
            prediction.outcomes.orEmpty().forEach { outcome ->
                put(JSONObject().apply {
                    putNullable("id", outcome.id)
                    putNullable("title", outcome.title)
                    putNullable("total_points", outcome.totalPoints)
                    putNullable("total_users", outcome.totalUsers)
                    putNullable("color", outcome.color)
                    putNullable("badge_set_id", outcome.badgeSetId)
                    putNullable("badge_version", outcome.badgeVersion)
                    putNullable("badge_url", outcome.badgeUrl)
                })
            }
        })
    }.toString()

    internal fun decode(value: String): Prediction? = runCatching {
        val json = JSONObject(value)
        val outcomes = json.optJSONArray("outcomes")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val outcome = array.optJSONObject(index) ?: continue
                    add(
                        Prediction.PredictionOutcome(
                            id = outcome.optionalString("id"),
                            title = outcome.optionalString("title"),
                            totalPoints = outcome.optionalInt("total_points"),
                            totalUsers = outcome.optionalInt("total_users"),
                            color = outcome.optionalString("color"),
                            badgeSetId = outcome.optionalString("badge_set_id"),
                            badgeVersion = outcome.optionalString("badge_version"),
                            badgeUrl = outcome.optionalString("badge_url"),
                        ),
                    )
                }
            }
        }
        Prediction(
            id = json.optionalString("id"),
            createdAt = json.optionalLong("created_at"),
            outcomes = outcomes,
            predictionWindowSeconds = json.optionalInt("prediction_window_seconds"),
            status = json.optionalString("status"),
            title = json.optionalString("title"),
            winningOutcomeId = json.optionalString("winning_outcome_id"),
            startedAt = json.optionalLong("started_at"),
            locksAt = json.optionalLong("locks_at"),
            lockedAt = json.optionalLong("locked_at"),
            endedAt = json.optionalLong("ended_at"),
            observedAt = json.optionalLong("observed_at"),
            broadcastId = json.optionalString("broadcast_id"),
        )
    }.getOrNull()

    private fun readIndex(preferences: SharedPreferences): Map<String, Long> {
        val json = preferences.getString(INDEX_KEY, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.optLong(it, 0L) }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optionalInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.toDoubleOrNull()?.toInt()
    }

    private fun JSONObject.optionalLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.toDoubleOrNull()?.toLong()
    }
}
