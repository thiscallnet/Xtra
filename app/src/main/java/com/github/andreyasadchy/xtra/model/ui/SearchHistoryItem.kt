package com.github.andreyasadchy.xtra.model.ui

import androidx.room.Entity
import androidx.room.Index
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.pow

/**
 * A channel, category or video the user actually opened from search, not the keywords they typed.
 * [refId] is the Twitch channel/game/video id when known, otherwise the login/slug.
 * [payload] keeps what is needed to reopen a video without a network lookup.
 */
@Entity(
    tableName = "search_history",
    primaryKeys = ["kind", "refId"],
    indices = [Index(value = ["lastOpenedAt"])],
)
class SearchHistoryItem(
    val kind: String,
    val refId: String,
    val title: String,
    /** Channel login or game slug. */
    val slug: String?,
    val imageUrl: String?,
    val openCount: Int,
    val lastOpenedAt: Long,
    val payload: String? = null,
) {
    val isChannel get() = kind == KIND_CHANNEL
    val isGame get() = kind == KIND_GAME
    val isVideo get() = kind == KIND_VIDEO

    /** Rebuilds the video this entry was saved from; null for other kinds or unreadable data. */
    fun toVideo(): Video? {
        if (!isVideo) return null
        val json = try { JSONObject(payload ?: return null) } catch (_: JSONException) { return null }
        fun str(key: String) = json.optString(key, "").takeIf { it.isNotEmpty() }
        return Video(
            id = refId,
            channelId = str("channelId"),
            channelLogin = str("channelLogin"),
            channelName = str("channelName"),
            channelImageURL = str("channelImageURL"),
            gameId = str("gameId"),
            gameSlug = str("gameSlug"),
            gameName = str("gameName"),
            title = title,
            thumbnailURL = str("thumbnailURL"),
            createdAt = str("createdAt"),
            durationSeconds = if (json.has("durationSeconds")) json.optInt("durationSeconds") else null,
            type = str("type"),
            animatedPreviewURL = str("animatedPreviewURL"),
        )
    }

    /** Channel name shown under a video entry. */
    fun videoChannelName(): String? = try {
        JSONObject(payload ?: "").optString("channelName", "").takeIf { it.isNotEmpty() }
    } catch (_: JSONException) { null }

    /** Frecency: repeated opens raise the score, time halves it every [HALF_LIFE_DAYS]. */
    fun score(now: Long): Double {
        val ageDays = ((now - lastOpenedAt).coerceAtLeast(0L)) / DAY_MS.toDouble()
        return (1.0 + 0.6 * ln(openCount.coerceAtLeast(1).toDouble())) * 0.5.pow(ageDays / HALF_LIFE_DAYS)
    }

    /** 0 = no match, higher = better. Prefix beats word-start beats substring. */
    fun matchRank(query: String): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        return maxOf(fieldRank(title.lowercase(), q), fieldRank(slug?.lowercase().orEmpty(), q))
    }

    private fun fieldRank(field: String, q: String): Int = when {
        field.isEmpty() -> 0
        field == q -> 4
        field.startsWith(q) -> 3
        field.split(' ', '_', '-', ':').any { it.startsWith(q) } -> 2
        field.contains(q) -> 1
        else -> 0
    }

    companion object {
        const val KIND_CHANNEL = "channel"
        const val KIND_GAME = "game"
        const val KIND_VIDEO = "video"
        /** Only used for live stream results in the All tab; never saved. */
        const val KIND_STREAM = "stream"
        const val MAX_STORED = 60
        const val MAX_STORED_VIDEOS = 20
        private const val DAY_MS = 86_400_000L
        private const val HALF_LIFE_DAYS = 14.0

        fun channel(id: String?, login: String?, name: String?, image: String?): SearchHistoryItem? {
            val ref = id?.takeIf { it.isNotBlank() } ?: login?.takeIf { it.isNotBlank() } ?: return null
            val title = name?.takeIf { it.isNotBlank() } ?: login?.takeIf { it.isNotBlank() } ?: return null
            return SearchHistoryItem(KIND_CHANNEL, ref, title, login, image, 1, System.currentTimeMillis())
        }

        fun video(video: Video): SearchHistoryItem? {
            val id = video.id?.takeIf { it.isNotBlank() } ?: return null
            val title = video.title?.takeIf { it.isNotBlank() } ?: return null
            val payload = JSONObject().apply {
                put("channelId", video.channelId)
                put("channelLogin", video.channelLogin)
                put("channelName", video.channelName)
                put("channelImageURL", video.channelImageURL)
                put("gameId", video.gameId)
                put("gameSlug", video.gameSlug)
                put("gameName", video.gameName)
                put("thumbnailURL", video.thumbnailURL)
                put("createdAt", video.createdAt)
                video.durationSeconds?.let { put("durationSeconds", it) }
                put("type", video.type)
                put("animatedPreviewURL", video.animatedPreviewURL)
            }.toString()
            return SearchHistoryItem(KIND_VIDEO, id, title, null, video.thumbnail, 1, System.currentTimeMillis(), payload)
        }

        fun game(id: String?, slug: String?, name: String?, boxArt: String?): SearchHistoryItem? {
            val ref = id?.takeIf { it.isNotBlank() } ?: slug?.takeIf { it.isNotBlank() } ?: return null
            val title = name?.takeIf { it.isNotBlank() } ?: return null
            return SearchHistoryItem(KIND_GAME, ref, title, slug, boxArt, 1, System.currentTimeMillis())
        }
    }
}

/** Orders by frecency; when [query] is set, only matches, best match first then frecency. */
fun List<SearchHistoryItem>.ranked(query: String = "", now: Long = System.currentTimeMillis()): List<SearchHistoryItem> {
    if (query.isBlank()) return map { it to it.score(now) }.sortedByDescending { it.second }.map { it.first }
    return map { Triple(it, it.matchRank(query), it.score(now)) }
        .filter { it.second > 0 }
        .sortedWith(compareByDescending<Triple<SearchHistoryItem, Int, Double>> { it.second }.thenByDescending { it.third })
        .map { it.first }
}
