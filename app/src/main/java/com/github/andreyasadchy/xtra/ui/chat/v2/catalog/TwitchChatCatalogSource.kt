package com.github.andreyasadchy.xtra.ui.chat.v2.catalog

import android.content.Context
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.repository.PlayerRepository
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges the already-used catalog endpoints into the immutable v2 catalog.
 * Each provider is independently best-effort: a failed provider is omitted
 * from the result so ChatCatalogRepository keeps its last-good map.
 */
class TwitchChatCatalogSource(
    context: Context,
    private val playerRepository: PlayerRepository,
    private val channelId: String,
    private val channelLogin: String,
) : ChatCatalogSource {
    private val context = context.applicationContext

    override suspend fun load(): ChatCatalogLoadResult = withContext(Dispatchers.IO) {
        val network = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val helix = TwitchApiHelper.getHelixHeaders(context)
        val gql = TwitchApiHelper.getGQLHeaders(context, true)
        val useWebp = true
        ChatCatalogLoadResult(
            twitch = ChatCatalogProviderUpdate(emptyMap()),
            sevenTv = provider { loadSevenTv(network, useWebp) },
            bttv = provider { loadBttv(network, useWebp) },
            ffz = provider { loadFfz(network, useWebp) },
            badges = provider {
                val global = playerRepository.loadGlobalBadges(network, helix, gql, "4")
                val channel = playerRepository.loadChannelBadges(network, helix, gql, channelId, channelLogin, "4")
                (global + channel).associateBy { "${it.setId}:${it.version}" }.mapValues { (_, badge) -> badge.toCatalog() }
            },
        )
    }

    private suspend fun loadSevenTv(network: String?, useWebp: Boolean): Map<String, ChatCatalogEmote> {
        val global = playerRepository.loadSTVEmoteSet(
            playerRepository.loadGlobalSTVEmoteSetResponse(network), useWebp, true,
        ).second
        // A channel failure must fail the provider as a whole. Returning only the global set
        // would silently erase the last-good channel aliases on the next refresh.
        val user = playerRepository.loadSTVUser(playerRepository.loadSTVUserResponse(network, channelId), useWebp)
        val channel = user.second ?: if (!user.first.isNullOrBlank()) {
            playerRepository.loadSTVEmoteSet(
                playerRepository.loadSTVEmoteSetResponse(network, user.first!!), useWebp, false,
            ).second
        } else emptyList()
        return emoteMap(global + channel, ChatAssetProvider.SEVEN_TV)
    }

    private suspend fun loadBttv(network: String?, useWebp: Boolean): Map<String, ChatCatalogEmote> {
        val global = playerRepository.loadBTTVEmotes(
            playerRepository.loadGlobalBTTVEmotesResponse(network), useWebp,
        )
        val channel = playerRepository.loadBTTVEmotes(
            playerRepository.loadBTTVEmotesResponse(network, channelId), useWebp,
        )
        return emoteMap(global + channel, ChatAssetProvider.BTTV)
    }

    private suspend fun loadFfz(network: String?, useWebp: Boolean): Map<String, ChatCatalogEmote> {
        val global = playerRepository.loadGlobalFFZEmotes(
            playerRepository.loadGlobalFFZEmotesResponse(network), useWebp,
        )
        val channel = playerRepository.loadFFZEmotes(
            playerRepository.loadFFZEmotesResponse(network, channelId), useWebp,
        )
        return emoteMap(global + channel, ChatAssetProvider.FFZ)
    }

    private fun emoteMap(emotes: List<Emote>, provider: ChatAssetProvider): Map<String, ChatCatalogEmote> = buildMap {
        // Provider response order is stable; first alias wins within a provider.
        emotes.forEach { emote ->
            val name = emote.name?.takeIf { it.isNotBlank() } ?: return@forEach
            if (name in this) return@forEach
            val url = emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x ?: return@forEach
            val width = emote.width?.takeIf { it > 0 } ?: 56
            val height = emote.height?.takeIf { it > 0 } ?: 56
            put(name, ChatCatalogEmote(
                name = name,
                asset = ChatAssetSpec(
                    key = ChatAssetKey(url),
                    sourceWidth = width,
                    sourceHeight = height,
                    targetHeight = 28,
                ),
                provider = provider,
                animated = emote.isAnimated,
                zeroWidth = emote.isOverlayEmote,
            ))
        }
    }

    private fun TwitchBadge.toCatalog(): ChatCatalogBadge {
        val url = url4x ?: url3x ?: url2x ?: url1x
        return ChatCatalogBadge(
            name = "$setId:$version",
            asset = ChatAssetSpec(
                key = ChatAssetKey(url ?: "twitch-badge:$setId:$version"),
                sourceWidth = 18,
                sourceHeight = 18,
                targetHeight = 18,
            ),
            provider = ChatAssetProvider.TWITCH,
            setId = setId,
            versionId = version,
            info = title,
        )
    }

    private suspend fun <T> provider(block: suspend () -> T): ChatCatalogProviderUpdate<T>? = try {
        ChatCatalogProviderUpdate(block())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

/** Structured, versioned last-good catalog storage for one playback channel. */
class TwitchChatCatalogCache(
    context: Context,
    channelId: String,
) : ChatCatalogCache {
    private val file = File(
        File(context.applicationContext.filesDir, "chat-v2/catalog"),
        channelId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json",
    )

    override suspend fun read(): ChatCatalogSnapshot? = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext null
        runCatching { decode(JSONObject(file.readText())) }.getOrNull()
    }

    override suspend fun write(snapshot: ChatCatalogSnapshot) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(encode(snapshot).toString())
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Unable to publish chat catalog cache" }
        }
    }

    private fun encode(snapshot: ChatCatalogSnapshot): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("revision", snapshot.revision)
        put("provider", "combined")
        put("fetchedAt", System.currentTimeMillis())
        put("twitch", encodeEmotes(snapshot.twitch))
        put("sevenTv", encodeEmotes(snapshot.sevenTv))
        put("bttv", encodeEmotes(snapshot.bttv))
        put("ffz", encodeEmotes(snapshot.ffz))
        put("badges", encodeBadges(snapshot.badges))
    }

    private fun encodeEmotes(map: Map<String, ChatCatalogEmote>) = JSONArray().apply {
        map.values.forEach { emote ->
            put(JSONObject().apply {
                put("name", emote.name)
                put("provider", emote.provider.name)
                put("animated", emote.animated)
                put("zeroWidth", emote.zeroWidth)
                put("asset", encodeSpec(emote.asset))
            })
        }
    }

    private fun encodeBadges(map: Map<String, ChatCatalogBadge>) = JSONArray().apply {
        map.values.forEach { badge ->
            put(JSONObject().apply {
                put("name", badge.name)
                put("provider", badge.provider.name)
                put("setId", badge.setId)
                put("versionId", badge.versionId)
                putOpt("info", badge.info)
                put("asset", encodeSpec(badge.asset))
            })
        }
    }

    private fun encodeSpec(spec: ChatAssetSpec): JSONObject = JSONObject().apply {
        put("key", spec.key.value)
        put("sourceWidth", spec.sourceWidth)
        put("sourceHeight", spec.sourceHeight)
        put("targetHeight", spec.targetHeight)
        val encodedOverlays = JSONArray()
        spec.overlays.forEach { overlay -> encodedOverlays.put(encodeSpec(overlay)) }
        put("overlays", encodedOverlays)
    }

    private fun decode(root: JSONObject): ChatCatalogSnapshot {
        check(root.optInt("schemaVersion") == 1)
        fun emotes(name: String): Map<String, ChatCatalogEmote> = buildMap {
            val array = root.optJSONArray(name) ?: return@buildMap
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val emoteName = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                val provider = runCatching { ChatAssetProvider.valueOf(item.optString("provider")) }.getOrNull() ?: continue
                put(emoteName, ChatCatalogEmote(
                    emoteName,
                    decodeSpec(item.optJSONObject("asset")),
                    provider,
                    item.optBoolean("animated"),
                    item.optBoolean("zeroWidth"),
                ))
            }
        }
        val badges = buildMap {
            val array = root.optJSONArray("badges") ?: JSONArray()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val key = "${item.optString("setId")}:${item.optString("versionId")}"
                put(key, ChatCatalogBadge(
                    item.optString("name"),
                    decodeSpec(item.optJSONObject("asset")),
                    ChatAssetProvider.TWITCH,
                    item.optString("setId"),
                    item.optString("versionId"),
                    item.optString("info").takeIf { it.isNotBlank() },
                ))
            }
        }
        return ChatCatalogSnapshot(
            revision = root.optLong("revision", 0L),
            twitch = emotes("twitch"),
            sevenTv = emotes("sevenTv"),
            bttv = emotes("bttv"),
            ffz = emotes("ffz"),
            badges = badges,
        )
    }

    private fun decodeSpec(value: JSONObject?): ChatAssetSpec {
        requireNotNull(value)
        val overlays = value.optJSONArray("overlays")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::decodeSpec) }
        }.orEmpty()
        return ChatAssetSpec(
            key = ChatAssetKey(value.optString("key")),
            sourceWidth = value.optInt("sourceWidth", 56),
            sourceHeight = value.optInt("sourceHeight", 56),
            targetHeight = value.optInt("targetHeight", 28),
            overlays = overlays,
        )
    }
}
