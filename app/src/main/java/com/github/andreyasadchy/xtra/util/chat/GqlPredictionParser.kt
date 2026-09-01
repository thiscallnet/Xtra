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

internal fun GqlPredictionSnapshot.hasUsableOutcomeSet(): Boolean {
    if (!hasActiveOrLockedPrediction) return true

    val values = prediction?.outcomes ?: return false
    return values.size in 2..10 && values.all { outcome ->
        !outcome.id.isNullOrBlank() && !outcome.title.isNullOrBlank()
    }
}

internal fun shouldLoadAnonymousPredictionSnapshot(
    authenticatedSnapshot: GqlPredictionSnapshot?,
): Boolean = authenticatedSnapshot == null ||
    !authenticatedSnapshot.hasActiveOrLockedPrediction ||
    !authenticatedSnapshot.hasUsableOutcomeSet()

internal fun chooseGqlPredictionSnapshot(
    authenticatedSnapshot: GqlPredictionSnapshot?,
    anonymousSnapshot: GqlPredictionSnapshot?,
): GqlPredictionSnapshot? = listOfNotNull(authenticatedSnapshot, anonymousSnapshot)
    .maxByOrNull { snapshot ->
        when {
            snapshot.hasActiveOrLockedPrediction && snapshot.hasUsableOutcomeSet() -> 4
            snapshot.hasActiveOrLockedPrediction -> 3
            snapshot.authoritative -> 2
            snapshot.prediction != null -> 1
            else -> 0
        }
    }

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
        val data = root.optJSONObject("data") ?: return null
        val channel = data.optJSONObject("community")
            ?.optJSONObject("channel")
            ?: data.optJSONObject("channel")
            ?: data.optJSONObject("user")?.optJSONObject("channel")
            ?: return null

        val activeObjects = channel.opt("activePredictionEvents").predictionObjectsOrNull()
        val lockedObjects = channel.opt("lockedPredictionEvents").predictionObjectsOrNull()
        val activeEvents = activeObjects.orEmpty()
            .mapNotNull { parsePrediction(it, "ACTIVE", observedAt) }
            .filter { prediction -> PredictionState.isOngoing(prediction) }
        val lockedEvents = lockedObjects.orEmpty()
            .mapNotNull { parsePrediction(it, "LOCKED", observedAt) }
            .filter { prediction -> PredictionState.isOngoing(prediction) }
        val resolvedEvents = channel.opt("resolvedPredictionEvents").predictionObjectsOrNull().orEmpty()
            .mapNotNull { parsePrediction(it, "RESOLVED", observedAt) }
            .filter { prediction ->
                PredictionState.isFinal(prediction) && isRecentResolved(prediction, observedAt)
            }

        return GqlPredictionSnapshot(
            prediction = (activeEvents + lockedEvents + resolvedEvents)
                .firstOrNull { !it.id.isNullOrBlank() },
            authoritative = activeObjects != null && lockedObjects != null,
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

        val outcomes = json.outcomeObjectsOrNull()?.map(::parseOutcome)

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

    private fun parseOutcome(outcome: JSONObject): Prediction.PredictionOutcome {
        val badge = outcome.optJSONObject("badge")
        return Prediction.PredictionOutcome(
            id = outcome.optionalString("id", "outcomeID", "outcomeId", "outcome_id"),
            title = outcome.optionalString("title"),
            totalPoints = outcome.optionalInt(
                "totalPoints",
                "total_points",
                "channelPoints",
                "channel_points",
            ),
            totalUsers = outcome.optionalInt("totalUsers", "total_users", "users"),
            color = outcome.optionalString("color")?.uppercase(),
            badgeSetId = badge?.optionalString("setID", "setId", "set_id"),
            badgeVersion = badge?.optionalString("version"),
            badgeUrl = badge?.optionalString("image4x", "image2x", "image1x"),
        )
    }

    private fun isRecentResolved(prediction: Prediction, observedAt: Long): Boolean {
        val endedAt = prediction.endedAt ?: return false
        val age = observedAt - endedAt
        return age in 0L..PredictionState.RESULT_DISPLAY_GRACE_MILLIS
    }

    private fun Any?.predictionObjectsOrNull(): List<JSONObject>? = when (this) {
        null, JSONObject.NULL -> null
        is JSONArray -> jsonObjects()
        is JSONObject -> when {
            optJSONArray("nodes") != null -> optJSONArray("nodes")!!.jsonObjects()
            optJSONArray("edges") != null -> optJSONArray("edges")!!.jsonObjects()
                .mapNotNull { it.optJSONObject("node") ?: it }
            has("id") -> listOf(this)
            else -> null
        }
        else -> null
    }

    private fun JSONObject.outcomeObjectsOrNull(key: String = "outcomes"): List<JSONObject>? {
        if (!has(key) || isNull(key)) return null
        val raw = opt(key)
        return when {
            raw is JSONArray -> raw.jsonObjects()
            raw is JSONObject -> when {
                raw.optJSONArray("nodes") != null -> raw.optJSONArray("nodes")!!.jsonObjects()
                raw.optJSONArray("edges") != null -> raw.optJSONArray("edges")!!.jsonObjects()
                    .mapNotNull { it.optJSONObject("node") ?: it }
                else -> null
            }
            else -> null
        }
    }

    private fun JSONArray.jsonObjects(): List<JSONObject> = buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }

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
