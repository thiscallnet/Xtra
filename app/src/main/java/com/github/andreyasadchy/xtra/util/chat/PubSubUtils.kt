package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.ChannelPointReward
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.chat.Raid
import org.json.JSONObject
import kotlin.time.Instant

data class ChannelPointsBalanceEvent(
    val channelId: String?,
    val type: Type,
    /** The event amount as a positive number. The reducer applies the sign. */
    val delta: Int? = null,
    val absoluteBalance: Int? = null,
    val reasonCode: String? = null,
    val timestamp: Long? = null,
    val messageId: String? = null,
    val transactionId: String? = null,
    val streakCount: Int? = null,
) {
    enum class Type {
        EARNED,
        SPENT,
    }
}

typealias ChannelPointsBalanceEventType = ChannelPointsBalanceEvent.Type

object PubSubUtils {
    fun parsePlaybackMessage(message: JSONObject): PlaybackMessage? {
        val messageType = message.optString("type")
        return when {
            messageType.startsWith("viewcount") -> PlaybackMessage(viewers = if (!message.isNull("viewers")) message.optInt("viewers") else null)
            messageType.startsWith("stream-up") -> PlaybackMessage(true, if (!message.isNull("server_time")) message.optLong("server_time").takeIf { it > 0 } else null)
            messageType.startsWith("stream-down") -> PlaybackMessage(false)
            else -> null
        }
    }

    fun parseStreamInfo(message: JSONObject): StreamInfo {
        return StreamInfo(
            title = if (!message.isNull("status")) message.optString("status").takeIf { it.isNotBlank() } else null,
            gameId = if (!message.isNull("game_id")) message.optInt("game_id").takeIf { it > 0 }?.toString() else null,
            gameName = if (!message.isNull("game")) message.optString("game").takeIf { it.isNotBlank() } else null,
        )
    }

    fun parseRewardMessage(message: JSONObject): ChatMessage {
        val messageData = message.optJSONObject("data")
        val redemption = messageData?.optJSONObject("redemption")
        val user = redemption?.optJSONObject("user")
        val reward = redemption?.optJSONObject("reward")
        val rewardImage = reward?.optJSONObject("image")
        val defaultImage = reward?.optJSONObject("default_image")
        val input = if (redemption?.isNull("user_input") == false) redemption.optString("user_input").takeIf { it.isNotBlank() } else null
        return ChatMessage(
            type = ChatMessage.USER_MESSAGE,
            userId = if (user?.isNull("id") == false) user.optString("id").takeIf { it.isNotBlank() } else null,
            userLogin = if (user?.isNull("login") == false) user.optString("login").takeIf { it.isNotBlank() } else null,
            userName = if (user?.isNull("display_name") == false) user.optString("display_name").takeIf { it.isNotBlank() } else null,
            message = input,
            reward = ChannelPointReward(
                id = if (reward?.isNull("id") == false) reward.optString("id").takeIf { it.isNotBlank() } else null,
                title = if (reward?.isNull("title") == false) reward.optString("title").takeIf { it.isNotBlank() } else null,
                cost = if (reward?.isNull("cost") == false) reward.optInt("cost") else null,
                url1x = if (rewardImage?.isNull("url_1x") == false) { rewardImage.optString("url_1x").takeIf { it.isNotBlank() } } else { null }
                    ?: if (defaultImage?.isNull("url_1x") == false) defaultImage.optString("url_1x").takeIf { it.isNotBlank() } else null,
                url2x = if (rewardImage?.isNull("url_2x") == false) { rewardImage.optString("url_2x").takeIf { it.isNotBlank() } } else { null }
                    ?: if (defaultImage?.isNull("url_2x") == false) defaultImage.optString("url_2x").takeIf { it.isNotBlank() } else null,
                url4x = if (rewardImage?.isNull("url_4x") == false) { rewardImage.optString("url_4x").takeIf { it.isNotBlank() } } else { null }
                    ?: if (defaultImage?.isNull("url_4x") == false) defaultImage.optString("url_4x").takeIf { it.isNotBlank() } else null,
            ),
            timestamp = if (messageData?.isNull("timestamp") == false) messageData.optString("timestamp").takeIf { it.isNotBlank() }?.let { Instant.parseOrNull(it)?.toEpochMilliseconds()?.takeIf { ms -> ms > 0 } } else null,
            fullMsg = message.toString(),
        )
    }

    fun parsePointsEarned(message: JSONObject): Pair<PointsEarned, String?> {
        val event = parseChannelPointsBalanceEvent(message, ChannelPointsBalanceEvent.Type.EARNED)
        return Pair(
            PointsEarned(
                pointsGained = event?.delta,
                reasonCode = event?.reasonCode,
                timestamp = event?.timestamp,
                absoluteBalance = event?.absoluteBalance,
                streakCount = event?.streakCount,
                messageId = event?.messageId,
                fullMsg = message.toString()
            ),
            event?.channelId
        )
    }

    fun parsePointsSpent(message: JSONObject): ChannelPointsBalanceEvent? =
        parseChannelPointsBalanceEvent(message, ChannelPointsBalanceEvent.Type.SPENT)

    /**
     * Parses the private user-points messages. Twitch has changed the nesting
     * of these payloads several times, so all fields are optional and common
     * aliases are checked recursively.
     */
    fun parseChannelPointsBalanceEvent(
        message: JSONObject,
        type: ChannelPointsBalanceEvent.Type? = null,
    ): ChannelPointsBalanceEvent? {
        val eventType = type ?: message.optionalString("type")?.lowercase()?.let { messageType ->
            when {
                messageType.startsWith("points-earned") || messageType.startsWith("points_earned") -> ChannelPointsBalanceEvent.Type.EARNED
                messageType.startsWith("points-spent") || messageType.startsWith("points_spent") -> ChannelPointsBalanceEvent.Type.SPENT
                else -> null
            }
        } ?: return null
        val data = message.optJSONObject("data")
        val detail = data?.optJSONObject(if (eventType == ChannelPointsBalanceEvent.Type.EARNED) "point_gain" else "point_spend")
            ?: data?.optJSONObject(if (eventType == ChannelPointsBalanceEvent.Type.EARNED) "pointGain" else "pointSpend")
            ?: data?.optJSONObject(if (eventType == ChannelPointsBalanceEvent.Type.EARNED) "points_earned" else "points_spent")
            ?: data?.optJSONObject("points")
            ?: message.optJSONObject(if (eventType == ChannelPointsBalanceEvent.Type.EARNED) "point_gain" else "point_spend")
        val deltaKeys = if (eventType == ChannelPointsBalanceEvent.Type.EARNED) {
            setOf("total_points", "points", "amount", "points_earned", "points_gained", "value")
        } else {
            setOf("total_points", "points", "amount", "cost", "points_spent", "value")
        }
        val absoluteBalanceKeys = setOf(
            "balance",
            "new_balance",
            "current_balance",
            "total_balance",
            "points_balance",
            "channel_points_balance",
        )
        val reasonKeys = setOf("reason_code", "reasonCode", "reason")
        val streakKeys = setOf("streak_count", "streakCount", "current_streak", "watch_streak_count")
        val channelId = findChannelId(message)
        val delta = detail?.findNumeric(deltaKeys)
            ?: data?.findNumeric(deltaKeys)
            ?: message.findNumeric(deltaKeys)
        val absoluteBalance = detail?.findNumeric(absoluteBalanceKeys)
            ?: data?.findNumeric(absoluteBalanceKeys)
            ?: message.findNumeric(absoluteBalanceKeys)
        val reasonCode = detail?.findString(reasonKeys)
            ?: data?.findString(reasonKeys)
            ?: message.findString(reasonKeys)
        val streakCount = detail?.findNumeric(streakKeys)
            ?: data?.findNumeric(streakKeys)
            ?: message.findNumeric(streakKeys)
        val timestamp = detail?.findTimestamp()
            ?: data?.findTimestamp()
            ?: message.findTimestamp()
        val messageId = detail?.findMessageId()
            ?: data?.findMessageId()
            ?: message.findMessageId()
        val transactionId = detail?.findString(setOf("transaction_id", "transactionId", "redemption_id", "redemptionId"))
            ?: data?.findString(setOf("transaction_id", "transactionId", "redemption_id", "redemptionId"))
            ?: message.findString(setOf("transaction_id", "transactionId", "redemption_id", "redemptionId"))
        if (channelId == null && delta == null && absoluteBalance == null) return null
        return ChannelPointsBalanceEvent(
            channelId = channelId,
            type = eventType,
            delta = delta?.takeIf { it >= 0 },
            absoluteBalance = absoluteBalance?.takeIf { it >= 0 },
            reasonCode = reasonCode,
            timestamp = timestamp,
            messageId = messageId,
            transactionId = transactionId,
            streakCount = streakCount?.takeIf { it > 0 },
        )
    }

    fun onRaidUpdate(message: JSONObject, openStream: Boolean): Raid? {
        val raid = message.optJSONObject("raid")
        return if (raid != null) {
            Raid(
                raidId = if (!raid.isNull("id")) raid.optString("id").takeIf { it.isNotBlank() } else null,
                targetId = if (!raid.isNull("target_id")) raid.optString("target_id").takeIf { it.isNotBlank() } else null,
                targetLogin = if (!raid.isNull("target_login")) raid.optString("target_login").takeIf { it.isNotBlank() } else null,
                targetName = if (!raid.isNull("target_display_name")) raid.optString("target_display_name").takeIf { it.isNotBlank() } else null,
                targetImageURL = if (!raid.isNull("target_profile_image")) raid.optString("target_profile_image").takeIf { it.isNotBlank() }?.replace("profile_image-%s", "profile_image-300x300") else null,
                viewerCount = raid.optInt("viewer_count"),
                openStream = openStream
            )
        } else null
    }

    /**
     * Parses the three shapes used by Xtra's poll transports:
     * Hermes/EventSub nested snapshots and Helix's flat Get Polls response.
     * Missing numbers stay null; zero is a real Twitch value and must not be
     * confused with a missing field.
     */
    fun onPollUpdate(
        message: JSONObject,
        eventType: String? = null,
        observedAt: Long? = System.currentTimeMillis(),
    ): Poll? {
        val messageData = message.optJSONObject("data")
        val poll = messageData?.optJSONObject("poll")
            ?: messageData?.optJSONObject("event")
            ?: message.optJSONObject("poll")
            ?: message.optJSONObject("event")
            ?: message.takeIf { it.has("choices") && it.has("title") }
        if (poll == null) return null

        val eventName = eventType ?: message.optionalString("type")
        val status = poll.optionalString("status")?.uppercase()
            ?: when {
                eventName?.endsWith(".begin") == true || eventName?.endsWith(".progress") == true || eventName?.endsWith("_begin") == true || eventName?.endsWith("_progress") == true -> "ACTIVE"
                eventName?.endsWith(".end") == true || eventName?.endsWith("_end") == true -> "COMPLETED"
                else -> null
            }
        val startedAt = poll.timestamp("started_at")
        val endsAt = poll.timestamp("ends_at")
        val endedAt = poll.timestamp("ended_at")
        val durationSeconds = poll.durationSeconds()
            ?: if (startedAt != null && endsAt != null) ((endsAt - startedAt) / 1000L).toInt().takeIf { it >= 0 } else null
        val effectiveEndsAt = endsAt ?: if (startedAt != null && durationSeconds != null) {
            startedAt + durationSeconds * 1000L
        } else null
        val remainingMilliseconds = poll.optionalLong("remaining_duration_milliseconds")
            ?: if (status == "ACTIVE" && effectiveEndsAt != null) {
                (effectiveEndsAt - System.currentTimeMillis()).coerceAtLeast(0L)
            } else null
        val terminalEndedAt = endedAt ?: if (status in setOf("COMPLETED", "TERMINATED", "ARCHIVED", "MODERATED", "INVALID", "CANCELED")) {
            effectiveEndsAt ?: observedAt
        } else null

        val choicesList = buildList {
            poll.optJSONArray("choices")?.let { choices ->
                for (index in 0 until choices.length()) {
                    val choice = choices.optJSONObject(index) ?: continue
                    val title = choice.optionalString("title") ?: continue
                    val nestedVotes = choice.opt("votes") as? JSONObject
                    val votes = choice.optionalInt("votes")
                        ?: nestedVotes?.optionalInt("total")
                        ?: choice.optionalInt("total_votes")
                    val channelPointsVotes = choice.optionalInt("channel_points_votes")
                        ?: nestedVotes?.optionalInt("channel_points_votes")
                        ?: nestedVotes?.optionalInt("channel_points")
                    val bitsVotes = choice.optionalInt("bits_votes")
                        ?: nestedVotes?.optionalInt("bits_votes")
                        ?: nestedVotes?.optionalInt("bits")
                    add(
                        Poll.PollChoice(
                            id = choice.optionalString("id"),
                            title = title,
                            totalVotes = votes,
                            channelPointsVotes = channelPointsVotes,
                            bitsVotes = bitsVotes,
                        ),
                    )
                }
            }
        }

        val channelPointsVoting = poll.optJSONObject("channel_points_voting")
        val bitsVoting = poll.optJSONObject("bits_voting")
        val totalVotesObject = poll.opt("votes") as? JSONObject
        val totalVotes = totalVotesObject?.optionalInt("total")
            ?: poll.optionalInt("votes")
            ?: poll.optionalInt("total_votes")
            ?: choicesList.mapNotNull { it.totalVotes }.takeIf { it.size == choicesList.size }?.sum()

        return Poll(
            id = poll.optionalString("id") ?: poll.optionalString("poll_id"),
            title = poll.optionalString("title"),
            status = status,
            choices = choicesList,
            totalVotes = totalVotes,
            remainingMilliseconds = remainingMilliseconds,
            channelPointsVotingEnabled = channelPointsVoting?.optBoolean("is_enabled", false) == true ||
                poll.optionalBoolean("channel_points_voting_enabled") == true,
            channelPointsPerVote = channelPointsVoting?.optionalInt("amount_per_vote")
                ?: poll.optionalInt("channel_points_per_vote"),
            bitsVotingEnabled = bitsVoting?.optBoolean("is_enabled", false) == true ||
                poll.optionalBoolean("bits_voting_enabled") == true,
            bitsPerVote = bitsVoting?.optionalInt("amount_per_vote")
                ?: poll.optionalInt("bits_per_vote"),
            startedAt = startedAt,
            endsAt = effectiveEndsAt,
            endedAt = terminalEndedAt,
            durationSeconds = durationSeconds,
            observedAt = observedAt,
        )
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.findString(keys: Set<String>): String? {
        keys.firstNotNullOfOrNull { optionalString(it) }?.let { return it }
        val iterator = keys()
        while (iterator.hasNext()) {
            when (val value = opt(iterator.next())) {
                is JSONObject -> value.findString(keys)?.let { return it }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        value.optJSONObject(index)?.findString(keys)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun JSONObject.findNumeric(keys: Set<String>): Int? {
        keys.firstNotNullOfOrNull { optionalInt(it) }?.let { return it }
        val iterator = keys()
        while (iterator.hasNext()) {
            when (val value = opt(iterator.next())) {
                is JSONObject -> value.findNumeric(keys)?.let { return it }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        value.optJSONObject(index)?.findNumeric(keys)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun JSONObject.findTimestamp(): Long? {
        val timestampKeys = setOf("timestamp", "created_at", "createdAt", "event_timestamp", "eventTimestamp")
        timestampKeys.forEach { key ->
            if (!has(key) || isNull(key)) return@forEach
            val value = opt(key)
            val timestamp = when (value) {
                null -> null
                is Number -> value.toLong().let { if (it in 1 until 1_000_000_000_000L) it * 1_000L else it }
                else -> value.toString().toLongOrNull()?.let { if (it in 1 until 1_000_000_000_000L) it * 1_000L else it }
                    ?: Instant.parseOrNull(value.toString())?.toEpochMilliseconds()
            }
            if (timestamp != null && timestamp > 0) return timestamp
        }
        val iterator = keys()
        while (iterator.hasNext()) {
            when (val value = opt(iterator.next())) {
                is JSONObject -> value.findTimestamp()?.let { return it }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        value.optJSONObject(index)?.findTimestamp()?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun JSONObject.findMessageId(): String? {
        findString(setOf("message_id", "messageId", "event_id", "eventId"))?.let { return it }
        optionalString("id")?.let { return it }
        return null
    }

    private fun findChannelId(message: JSONObject): String? {
        val channelKeys = setOf("channel_id", "channelId", "channelID")
        channelKeys.firstNotNullOfOrNull { message.optionalString(it) }?.let { return it }
        (message.opt("channel") as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        message.optJSONObject("channel")?.findString(setOf("id", "channel_id", "channelId"))?.let { return it }
        val iterator = message.keys()
        while (iterator.hasNext()) {
            when (val value = message.opt(iterator.next())) {
                is JSONObject -> findChannelId(value)?.let { return it }
                is org.json.JSONArray -> {
                    for (index in 0 until value.length()) {
                        value.optJSONObject(index)?.let { findChannelId(it) }?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun JSONObject.optionalInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.toDoubleOrNull()?.toInt()
    }

    private fun JSONObject.optionalLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return opt(key)?.toString()?.toDoubleOrNull()?.toLong()
    }

    private fun JSONObject.optionalBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key)

    private fun JSONObject.timestamp(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return when {
            value is Number -> value.toLong()
            value == null -> null
            else -> value.toString().toLongOrNull() ?: Instant.parseOrNull(value.toString())?.toEpochMilliseconds()
        }
    }

    private fun JSONObject.durationSeconds(): Int? {
        if (!has("duration") || isNull("duration")) return null
        val value = opt("duration")
        return when {
            value is Number -> value.toInt()
            value == null -> null
            else -> value.toString().let { raw ->
                raw.toIntOrNull() ?: Regex("^(\\d+)(?:\\.\\d+)?s$").matchEntire(raw)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
    }

    fun onPredictionUpdate(
        message: JSONObject,
        eventType: String? = null,
        observedAt: Long? = System.currentTimeMillis(),
    ): Prediction? {
        val messageData = message.optJSONObject("data")
        val prediction = messageData?.optJSONObject("event")
            ?: messageData?.optJSONObject("prediction")
            ?: message.optJSONObject("event")
            ?: message.optJSONObject("prediction")
            ?: message.takeIf { it.has("outcomes") && it.has("title") }
        val outcomesList = mutableListOf<Prediction.PredictionOutcome>()
        val outcomes = prediction?.optJSONArray("outcomes")
        if (outcomes != null) {
            for (i in 0 until outcomes.length()) {
                val outcome = outcomes.optJSONObject(i)
                val title = outcome?.optionalString("title")
                if (!title.isNullOrBlank()) {
                    outcomesList.add(
                        Prediction.PredictionOutcome(
                            id = outcome?.optionalString("id"),
                            title = title,
                            totalPoints = outcome?.optionalInt("total_points")
                                ?: outcome?.optionalInt("channel_points"),
                            totalUsers = outcome?.optionalInt("total_users")
                                ?: outcome?.optionalInt("users"),
                            color = outcome?.optionalString("color")?.uppercase(),
                        )
                    )
                }
            }
        }
        return if (prediction != null) {
            val startedAt = prediction.timestamp("started_at")
            val createdAt = prediction.timestamp("created_at") ?: startedAt
            val locksAt = prediction.timestamp("locks_at")
            val lockedAt = prediction.timestamp("locked_at")
            val endedAt = prediction.timestamp("ended_at")
            val eventStatus = prediction.optString("status").takeIf { it.isNotBlank() }?.uppercase()
            ?: when {
                eventType?.endsWith(".begin") == true || eventType?.endsWith(".progress") == true -> "ACTIVE"
                eventType?.endsWith(".lock") == true -> "LOCKED"
                eventType?.endsWith(".end") == true -> "RESOLVED"
                else -> null
            }
            val predictionWindowSeconds = prediction.optionalInt("prediction_window_seconds")
                ?: prediction.optionalInt("prediction_window")
                ?: if (startedAt != null && locksAt != null) {
                    ((locksAt - startedAt) / 1000L).toInt().takeIf { it > 0 }
                } else null
            Prediction(
                id = prediction.optionalString("id"),
                createdAt = createdAt?.takeIf { it > 0 },
                outcomes = outcomesList,
                predictionWindowSeconds = predictionWindowSeconds,
                status = eventStatus,
                title = prediction.optionalString("title"),
                winningOutcomeId = prediction.optionalString("winning_outcome_id"),
                startedAt = startedAt?.takeIf { it > 0 },
                locksAt = locksAt?.takeIf { it > 0 },
                lockedAt = lockedAt?.takeIf { it > 0 },
                endedAt = endedAt?.takeIf { it > 0 } ?: if (PredictionState.isFinalStatus(eventStatus)) observedAt else null,
                observedAt = observedAt,
            )
        } else null
    }

    class PlaybackMessage(
        val live: Boolean? = null,
        val serverTime: Long? = null,
        val viewers: Int? = null,
    )

    class StreamInfo(
        val title: String? = null,
        val gameId: String? = null,
        val gameName: String? = null,
    )

    class PointsEarned(
        val pointsGained: Int? = null,
        val reasonCode: String? = null,
        val timestamp: Long? = null,
        val absoluteBalance: Int? = null,
        val streakCount: Int? = null,
        val messageId: String? = null,
        val fullMsg: String? = null,
    )
}
