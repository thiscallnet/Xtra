package com.github.andreyasadchy.xtra.repository

import android.util.Log
import com.github.andreyasadchy.xtra.db.NotificationEventsDao
import com.github.andreyasadchy.xtra.db.NotificationUsersDao
import com.github.andreyasadchy.xtra.db.ShownNotificationsDao
import com.github.andreyasadchy.xtra.model.NotificationEvent
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.time.Instant

internal fun isLiveNotificationBaselineAuthenticationMissing(
    cachedChannelCount: Int,
    gqlHeaders: Map<String, String>,
    helixHeaders: Map<String, String>,
): Boolean = cachedChannelCount > 0 &&
        gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() &&
        helixHeaders[C.HEADER_TOKEN].isNullOrBlank()

internal fun streamIdsMissingProfileImages(streams: List<Stream>): List<String> = streams.mapNotNull { stream ->
    stream.channelId?.takeIf { it.isNotBlank() }?.takeIf { stream.channelImageURL.isNullOrBlank() }
}.distinct()

internal fun mergeProfileImages(streams: List<Stream>, profileUrls: Map<String, String>): List<Stream> {
    streams.forEach { stream ->
        stream.channelId?.let { profileUrls[it] }?.let { imageUrl ->
            if (stream.channelImageURL.isNullOrBlank()) {
                stream.channelImageURL = imageUrl
            }
        }
    }
    return streams
}

private val liveNotificationGraphQlAuthPattern = Regex(
    "(?i)\\b(?:unauthori[sz]ed|unauthenticated|authentication required|login required|forbidden|access denied|invalid (?:oauth|access|refresh) token|(?:oauth|access|refresh) token(?: is)? (?:invalid|expired)|invalid token|not authorized|permission denied|HTTP\\s+(?:401|403)|(?:401|403))\\b"
)

internal fun isFatalLiveNotificationGraphQlError(
    errorMessage: String?,
    requiredDataAvailable: Boolean,
): Boolean = !requiredDataAvailable || liveNotificationGraphQlAuthPattern.containsMatchIn(errorMessage.orEmpty())

internal sealed interface NotificationPreferenceLoadResult {
    data class Loaded(val enabledIds: Set<String>) : NotificationPreferenceLoadResult
    data object WebSessionUnavailable : NotificationPreferenceLoadResult
    data object TransientFailure : NotificationPreferenceLoadResult
}

internal enum class NotificationUserSyncResult {
    SUCCESS,
    PREFERENCE_TRANSIENT_FAILURE,
}

internal typealias LiveNotificationStreamsProvider = suspend () -> List<Stream>

private data class GraphQlFollowedChannels(
    val followedIds: Set<String>,
    val preferenceEnabledIds: Set<String>,
)

internal fun isNotificationPreferenceAuthUnavailable(error: Throwable): Boolean = when (error) {
    is MissingAuthenticationException -> true
    is TwitchApiException -> error.statusCode == 401 || error.statusCode == 403
    is GraphQLApiException -> isFatalLiveNotificationGraphQlError(
        errorMessage = error.message,
        requiredDataAvailable = true,
    )
    else -> false
}

class NotificationsRepository(
    private val shownNotificationsDao: ShownNotificationsDao,
    private val notificationUsersDao: NotificationUsersDao,
    private val notificationEventsDao: NotificationEventsDao,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
    private val liveNotificationStreamsProvider: LiveNotificationStreamsProvider? = null,
) {

    private val liveNotificationDeduplicator = LiveNotificationDeduplicator(shownNotificationsDao)

    suspend fun getNewStreams(
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
        helixHeaders: Map<String, String>,
        includeFollowedStreams: Boolean = true,
        preferHelix: Boolean = false,
        enqueueNotificationEvents: Boolean = false,
        onHelixRateLimit: ((HelixRateLimit) -> Unit)? = null,
        onApiUsed: ((String) -> Unit)? = null,
    ): List<Stream> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Stream>()
        var apiUsed = "none"
        val notificationIds = notificationUsersDao.getAll().map { it.channelId }
        if (isLiveNotificationBaselineAuthenticationMissing(
                cachedChannelCount = notificationIds.size,
                gqlHeaders = gqlHeaders,
                helixHeaders = helixHeaders,
            )
        ) {
            return@withContext emptyList()
        }
        if (liveNotificationStreamsProvider != null) {
            list.addAll(liveNotificationStreamsProvider.invoke())
        } else if (notificationIds.isNotEmpty()) {
            val localStreams = if (preferHelix && !helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                try {
                    apiUsed = C.HELIX
                    helixLocal(networkLibrary, helixHeaders, notificationIds, onHelixRateLimit)
                } catch (e: CancellationException) {
                    throw e
                } catch (helixException: Exception) {
                    if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        throw helixException
                    }
                    apiUsed = C.GQL
                    gqlQueryLocal(networkLibrary, gqlHeaders, notificationIds)
                }
            } else if (!gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                try {
                    apiUsed = C.GQL
                    gqlQueryLocal(networkLibrary, gqlHeaders, notificationIds)
                } catch (e: CancellationException) {
                    throw e
                } catch (gqlException: Exception) {
                    if (helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
                        throw gqlException
                    }
                    apiUsed = C.HELIX
                    helixLocal(networkLibrary, helixHeaders, notificationIds, onHelixRateLimit)
                }
            } else {
                apiUsed = C.HELIX
                helixLocal(networkLibrary, helixHeaders, notificationIds, onHelixRateLimit)
            }
            list.addAll(localStreams)
        }
        if (liveNotificationStreamsProvider == null && includeFollowedStreams && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            try {
                if (apiUsed == "none") {
                    apiUsed = C.GQL
                }
                gqlQueryLoad(networkLibrary, gqlHeaders)
                    .filterNot { item -> list.any { it.channelId == item.channelId } }
                    .let(list::addAll)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (list.isEmpty()) {
                    throw e
                }
            }
        }
        val newStreams = liveNotificationDeduplicator.processStreams(list)
        val notificationStreams = if (enqueueNotificationEvents) {
            enrichNewStreamsWithProfiles(
                streams = newStreams,
                networkLibrary = networkLibrary,
                helixHeaders = helixHeaders,
            )
        } else {
            newStreams
        }
        if (enqueueNotificationEvents) {
            notificationStreams.mapNotNull { stream ->
                stream.startedAtMillis()?.let { NotificationEvent.fromStream(stream, it) }
            }.let(notificationEventsDao::insertList)
        }
        onApiUsed?.invoke(apiUsed)
        notificationStreams
    }

    internal suspend fun syncNotificationUsers(
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
        helixHeaders: Map<String, String>,
        userId: String?,
    ): NotificationUserSyncResult = withContext(Dispatchers.IO) {
        val previousIds = notificationUsersDao.getAll().map { it.channelId }
        val hasWebSession = !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()
        val hasHelixSession = !helixHeaders[C.HEADER_TOKEN].isNullOrBlank() && !userId.isNullOrBlank()
        val followedIds: Set<String>
        val preferenceEnabledIds: NotificationPreferenceLoadResult
        when {
            hasWebSession -> {
                // GeckoView supplies Twitch's private GQL session. Prefer it when both
                // credentials exist so notification setup does not depend on a stale Helix grant.
                val followedChannels = loadGraphQlFollowedChannels(networkLibrary, gqlHeaders)
                followedIds = followedChannels.followedIds
                preferenceEnabledIds = NotificationPreferenceLoadResult.Loaded(
                    followedChannels.preferenceEnabledIds,
                )
            }
            hasHelixSession -> {
                val helixFollowedIds = mutableSetOf<String>()
                var offset: String? = null
                do {
                    val response = helixRepository.getUserFollows(
                        networkLibrary = networkLibrary,
                        headers = helixHeaders,
                        userId = userId,
                        limit = 100,
                        offset = offset,
                    )
                    helixFollowedIds.addAll(response.data.mapNotNull { it.id })
                    offset = response.pagination?.cursor
                } while (!offset.isNullOrBlank())
                followedIds = helixFollowedIds
                preferenceEnabledIds = loadOptionalNotificationPreferenceIds(
                    webSessionAvailable = hasWebSession,
                ) {
                    loadGraphQlFollowedChannels(networkLibrary, gqlHeaders).preferenceEnabledIds
                }
            }
            else -> throw MissingAuthenticationException("channels/followed sync")
        }
        val users = selectNotificationChannelIds(followedIds, preferenceEnabledIds, previousIds)
            .map(::NotificationUser)
        notificationUsersDao.replaceAll(users)
        val enabledIds = users.mapTo(hashSetOf()) { it.channelId }
        notificationEventsDao.getAll()
            .filterNot { it.channelId in enabledIds }
            .forEach { notificationEventsDao.delete(it.eventId) }
        if (preferenceEnabledIds is NotificationPreferenceLoadResult.TransientFailure) {
            NotificationUserSyncResult.PREFERENCE_TRANSIENT_FAILURE
        } else {
            NotificationUserSyncResult.SUCCESS
        }
    }

    private suspend fun loadGraphQlFollowedChannels(
        networkLibrary: String?,
        gqlHeaders: Map<String, String>,
    ): GraphQlFollowedChannels {
        val followedIds = mutableSetOf<String>()
        val enabledIds = mutableSetOf<String>()
        var offset: String? = null
        do {
            val response = graphQLRepository.loadQueryUserFollowedUsers(networkLibrary, gqlHeaders, 100, offset)
            response.errors?.firstOrNull()?.let {
                throw GraphQLApiException(it.message, operation = "UserFollowedUsers")
            }
            val data = response.data
                ?: throw GraphQLApiException("GraphQL response did not include data", operation = "UserFollowedUsers")
            val user = data.user
                ?: throw GraphQLApiException("GraphQL response did not include user data", operation = "UserFollowedUsers")
            val follows = user.follows
                ?: throw GraphQLApiException("GraphQL response did not include followed-user data", operation = "UserFollowedUsers")
            val items = follows.edges
                ?: throw GraphQLApiException("GraphQL response did not include followed-user edges", operation = "UserFollowedUsers")
            items.forEach { item ->
                val node = item?.node ?: return@forEach
                val id = node.id ?: return@forEach
                followedIds += id
                if (node.self?.follower?.notificationSettings?.isEnabled == true) {
                    enabledIds += id
                }
            }
            offset = items.lastOrNull()?.cursor?.toString()
        } while (!offset.isNullOrBlank() && follows.pageInfo?.hasNextPage == true)
        return GraphQlFollowedChannels(
            followedIds = followedIds,
            preferenceEnabledIds = enabledIds,
        )
    }

    /**
     * Setup-only validation for the cached notification-channel baseline.
     * Runtime polls intentionally keep the old no-credential no-op behavior.
     */
    suspend fun validateLiveNotificationBaselineAuthentication(
        gqlHeaders: Map<String, String>,
        helixHeaders: Map<String, String>,
    ) = withContext(Dispatchers.IO) {
        if (isLiveNotificationBaselineAuthenticationMissing(
                cachedChannelCount = notificationUsersDao.getAll().size,
                gqlHeaders = gqlHeaders,
                helixHeaders = helixHeaders,
            )
        ) {
            throw MissingAuthenticationException("live stream baseline fetch")
        }
    }

    suspend fun getNotificationUserIds(): List<String> = withContext(Dispatchers.IO) {
        notificationUsersDao.getAll().map { it.channelId }
    }

    suspend fun getPendingNotificationEvents(): List<NotificationEvent> = withContext(Dispatchers.IO) {
        notificationEventsDao.getAll()
    }

    suspend fun markNotificationDelivered(eventId: String) = withContext(Dispatchers.IO) {
        notificationEventsDao.delete(eventId)
    }

    suspend fun clearPendingNotificationEvents() = withContext(Dispatchers.IO) {
        notificationEventsDao.deleteAll()
    }

    suspend fun clearNotificationState() = withContext(Dispatchers.IO) {
        notificationUsersDao.deleteAll()
        shownNotificationsDao.deleteList(shownNotificationsDao.getAll())
        notificationEventsDao.deleteAll()
    }

    private suspend fun gqlQueryLoad(networkLibrary: String?, gqlHeaders: Map<String, String>): List<Stream> {
        val list = mutableListOf<Stream>()
        var offset: String? = null
        do {
            val response = graphQLRepository.loadQueryUserFollowedStreams(networkLibrary, gqlHeaders, 100, offset)
            val graphQLError = response.errors?.firstOrNull {
                isFatalLiveNotificationGraphQlError(it.message, requiredDataAvailable = true)
            } ?: response.errors?.firstOrNull()
            if (isFatalLiveNotificationGraphQlError(
                    errorMessage = graphQLError?.message,
                    requiredDataAvailable = response.data != null,
                )
            ) {
                throw GraphQLApiException(
                    graphQLError?.message ?: "GraphQL response did not include data",
                    operation = "UserFollowedStreams",
                )
            }
            val data = response.data
                ?: throw GraphQLApiException("GraphQL response did not include data", operation = "UserFollowedStreams")
            val user = data.user
                ?: throw GraphQLApiException(
                    graphQLError?.message ?: "GraphQL response did not include user data",
                    operation = "UserFollowedStreams",
                )
            val followedLiveUsers = user.followedLiveUsers
                ?: throw GraphQLApiException(
                    graphQLError?.message ?: "GraphQL response did not include followed-stream data",
                    operation = "UserFollowedStreams",
                )
            val items = followedLiveUsers.edges
                ?: throw GraphQLApiException(
                    graphQLError?.message ?: "GraphQL response did not include followed-stream edges",
                    operation = "UserFollowedStreams",
                )
            items.mapNotNull { item ->
                item?.node?.let {
                    val stream = it.stream ?: return@let null
                    val streamId = stream.id?.takeIf { id -> id.isNotBlank() } ?: return@let null
                    val createdAt = stream.createdAt?.toString()?.takeIf { value -> value.isNotBlank() }
                        ?: return@let null
                    if (it.self?.follower?.notificationSettings?.isEnabled == true) {
                        Stream(
                            id = streamId,
                            channelId = it.id,
                            channelLogin = it.login,
                            channelName = it.displayName,
                            channelImageURL = it.profileImageURL,
                            gameId = stream.game?.id,
                            gameSlug = stream.game?.slug,
                            gameName = stream.game?.displayName,
                            title = stream.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = stream.previewImageURL,
                            createdAt = createdAt,
                            viewerCount = stream.viewersCount,
                            tags = stream.freeformTags?.mapNotNull { tag -> tag.name },
                        )
                    } else null
                }
            }.let { list.addAll(it) }
            offset = items.lastOrNull()?.cursor?.toString()
        } while (!items.lastOrNull()?.cursor?.toString().isNullOrBlank() && followedLiveUsers.pageInfo?.hasNextPage == true)
        return list
    }

    private suspend fun gqlQueryLocal(networkLibrary: String?, gqlHeaders: Map<String, String>, ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map { list ->
            graphQLRepository.loadQueryUsersStream(networkLibrary, gqlHeaders, list)
        }.flatMap { response ->
            val graphQLError = response.errors?.firstOrNull {
                isFatalLiveNotificationGraphQlError(it.message, requiredDataAvailable = true)
            } ?: response.errors?.firstOrNull()
            if (isFatalLiveNotificationGraphQlError(
                    errorMessage = graphQLError?.message,
                    requiredDataAvailable = response.data != null,
                )
            ) {
                throw GraphQLApiException(
                    graphQLError?.message ?: "GraphQL response did not include data",
                    operation = "UsersStream",
                )
            }
            val data = response.data
                ?: throw GraphQLApiException("GraphQL response did not include data", operation = "UsersStream")
            data.users
                ?: throw GraphQLApiException(
                    graphQLError?.message ?: "GraphQL response did not include user stream data",
                    operation = "UsersStream",
                )
        }
        return items.mapNotNull { item ->
            item?.let {
                if (it.stream?.viewersCount != null && !it.stream.id.isNullOrBlank()) {
                    Stream(
                        id = it.stream.id,
                        channelId = it.id,
                        channelLogin = it.login,
                        channelName = it.displayName,
                        channelImageURL = it.profileImageURL,
                        gameId = it.stream.game?.id,
                        gameSlug = it.stream.game?.slug,
                        gameName = it.stream.game?.displayName,
                        title = it.stream.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = it.stream.previewImageURL,
                        createdAt = it.stream.createdAt?.toString(),
                        viewerCount = it.stream.viewersCount,
                        tags = it.stream.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                } else null
            }
        }
    }

    private suspend fun helixLocal(
        networkLibrary: String?,
        helixHeaders: Map<String, String>,
        ids: List<String>,
        onHelixRateLimit: ((HelixRateLimit) -> Unit)?,
    ): List<Stream> {
        val rateLimits = Collections.synchronizedList(mutableListOf<HelixRateLimit>())
        val semaphore = Semaphore(4)
        val items = coroutineScope {
            ids.chunked(100).map { chunk ->
                async {
                    semaphore.withPermit {
                        helixRepository.getStreams(
                            networkLibrary = networkLibrary,
                            headers = helixHeaders,
                            ids = chunk,
                            rateLimitListener = { rateLimits.add(it) },
                        )
                    }
                }
            }.awaitAll()
        }.flatMap { it.data }
        onHelixRateLimit?.let { callback ->
            synchronized(rateLimits) {
                rateLimits.toList()
            }.takeIf { it.isNotEmpty() }?.let { limits ->
                callback(
                    HelixRateLimit(
                        limit = limits.mapNotNull { it.limit }.minOrNull(),
                        remaining = limits.mapNotNull { it.remaining }.minOrNull(),
                        resetEpochSeconds = limits.mapNotNull { it.resetEpochSeconds }.maxOrNull(),
                    )
                )
            }
        }
        return items.mapNotNull {
            if (it.viewerCount != null && !it.id.isNullOrBlank() && !it.startedAt.isNullOrBlank()) {
                Stream(
                    id = it.id,
                    channelId = it.channelId,
                    channelLogin = it.channelLogin,
                    channelName = it.channelName,
                    gameId = it.gameId,
                    gameName = it.gameName,
                    title = it.title,
                    thumbnailURL = it.thumbnailURL,
                    createdAt = it.startedAt,
                    viewerCount = it.viewerCount,
                    tags = it.tags,
                )
            } else null
        }
    }

    suspend fun saveList(list: List<ShownNotification>) = withContext(Dispatchers.IO) {
        shownNotificationsDao.insertList(list)
    }

    suspend fun getUserById(id: String) = withContext(Dispatchers.IO) {
        notificationUsersDao.getById(id)
    }

    suspend fun saveUser(item: NotificationUser) = withContext(Dispatchers.IO) {
        notificationUsersDao.insert(item)
    }

    suspend fun deleteUser(item: NotificationUser) = withContext(Dispatchers.IO) {
        notificationUsersDao.delete(item)
        notificationEventsDao.deleteForChannel(item.channelId)
    }

    private suspend fun enrichNewStreamsWithProfiles(
        streams: List<Stream>,
        networkLibrary: String?,
        helixHeaders: Map<String, String>,
    ): List<Stream> {
        val ids = streamIdsMissingProfileImages(streams)
        if (ids.isEmpty() || helixHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            return streams
        }

        val profileUrls = mutableMapOf<String, String>()
        ids.chunked(MAX_USERS_PER_REQUEST).forEach { chunk ->
            val users = try {
                helixRepository.getUsers(
                    networkLibrary = networkLibrary,
                    headers = helixHeaders,
                    ids = chunk,
                ).data
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enrich live notification avatars", e)
                emptyList()
            }
            users.forEach { user ->
                val id = user.id?.takeIf { it.isNotBlank() }
                val imageUrl = user.profileImageURL?.takeIf { it.isNotBlank() }
                if (id != null && imageUrl != null) {
                    profileUrls[id] = imageUrl
                }
            }
        }
        return mergeProfileImages(streams, profileUrls)
    }

    private fun Stream.startedAtMillis(): Long? = createdAt
        ?.takeIf { it.isNotBlank() }
        ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
        ?.takeIf { it > 0 }

    companion object {
        private const val TAG = "NotificationsRepository"
        private const val MAX_USERS_PER_REQUEST = 100
    }
}

/**
 * GQL supplies both followed channels and Twitch's notification preference filter when the account is
 * backed by GeckoView. Official Helix OAuth remains supported as the native fallback.
 */
internal fun selectNotificationChannelIds(
    followedIds: Iterable<String>,
    preferenceEnabledIds: Set<String>?,
): Set<String> {
    val followed = followedIds.toSet()
    return preferenceEnabledIds?.let(followed::intersect) ?: followed
}

internal fun selectNotificationChannelIds(
    followedIds: Iterable<String>,
    preferenceResult: NotificationPreferenceLoadResult,
    previousNotificationIds: Iterable<String>,
): Set<String> = when (preferenceResult) {
    is NotificationPreferenceLoadResult.Loaded -> selectNotificationChannelIds(
        followedIds = followedIds,
        preferenceEnabledIds = preferenceResult.enabledIds,
    )
    NotificationPreferenceLoadResult.WebSessionUnavailable -> followedIds.toSet()
    NotificationPreferenceLoadResult.TransientFailure -> {
        val followed = followedIds.toSet()
        val previous = previousNotificationIds.toSet()
        if (previous.isEmpty()) followed else previous.intersect(followed)
    }
}

internal suspend fun loadOptionalNotificationPreferenceIds(
    webSessionAvailable: Boolean,
    load: suspend () -> Set<String>,
): NotificationPreferenceLoadResult {
    if (!webSessionAvailable) return NotificationPreferenceLoadResult.WebSessionUnavailable
    return try {
        NotificationPreferenceLoadResult.Loaded(load())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isNotificationPreferenceAuthUnavailable(e)) {
            NotificationPreferenceLoadResult.WebSessionUnavailable
        } else {
            NotificationPreferenceLoadResult.TransientFailure
        }
    }
}
