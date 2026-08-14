package com.github.andreyasadchy.xtra.ui.multiview

import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.multiview.playback.MultiviewQualityMode
import com.github.andreyasadchy.xtra.ui.multiview.ui.MultiviewLayoutMode
import org.json.JSONArray
import org.json.JSONObject

/** Small JSON store for restoring a multiview after navigation or process recreation. */
object MultiviewSessionStore {
    fun encode(state: MultiviewSessionState): String {
        return JSONObject().apply {
            put("streams", JSONArray().apply { state.streams.forEach { put(encodeStream(it)) } })
            putNullable("activeIdentity", state.activeIdentity)
            putNullable("focusedIdentity", state.focusedIdentity)
            put("layoutMode", state.layoutMode.name)
            putNullable("layoutBeforeFocus", state.layoutBeforeFocus?.name)
            put("fillVideo", state.fillVideo)
            put("chatVisible", state.chatVisible)
            put("combinedChat", state.combinedChat)
            putNullable("chatIdentity", state.chatIdentity)
            put("qualityMode", state.qualityMode.name)
            put("qualityOverrides", JSONObject().apply {
                state.qualityOverrides.forEach { (identity, quality) -> put(identity, quality) }
            })
            put("audioVolumes", JSONObject().apply {
                state.audioVolumes.forEach { (identity, volume) -> put(identity, volume.toDouble()) }
            })
        }.toString()
    }

    fun decode(raw: String?): MultiviewSessionState? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            JSONObject(raw).let { json ->
                val streams = json.optJSONArray("streams")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.let(::decodeStream)
                    }
                }.orEmpty()
                MultiviewSessionState(
                    streams = streams,
                    activeIdentity = json.stringOrNull("activeIdentity"),
                    focusedIdentity = json.stringOrNull("focusedIdentity"),
                    layoutMode = json.enumOrDefault("layoutMode", MultiviewLayoutMode.AUTO),
                    layoutBeforeFocus = json.enumOrNull("layoutBeforeFocus"),
                    fillVideo = json.optBoolean("fillVideo", false),
                    chatVisible = json.optBoolean("chatVisible", false),
                    combinedChat = json.optBoolean("combinedChat", false),
                    chatIdentity = json.stringOrNull("chatIdentity"),
                    qualityMode = json.enumOrDefault("qualityMode", MultiviewQualityMode.AUTO),
                    qualityOverrides = json.stringMap("qualityOverrides"),
                    audioVolumes = json.floatMap("audioVolumes"),
                )
            }
        }.getOrNull()
    }

    private fun encodeStream(stream: Stream): JSONObject = JSONObject().apply {
        putNullable("id", stream.id)
        putNullable("channelId", stream.channelId)
        putNullable("channelLogin", stream.channelLogin)
        putNullable("channelName", stream.channelName)
        putNullable("channelImageURL", stream.channelImageURL)
        putNullable("gameId", stream.gameId)
        putNullable("gameSlug", stream.gameSlug)
        putNullable("gameName", stream.gameName)
        putNullable("title", stream.title)
        putNullable("thumbnailURL", stream.thumbnailURL)
        putNullable("createdAt", stream.createdAt)
        stream.viewerCount?.let { put("viewerCount", it) }
        put("tags", JSONArray().apply { stream.tags.orEmpty().forEach(::put) })
    }

    private fun decodeStream(json: JSONObject): Stream? {
        val id = json.stringOrNull("id")
        val channelId = json.stringOrNull("channelId")
        val channelLogin = json.stringOrNull("channelLogin")
        if (id == null && channelId == null && channelLogin == null) return null
        val tags = json.optJSONArray("tags")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
        }
        return Stream(
            id = id,
            channelId = channelId,
            channelLogin = channelLogin,
            channelName = json.stringOrNull("channelName"),
            channelImageURL = json.stringOrNull("channelImageURL"),
            gameId = json.stringOrNull("gameId"),
            gameSlug = json.stringOrNull("gameSlug"),
            gameName = json.stringOrNull("gameName"),
            title = json.stringOrNull("title"),
            thumbnailURL = json.stringOrNull("thumbnailURL"),
            createdAt = json.stringOrNull("createdAt"),
            viewerCount = json.optInt("viewerCount").takeIf { json.has("viewerCount") },
            tags = tags,
        )
    }

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        return optString(key).takeUnless { it.isBlank() || it == JSONObject.NULL.toString() }
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumOrDefault(key: String, default: T): T {
        return enumOrNull<T>(key) ?: default
    }

    private inline fun <reified T : Enum<T>> JSONObject.enumOrNull(key: String): T? {
        return stringOrNull(key)?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() }
    }

    private fun JSONObject.stringMap(key: String): Map<String, String> {
        val objectValue = optJSONObject(key) ?: return emptyMap()
        return objectValue.keys().asSequence().mapNotNull { name ->
            objectValue.optString(name).takeIf(String::isNotBlank)?.let { name to it }
        }.toMap()
    }

    private fun JSONObject.floatMap(key: String): Map<String, Float> {
        val objectValue = optJSONObject(key) ?: return emptyMap()
        return objectValue.keys().asSequence().mapNotNull { name ->
            if (!objectValue.has(name)) return@mapNotNull null
            name to objectValue.optDouble(name, 0.0).toFloat().coerceIn(0f, 1f)
        }.toMap()
    }
}
