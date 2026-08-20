package com.github.andreyasadchy.xtra.util.watch

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class WatchCreditSession(
    val broadcastId: String,
    val channelId: String,
    val channelLogin: String,
    val userId: String,
)

object WatchCreditTelemetry {
    const val LOG_TAG = "XtraWatchCredit"

    private val spadeUrlRegex = Regex(
        """(?i)[\"']?(?:spadeUrl|spade_url|beacon_url)[\"']?\s*:\s*[\"'](https?://[^\"'\s<>]+)[\"']""",
    )
    private val settingsUrlRegex = Regex(
        """https://(?:static\.twitchcdn\.net|assets\.twitch\.tv)/config/settings[^\"'\\\s<>]*\.js(?:\?[^\"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE,
    )

    fun buildMinuteWatchedPayload(
        session: WatchCreditSession,
        clientTimeMillis: Long = System.currentTimeMillis(),
        game: String? = null,
        gameId: String? = null,
    ): String = buildJsonArray {
        add(buildJsonObject {
            put("event", "minute-watched")
            putJsonObject("properties") {
                put("broadcast_id", session.broadcastId)
                put("channel_id", session.channelId)
                put("channel", session.channelLogin)
                put("client_time", formatClientTime(clientTimeMillis))
                game?.let { put("game", it) }
                gameId?.let { put("game_id", it) }
                put("hidden", false)
                put("is_live", true)
                put("live", true)
                put("location", "channel")
                put("logged_in", true)
                put("minutes_logged", 1)
                put("muted", false)
                put("player", "site")
                put("user_id", session.userId)
            }
        })
    }.toString()

    fun extractSpadeUrl(content: String): String? = spadeUrlRegex
        .find(content.replace("\\/", "/"))
        ?.groupValues
        ?.getOrNull(1)

    fun extractSettingsUrl(content: String): String? = settingsUrlRegex
        .find(content.replace("\\/", "/"))
        ?.value

    fun isSuccessfulStatus(statusCode: Int): Boolean = statusCode in 200..299

    private fun formatClientTime(timeMillis: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timeMillis))
}
