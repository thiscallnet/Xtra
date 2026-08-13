package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Prediction
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Instant

/** Parsed result of Twitch's viewer-facing current-prediction operation. */
internal data class GqlPredictionSnapshot(
    val prediction: Prediction?,
    /** True only when the response contained both current collections. */
    val authoritative: Boolean,
    val hasActiveOrLockedPrediction: Boolean,
)

/**
 * Parser for the private ChannelPointsPredictionContext operation.
 *
 * Twitch has returned both arrays and connection-shaped collections for this
 * operation. The parser deliberately accepts both, and keeps its camelCase
 * handling separate from the snake_case PubSub/Helix parser.
 */
internal object GqlPredictionParser {
    fun parse(body: String, observedAt: Long = System.currentTimeMillis()): GqlPredictionSnapshot? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val channel = root.optJSONObject("data")
            ?.optJSONObject("community")
            ?.optJSONObject("channel")
            ?: return null

        val activeEvents = channel.opt("activePredictionEvents")
            .predictionObjects()
            .mapNotNull { parsePrediction(it, "ACTIVE", observedAt) }
            .filter { prediction -> PredictionState.isOngoing(prediction) }
        val lockedEvents = channel.opt("lockedPredictionEvents")
            .predictionObjects()
            .mapNotNull { parsePrediction(it, "LOCKED", observedAt) }
            .filter { prediction -> PredictionState.isOngoing(prediction) }
        val resolvedEvents = channel.opt("resolvedPredictionEvents")
            .predictionObjects()
            .mapNotNull { parsePrediction(it, "RESOLVED", observedAt) }
            .filter { prediction ->
                PredictionState.isFinal(prediction) && isRecentResolved(prediction, observedAt)
            }

        return GqlPredictionSnapshot(
            prediction = (activeEvents + lockedEvents + resolvedEvents)
                .firstOrNull { !it.id.isNullOrBlank() },
            authoritative = channel.hasNonNullCollection("activePredictionEvents") &&
                    channel.hasNonNullCollection("lockedPredictionEvents"),
            hasActiveOrLockedPrediction = activeEvents.isNotEmpty() || lockedEvents.isNotEmpty(),
        )
    }

    private fun parsePrediction(
        json: JSONObject,
        fallbackStatus: String,
        observedAt: Long,
    ): Prediction? {
        val id = json.optionalString("id") ?: return null
        val startedAt = json.timestamp("startedAt", "started_at")
        val createdAt = json.timestamp("createdAt", "created_at") ?: startedAt
        val locksAt = json.timestamp("locksAt", "locks_at")
        val lockedAt = json.timestamp("lockedAt", "locked_at")
        val endedAt = json.timestamp("endedAt", "ended_at")
        val status = json.optionalString("status")?.uppercase() ?: fallbackStatus
        val effectiveWindow = json.optionalInt(
            "predictionWindowSeconds",
            "prediction_window_seconds",
            "predictionWindow",
            "prediction_window",
        ) ?: if (startedAt != null && locksAt != null) {
            ((locksAt - startedAt) / 1_000L).toInt().takeIf { it > 0 }
        } else {
            null
        }

        val outcomes = json.optJSONArray("outcomes")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val outcome = array.optJSONObject(index) ?: continue
                    add(
                        Prediction.PredictionOutcome(
                            id = outcome.optionalString("id"),
                            title = outcome.optionalString("title"),
                            totalPoints = outcome.optionalInt("totalPoints", "total_points"),
                            totalUsers = outcome.optionalInt("totalUsers", "total_users"),
                            color = outcome.optionalString("color")?.uppercase(),
                        ),
                    )
                }
            }
        }.orEmpty()

        val winningOutcomeId = json.optionalString("winning_outcome_id")
            ?: json.optJSONObject("winningOutcome")?.optionalString("id")

        return Prediction(
            id = id,
            createdAt = createdAt,
            outcomes = outcomes,
            predictionWindowSeconds = effectiveWindow,
            status = status,
            title = json.optionalString("title"),
            winningOutcomeId = winningOutcomeId,
            startedAt = startedAt,
            locksAt = locksAt,
            lockedAt = lockedAt,
            endedAt = endedAt,
            observedAt = observedAt,
        )
    }

    private fun isRecentResolved(prediction: Prediction, observedAt: Long): Boolean {
        val endedAt = prediction.endedAt ?: return false
        val age = observedAt - endedAt
        return age in 0L..PredictionState.RESULT_DISPLAY_GRACE_MILLIS
    }

    private fun Any?.predictionObjects(): List<JSONObject> = when (this) {
        is JSONArray -> buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }
        is JSONObject -> {
            val entries = optJSONArray("edges") ?: optJSONArray("nodes")
            if (entries != null) {
                buildList {
                    for (index in 0 until entries.length()) {
                        val entry = entries.optJSONObject(index) ?: continue
                        (entry.optJSONObject("node") ?: entry).let(::add)
                    }
                }
            } else if (has("id")) {
                listOf(this)
            } else {
                emptyList()
            }
        }
        else -> emptyList()
    }

    private fun JSONObject.hasNonNullCollection(key: String): Boolean = has(key) && !isNull(key)

    private fun JSONObject.optionalString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optionalInt(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) {
            null
        } else {
            opt(key)?.toString()?.toDoubleOrNull()?.toInt()
        }
    }

    private fun JSONObject.timestamp(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) {
            null
        } else {
            when (val value = opt(key)) {
                is Number -> value.toLong().toEpochMillis()
                null -> null
                else -> value.toString().toLongOrNull()?.toEpochMillis()
                    ?: Instant.parseOrNull(value.toString())?.toEpochMilliseconds()
            }
        }
    }

    private fun Long.toEpochMillis(): Long? = when {
        this <= 0L -> null
        this < 100_000_000_000L -> this * 1_000L
        else -> this
    }
}
