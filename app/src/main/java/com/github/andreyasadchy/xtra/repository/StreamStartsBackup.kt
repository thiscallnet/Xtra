package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.ChannelStreamStart
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON form of the stream start history used inside settings backups. */
object StreamStartsBackup {
    /** Restored history waits here until [ChannelStreamStartsRepository] imports it on first use. */
    const val PENDING_IMPORT_FILE = "stream-starts-import.json"

    private const val FORMAT_VERSION = 1
    private const val MAX_ROWS = 500_000
    private const val MAX_CHANNEL_ID_LENGTH = 64
    private const val FUTURE_TOLERANCE_MS = 24L * 60 * 60_000L

    fun write(file: File, starts: List<ChannelStreamStart>) {
        val rows = JSONArray()
        // Keep the newest rows so an oversized history never fails the whole backup.
        starts.sortedByDescending { it.startedAt }.take(MAX_ROWS).forEach { start ->
            rows.put(JSONArray().put(start.channelId).put(start.startedAt))
        }
        file.writeText(JSONObject().put("version", FORMAT_VERSION).put("starts", rows).toString())
    }

    /** Parses and validates a history file, throwing if any part of it is malformed. */
    fun read(file: File, nowMs: Long = System.currentTimeMillis()): List<ChannelStreamStart> {
        val json = JSONObject(file.readText())
        require(json.optInt("version") == FORMAT_VERSION) { "Unsupported stream start history version" }
        val rows = json.getJSONArray("starts")
        require(rows.length() <= MAX_ROWS) { "Stream start history is too large" }
        return List(rows.length()) { index ->
            val row = rows.getJSONArray(index)
            require(row.length() == 2) { "Stream start history has a malformed row" }
            val channelId = row.getString(0)
            require(channelId.isNotBlank() && channelId.length <= MAX_CHANNEL_ID_LENGTH && channelId.none(Char::isWhitespace)) {
                "Stream start history has an invalid channel"
            }
            val startedAt = row.getLong(1)
            require(startedAt in 1..(nowMs + FUTURE_TOLERANCE_MS)) { "Stream start history has an invalid time" }
            ChannelStreamStart(channelId, startedAt)
        }
    }
}
