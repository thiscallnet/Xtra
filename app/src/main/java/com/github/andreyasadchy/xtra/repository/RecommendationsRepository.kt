package com.github.andreyasadchy.xtra.repository

import android.content.Context
import com.github.andreyasadchy.xtra.model.gql.stream.StreamsResponse
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Provides a real Twitch recommendation source with a documented-data fallback. */
class RecommendationsRepository(
    private val context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
) {

    private var cachedRecommendations: List<Stream> = emptyList()
    private var cacheExpiresAt = 0L

    suspend fun getLiveRecommendations(limit: Int): List<Stream> {
        val now = System.currentTimeMillis()
        if (cacheExpiresAt > now) {
            return cachedRecommendations.take(limit)
        }
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val personalized = runCatching {
            parsePersonalSections(graphQLRepository.loadPersonalSections(networkLibrary, headers))
        }.getOrNull().orEmpty()
        val result = (personalized.ifEmpty { fallback(networkLibrary, headers, limit) })
            .distinctBy { it.channelId ?: it.id }
            .take(limit)
        if (result.isNotEmpty()) {
            cachedRecommendations = result
            cacheExpiresAt = now + RECOMMENDATIONS_CACHE_MILLIS
        }
        return result
    }

    private suspend fun fallback(networkLibrary: String?, headers: Map<String, String>, limit: Int): List<Stream> {
        val followedIds = localChannelFollowsRepository.getAll().mapNotNull { it.userId }.toSet()
        val response = graphQLRepository.loadTopStreams(
            networkLibrary = networkLibrary,
            headers = headers,
            sort = "VIEWER_COUNT",
            tags = null,
            languages = null,
            limit = (limit * 3).coerceAtMost(30),
            cursor = null,
        )
        return response.data?.streams?.edges.orEmpty().mapNotNull { it.node.toStream() }
            .filterNot { it.channelId in followedIds }
            .take(limit)
    }

    private fun parsePersonalSections(root: JsonObject): List<Stream> {
        return root["data"]?.jsonObject?.get("personalSections")?.jsonArray.orEmpty()
            .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "RECOMMENDED_SECTION" }
            .flatMap { section ->
                section.jsonObject["items"]?.jsonArray.orEmpty().mapNotNull { item ->
                    val itemObject = item.jsonObject
                    val user = itemObject["user"]?.jsonObject ?: return@mapNotNull null
                    val content = itemObject["content"]?.jsonObject ?: return@mapNotNull null
                    if (content["__typename"]?.jsonPrimitive?.contentOrNull != "Stream") return@mapNotNull null
                    Stream(
                        id = content.string("id"),
                        channelId = user.string("id"),
                        channelLogin = user.string("login"),
                        channelName = user.string("displayName"),
                        channelImageURL = user.string("profileImageURL"),
                        gameId = content["game"]?.jsonObject?.string("id"),
                        gameSlug = content["game"]?.jsonObject?.string("slug"),
                        gameName = content["game"]?.jsonObject?.string("displayName"),
                        title = content.string("title"),
                        thumbnailURL = content.string("previewImageURL"),
                        createdAt = content.string("createdAt"),
                        viewerCount = content["viewersCount"]?.jsonPrimitive?.intOrNull,
                        tags = content["freeformTags"]?.jsonArray?.mapNotNull { it.jsonObject.string("name") },
                    )
                }
            }
    }

    private fun StreamsResponse.Stream.toStream(): Stream? {
        val broadcaster = broadcaster ?: return null
        return Stream(
            id = id,
            channelId = broadcaster.id,
            channelLogin = broadcaster.login,
            channelName = broadcaster.displayName,
            channelImageURL = broadcaster.profileImageURL,
            gameId = game?.id,
            gameSlug = game?.slug,
            gameName = game?.displayName,
            title = title,
            thumbnailURL = previewImageURL,
            createdAt = createdAt,
            viewerCount = viewersCount,
            tags = freeformTags?.mapNotNull { it.name },
        )
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private companion object {
        const val RECOMMENDATIONS_CACHE_MILLIS = 5 * 60 * 1000L
    }
}
