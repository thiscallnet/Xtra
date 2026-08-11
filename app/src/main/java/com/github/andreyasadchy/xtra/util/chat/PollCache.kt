package com.github.andreyasadchy.xtra.util.chat

import android.content.SharedPreferences
import com.github.andreyasadchy.xtra.model.chat.Poll
import org.json.JSONArray
import org.json.JSONObject

/** Small bounded per-channel cache for the latest observed poll. */
object PollCache {
    private const val ENTRY_PREFIX = "latest_poll_"
    private const val INDEX_KEY = "latest_poll_index"
    private const val MAX_ENTRIES = 32
    private const val MAX_AGE_MILLIS = 90L * 24L * 60L * 60L * 1000L

    fun load(preferences: SharedPreferences, channelId: String, now: Long = System.currentTimeMillis()): Poll? {
        val poll = preferences.getString(ENTRY_PREFIX + channelId, null)
            ?.let(::decode)
            ?.let { PollState.normalizeCached(it, now) }
        if (poll == null) {
            preferences.edit().remove(ENTRY_PREFIX + channelId).apply()
        }
        return poll
    }

    fun save(
        preferences: SharedPreferences,
        channelId: String,
        poll: Poll,
        now: Long = System.currentTimeMillis(),
    ) {
        val index = readIndex(preferences).toMutableMap()
        index[channelId] = now
        val cutoff = now - MAX_AGE_MILLIS
        index.filterValues { it < cutoff }
            .keys
            .toList()
            .forEach {
                index.remove(it)
                preferences.edit().remove(ENTRY_PREFIX + it).apply()
            }
        while (index.size > MAX_ENTRIES) {
            index.minByOrNull { it.value }?.key?.let { oldest ->
                index.remove(oldest)
                preferences.edit().remove(ENTRY_PREFIX + oldest).apply()
            } ?: break
        }
        preferences.edit()
            .putString(ENTRY_PREFIX + channelId, encode(poll))
            .putString(INDEX_KEY, JSONObject(index).toString())
            .apply()
    }

    internal fun encode(poll: Poll): String = JSONObject().apply {
        putNullable("id", poll.id)
        putNullable("title", poll.title)
        putNullable("status", poll.status)
        putNullable("total_votes", poll.totalVotes)
        putNullable("remaining_ms", poll.remainingMilliseconds)
        put("channel_points_enabled", poll.channelPointsVotingEnabled)
        putNullable("channel_points_per_vote", poll.channelPointsPerVote)
        put("bits_enabled", poll.bitsVotingEnabled)
        putNullable("bits_per_vote", poll.bitsPerVote)
        putNullable("started_at", poll.startedAt)
        putNullable("ends_at", poll.endsAt)
        putNullable("ended_at", poll.endedAt)
        putNullable("duration_seconds", poll.durationSeconds)
        putNullable("observed_at", poll.observedAt)
        put("choices", JSONArray().apply {
            poll.choices.orEmpty().forEach { choice ->
                put(JSONObject().apply {
                    putNullable("id", choice.id)
                    putNullable("title", choice.title)
                    putNullable("votes", choice.totalVotes)
                    putNullable("channel_points_votes", choice.channelPointsVotes)
                    putNullable("bits_votes", choice.bitsVotes)
                })
            }
        })
    }.toString()

    internal fun decode(value: String): Poll? = runCatching {
        val json = JSONObject(value)
        val choices = json.optJSONArray("choices")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val choice = array.optJSONObject(index) ?: continue
                    add(
                        Poll.PollChoice(
                            id = choice.optionalString("id"),
                            title = choice.optionalString("title"),
                            totalVotes = choice.optionalInt("votes"),
                            channelPointsVotes = choice.optionalInt("channel_points_votes"),
                            bitsVotes = choice.optionalInt("bits_votes"),
                        ),
                    )
                }
            }
        }
        Poll(
            id = json.optionalString("id"),
            title = json.optionalString("title"),
            status = json.optionalString("status"),
            choices = choices,
            totalVotes = json.optionalInt("total_votes"),
            remainingMilliseconds = json.optionalLong("remaining_ms"),
            channelPointsVotingEnabled = json.optBoolean("channel_points_enabled", false),
            channelPointsPerVote = json.optionalInt("channel_points_per_vote"),
            bitsVotingEnabled = json.optBoolean("bits_enabled", false),
            bitsPerVote = json.optionalInt("bits_per_vote"),
            startedAt = json.optionalLong("started_at"),
            endsAt = json.optionalLong("ends_at"),
            endedAt = json.optionalLong("ended_at"),
            durationSeconds = json.optionalInt("duration_seconds"),
            observedAt = json.optionalLong("observed_at"),
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
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optionalInt(key: String): Int? {
        if (isNull(key) || !has(key)) return null
        return opt(key)?.toString()?.toDoubleOrNull()?.toInt()
    }

    private fun JSONObject.optionalLong(key: String): Long? {
        if (isNull(key) || !has(key)) return null
        return opt(key)?.toString()?.toDoubleOrNull()?.toLong()
    }
}
