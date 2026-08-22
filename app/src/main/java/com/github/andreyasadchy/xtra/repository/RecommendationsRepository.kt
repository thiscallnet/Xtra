package com.github.andreyasadchy.xtra.repository

import android.content.Context
import android.util.Log
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.model.gql.stream.StreamsResponse
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Provides a real Twitch recommendation source with a documented-data fallback. */
class RecommendationsRepository(
    private val context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
) {

    private val cacheMutex = Mutex()
    // The cache entry keeps identity, data, source, and expiry inseparable.
    private var cache: RecommendationCache? = null
    // Diagnostic state only; it is never used to decide whether cached data is valid.
    var lastSource: RecommendationSource = RecommendationSource.UNAVAILABLE
        private set

    suspend fun getLiveRecommendations(limit: Int, excludedChannelIds: Set<String> = emptySet()): List<Stream> {
        val now = System.currentTimeMillis()
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        val accountKey = RecommendationAccountKey(
            userId = context.tokenPrefs().getString(C.USER_ID, null),
            username = context.tokenPrefs().getString(C.USERNAME, null),
            authenticated = !headers[C.HEADER_TOKEN].isNullOrBlank(),
            // Keep the identity in memory only; this also protects the cache
            // while account preferences are still being written during login.
            authIdentity = headers[C.HEADER_TOKEN]?.hashCode(),
        )
        cacheMutex.withLock {
            cache
                ?.takeIf { it.accountKey == accountKey && it.expiresAt > now }
                ?.let { entry ->
                    lastSource = entry.source
                    return entry.recommendations
                        .filterNot { it.channelId in excludedChannelIds }
                        .take(limit)
                        .also { debug("source=${entry.source} cache-hit count=${it.size}") }
                }
        }
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val personalized = try {
            val response = graphQLRepository.loadPersonalSections(networkLibrary, headers)
            if (response["errors"]?.jsonArray?.isNotEmpty() == true) {
                throw IllegalStateException("PersonalSections returned GraphQL errors")
            }
            parsePersonalSections(response)
                .filterNot { it.channelId in excludedChannelIds }
                .also { debug("PersonalSections parsed count=${it.size}") }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            debugFailure("PersonalSections failed; using fallback", error)
            null
        }
        val source = if (!personalized.isNullOrEmpty()) {
            RecommendationSource.PERSONALIZED
        } else {
            RecommendationSource.FALLBACK
        }
        lastSource = source
        val result = if (!personalized.isNullOrEmpty()) {
            personalized
        } else {
            debug("source=FALLBACK reason=${if (personalized == null) "personalized-error" else "personalized-empty"}")
            try {
                fallback(networkLibrary, headers, limit, excludedChannelIds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                debugFailure("Fallback recommendations failed", error)
                emptyList()
            }
        }
            .filterNot { it.channelId in excludedChannelIds }
            .distinctBy { it.channelId ?: it.id }
            .take(limit)
        val resultSource = if (result.isEmpty()) RecommendationSource.UNAVAILABLE else source
        lastSource = resultSource
        debug("source=$resultSource count=${result.size}")
        if (result.isNotEmpty()) {
            publishCacheIfCurrent(
                requestAccountKey = accountKey,
                recommendations = result,
                source = resultSource,
            )
        }
        return result
    }

    private suspend fun publishCacheIfCurrent(
        requestAccountKey: RecommendationAccountKey,
        recommendations: List<Stream>,
        source: RecommendationSource,
    ) {
        cacheMutex.withLock {
            // A request may finish after login/logout or account switching. Never
            // publish its result into the cache of the account that is current now.
            if (currentAccountKey() == requestAccountKey) {
                cache = RecommendationCache(
                    accountKey = requestAccountKey,
                    recommendations = recommendations,
                    source = source,
                    expiresAt = System.currentTimeMillis() + RECOMMENDATIONS_CACHE_MILLIS,
                )
            } else {
                debug("cache publication skipped after account change")
            }
        }
    }

    private fun currentAccountKey(): RecommendationAccountKey {
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        return RecommendationAccountKey(
            userId = context.tokenPrefs().getString(C.USER_ID, null),
            username = context.tokenPrefs().getString(C.USERNAME, null),
            authenticated = !headers[C.HEADER_TOKEN].isNullOrBlank(),
            authIdentity = headers[C.HEADER_TOKEN]?.hashCode(),
        )
    }

    private suspend fun fallback(
        networkLibrary: String?,
        headers: Map<String, String>,
        limit: Int,
        excludedChannelIds: Set<String>,
    ): List<Stream> {
        val followedIds = localChannelFollowsRepository.getAll().mapNotNull { it.userId }.toSet() + excludedChannelIds
        val response = graphQLRepository.loadTopStreams(
            networkLibrary = networkLibrary,
            headers = headers,
            sort = "RELEVANCE",
            tags = null,
            languages = null,
            limit = (limit * 3).coerceAtMost(30),
            cursor = null,
        )
        return response.data?.streams?.edges.orEmpty().mapNotNull { it.node.toStream() }
            .filterNot { it.channelId in followedIds }
            .take(limit)
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

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d(LOG_TAG, message)
    }

    private fun debugFailure(message: String, error: Exception) {
        if (BuildConfig.DEBUG) {
            Log.w(LOG_TAG, "$message: ${error::class.simpleName}: ${error.message}")
        }
    }

    private companion object {
        const val LOG_TAG = "FollowingRecommendations"
        const val RECOMMENDATIONS_CACHE_MILLIS = 5 * 60 * 1000L
    }
}

private data class RecommendationCache(
    val accountKey: RecommendationAccountKey,
    val recommendations: List<Stream>,
    val source: RecommendationSource,
    val expiresAt: Long,
)

internal data class RecommendationAccountKey(
    val userId: String?,
    val username: String?,
    val authenticated: Boolean,
    val authIdentity: Int?,
)

enum class RecommendationSource {
    PERSONALIZED,
    FALLBACK,
    UNAVAILABLE,
}

internal fun parsePersonalSections(root: JsonObject): List<Stream> {
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

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
