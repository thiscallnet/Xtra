package com.github.andreyasadchy.xtra.repository

import android.content.Context
import android.util.Log
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.gql.stream.StreamsResponse
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionStore
import com.github.andreyasadchy.xtra.repository.auth.PrivateGqlCredential
import com.github.andreyasadchy.xtra.repository.auth.PrivateGqlCredentialType
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Provides a real Twitch recommendation source with a documented-data fallback. */
class RecommendationsRepository(
    private val context: Context,
    private val graphQLRepository: GraphQLRepository,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
) {

    private val cacheMutex = Mutex()
    private val authSessionStore = AuthSessionStore(context.prefs(), context.tokenPrefs())
    private val recommendationClientSessionId = Uuid.random().toString()
    // The cache entry keeps identity, data, source, and expiry inseparable.
    private var cache: RecommendationCache? = null
    // Diagnostic state only; it is never used to decide whether cached data is valid.
    var lastSource: RecommendationSource = RecommendationSource.UNAVAILABLE
        private set

    suspend fun getLiveRecommendations(
        limit: Int,
        excludedChannelIds: Set<String> = emptySet(),
    ): RecommendationResult {
        val now = System.currentTimeMillis()
        val webSessionManager = (context.applicationContext as? XtraApp)
            ?.xtraModule?.twitchWebSessionManager
        val requestContext = recommendationRequestContext(now)
        val accountKey = requestContext.accountKey
        cacheMutex.withLock {
            cache
                ?.takeIf { it.accountKey == accountKey && it.expiresAt > now }
                ?.let { entry ->
                    lastSource = entry.source
                    return RecommendationResult(
                        streams = entry.recommendations
                        .filterNot { it.channelId in excludedChannelIds }
                        .take(limit),
                        source = entry.source,
                        authMode = entry.authMode,
                    ).also { debug("source=${it.source} auth=${it.authMode} cache-hit count=${it.streams.size}") }
                }
        }
        val auth = requestContext.auth
        debug("auth=${auth.mode} userBound=${auth.userId != null}")
        val personalized = if (auth is RecommendationAuth.Anonymous) {
            debug("PersonalSections skipped reason=missing-user-bound-private-credential")
            null
        } else {
            try {
                if (webSessionManager?.isWebSessionActive() == true) {
                    // Do not delay a cache hit. Acquire only when the personalized
                    // authenticated request is actually about to be attempted.
                    webSessionManager.refreshGeckoGqlIdentity()
                }
                val response = graphQLRepository.loadPersonalSections(
                    requestContext.networkLibrary,
                    requestContext.personalizedHeaders,
                )
                val graphqlErrorCount = (response["errors"] as? JsonArray)?.size ?: 0
                if (graphqlErrorCount > 0) {
                    debug("PersonalSections graphqlErrors=$graphqlErrorCount")
                    throw IllegalStateException("PersonalSections returned GraphQL errors")
                }
                val sections = ((response["data"] as? JsonObject)?.get("personalSections") as? JsonArray)
                val itemCount = sections.orEmpty().sumOf { section ->
                    (section as? JsonObject)?.let { (it["items"] as? JsonArray)?.size } ?: 0
                }
                debug("PersonalSections sections=${sections?.size ?: 0} items=$itemCount")
                parsePersonalSections(response)
                    .filterNot { it.channelId in excludedChannelIds }
                    .also { debug("PersonalSections parsed count=${it.size}") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                debugFailure("PersonalSections failed; using fallback", error)
                null
            }
        }
        val source = if (!personalized.isNullOrEmpty()) {
            RecommendationSource.PERSONALIZED
        } else {
            RecommendationSource.FALLBACK
        }
        lastSource = source
        val streams = if (!personalized.isNullOrEmpty()) {
            personalized
        } else {
            debug("source=FALLBACK reason=${if (personalized == null) "personalized-error" else "personalized-empty"}")
            try {
                fallback(
                    networkLibrary = requestContext.networkLibrary,
                    headers = TwitchApiHelper.getPublicRecommendationGQLHeaders(
                        context = context,
                        clientSessionId = recommendationClientSessionId,
                    ),
                    limit = limit,
                    excludedChannelIds = excludedChannelIds,
                )
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
        val resultSource = recommendationSourceFor(personalized, streams)
        lastSource = resultSource
        val result = RecommendationResult(
            streams = streams,
            source = resultSource,
            authMode = auth.mode,
        )
        debug("source=${result.source} auth=${result.authMode} count=${result.streams.size}")
        if (result.streams.isNotEmpty()) {
            publishCacheIfCurrent(
                requestAccountKey = accountKey,
                result = result,
            )
        }
        return result
    }

    private suspend fun publishCacheIfCurrent(
        requestAccountKey: RecommendationAccountKey,
        result: RecommendationResult,
    ) {
        cacheMutex.withLock {
            // A request may finish after login/logout or account switching. Never
            // publish its result into the cache of the account that is current now.
            if (currentAccountKey() == requestAccountKey) {
                cache = RecommendationCache(
                    accountKey = requestAccountKey,
                    recommendations = result.streams,
                    source = result.source,
                    authMode = result.authMode,
                    expiresAt = System.currentTimeMillis() + RECOMMENDATIONS_CACHE_MILLIS,
                )
            } else {
                debug("cache publication skipped after account change")
            }
        }
    }

    private fun currentAccountKey(): RecommendationAccountKey {
        val officialSession = authSessionStore.read()
        val auth = recommendationAuthFor(
            officialUserId = officialSession?.userId,
            credential = authSessionStore.readPrivateGqlCredential(),
        )
        return RecommendationAccountKey(
            userId = officialSession?.userId ?: context.tokenPrefs().getString(C.USER_ID, null),
            username = officialSession?.login ?: context.tokenPrefs().getString(C.USERNAME, null),
            authMode = auth.mode,
            credentialIdentity = auth.accessToken?.hashCode(),
        )
    }

    private fun recommendationRequestContext(nowMillis: Long): RecommendationRequestContext {
        val officialSession = authSessionStore.read()
        val auth = recommendationAuthFor(
            officialUserId = officialSession?.userId,
            credential = authSessionStore.readPrivateGqlCredential(),
        )
        return RecommendationRequestContext(
            auth = auth,
            accountKey = RecommendationAccountKey(
                userId = officialSession?.userId ?: context.tokenPrefs().getString(C.USER_ID, null),
                username = officialSession?.login ?: context.tokenPrefs().getString(C.USERNAME, null),
                authMode = auth.mode,
                credentialIdentity = auth.accessToken?.hashCode(),
            ),
            personalizedHeaders = TwitchApiHelper.getPersonalizedRecommendationGQLHeaders(
                context = context,
                clientId = auth.clientId,
                accessToken = auth.accessToken,
                clientSessionId = recommendationClientSessionId,
            ),
            networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
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
            Log.w(LOG_TAG, "$message: ${error::class.simpleName}")
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
    val authMode: RecommendationAuthMode,
    val expiresAt: Long,
)

internal data class RecommendationAccountKey(
    val userId: String?,
    val username: String?,
    val authMode: RecommendationAuthMode,
    val credentialIdentity: Int?,
)

data class RecommendationResult(
    val streams: List<Stream>,
    val source: RecommendationSource,
    val authMode: RecommendationAuthMode,
)

enum class RecommendationAuthMode {
    WEB,
    ANONYMOUS,
}

internal sealed interface RecommendationAuth {
    val mode: RecommendationAuthMode
    val userId: String?
    val clientId: String?
    val accessToken: String?

    data class Web(
        override val userId: String,
        override val clientId: String,
        override val accessToken: String,
    ) : RecommendationAuth {
        override val mode = RecommendationAuthMode.WEB
    }

    data object Anonymous : RecommendationAuth {
        override val mode = RecommendationAuthMode.ANONYMOUS
        override val userId: String? = null
        override val clientId: String? = null
        override val accessToken: String? = null
    }
}

private data class RecommendationRequestContext(
    val auth: RecommendationAuth,
    val accountKey: RecommendationAccountKey,
    val personalizedHeaders: Map<String, String>,
    val networkLibrary: String?,
)

internal fun recommendationAuthFor(
    officialUserId: String?,
    credential: PrivateGqlCredential?,
): RecommendationAuth {
    val matchingCredential = credential?.takeIf {
        !officialUserId.isNullOrBlank() && it.userId == officialUserId
    }
    return when (matchingCredential?.type) {
        PrivateGqlCredentialType.WEB -> RecommendationAuth.Web(
            userId = matchingCredential.userId,
            clientId = matchingCredential.clientId,
            accessToken = matchingCredential.accessToken,
        )
        null -> RecommendationAuth.Anonymous
    }
}

enum class RecommendationSource {
    PERSONALIZED,
    FALLBACK,
    UNAVAILABLE,
}

internal fun recommendationSourceFor(
    personalized: List<Stream>?,
    result: List<Stream>,
): RecommendationSource = when {
    result.isEmpty() -> RecommendationSource.UNAVAILABLE
    !personalized.isNullOrEmpty() -> RecommendationSource.PERSONALIZED
    else -> RecommendationSource.FALLBACK
}

internal fun parsePersonalSections(root: JsonObject): List<Stream> {
    return (root["data"] as? JsonObject)?.array("personalSections").orEmpty()
        .filterIsInstance<JsonObject>()
        .filter { it.string("type") == "RECOMMENDED_SECTION" }
        .flatMap { section ->
            section.array("items").filterIsInstance<JsonObject>().mapNotNull { itemObject ->
                val user = itemObject["user"] as? JsonObject ?: return@mapNotNull null
                val content = itemObject["content"] as? JsonObject ?: return@mapNotNull null
                if (content.string("__typename") != "Stream") return@mapNotNull null
                val streamId = content.string("id") ?: return@mapNotNull null
                val channelId = user.string("id") ?: return@mapNotNull null
                Stream(
                    id = streamId,
                    channelId = channelId,
                    channelLogin = user.string("login"),
                    channelName = user.string("displayName"),
                    channelImageURL = user.string("profileImageURL"),
                    gameId = (content["game"] as? JsonObject)?.string("id"),
                    gameSlug = (content["game"] as? JsonObject)?.string("slug"),
                    gameName = (content["game"] as? JsonObject)?.string("displayName"),
                    title = content.string("title"),
                    thumbnailURL = content.string("previewImageURL"),
                    createdAt = content.string("createdAt"),
                    viewerCount = (content["viewersCount"] as? JsonPrimitive)?.intOrNull,
                    tags = (content["freeformTags"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonObject)?.string("name") },
                )
            }
        }
        .distinctBy { it.channelId ?: it.id }
}

private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
