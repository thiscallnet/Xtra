package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.NotificationEventsDao
import com.github.andreyasadchy.xtra.db.NotificationUsersDao
import com.github.andreyasadchy.xtra.db.ShownNotificationsDao
import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.model.NotificationEvent
import com.github.andreyasadchy.xtra.model.NotificationUser
import com.github.andreyasadchy.xtra.model.ShownNotification
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.LiveStreamOnlineEvent
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

internal fun shouldEnqueueStreamOnline(
    channelEnabled: Boolean,
    shownStartedAt: Long?,
    eventStartedAt: Long,
): Boolean = channelEnabled && shownStartedAt?.let { it >= eventStartedAt } != true

internal fun isLiveNotificationBaselineAuthenticationMissing(
    cachedChannelCount: Int,
    gqlHeaders: Map<String, String>,
    helixHeaders: Map<String, String>,
): Boolean = cachedChannelCount > 0 &&
        gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() &&
        helixHeaders[C.HEADER_TOKEN].isNullOrBlank()

private val liveNotificationGraphQlAuthPattern = Regex(
    "(?i)\\b(?:unauthori[sz]ed|unauthenticated|authentication required|login required|forbidden|access denied|invalid (?:oauth|access|refresh) token|(?:oauth|access|refresh) token(?: is)? (?:invalid|expired)|invalid token|not authorized|permission denied|HTTP\\s+(?:401|403)|(?:401|403))\\b"
)

internal fun isFatalLiveNotificationGraphQlError(
    errorMessage: String?,
    requiredDataAvailable: Boolean,
): Boolean = !requiredDataAvailable || liveNotificationGraphQlAuthPattern.containsMatchIn(errorMessage.orEmpty())

class NotificationsRepository(
    private val shownNotificationsDao: ShownNotificationsDao,
    private val notificationUsersDao: NotificationUsersDao,
    private val notificationEventsDao: NotificationEventsDao,
    private val database: AppDatabase,
    private val graphQLRepository: GraphQLRepository,
    private val helixRepository: HelixRepository,
) {

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
        if (notificationIds.isNotEmpty()) {
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
        if (includeFollowedStreams && !gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
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
        val liveStreams = list.distinctBy { it.channelId ?: it.id }
        val liveList = liveStreams.mapNotNull { stream ->
            stream.channelId.takeUnless { it.isNullOrBlank() }?.let { channelId ->
                stream.startedAtMillis()?.let { startedAt ->
                    ShownNotification(channelId, startedAt)
                }
            }
        }
        val oldList = shownNotificationsDao.getAll()
        oldList.filter { item -> liveList.find { it.channelId == item.channelId } == null }.let {
            shownNotificationsDao.deleteList(it)
        }
        val oldByChannel = oldList.associateBy { it.channelId }
        val newStreams = liveStreams.filter { stream ->
            val channelId = stream.channelId ?: return@filter false
            val startedAt = stream.startedAtMillis() ?: return@filter false
            oldByChannel[channelId]?.startedAt?.let { it >= startedAt } != true
        }
        if (enqueueNotificationEvents) {
            newStreams.mapNotNull { stream ->
                stream.startedAtMillis()?.let { NotificationEvent.fromStream(stream, it) }
            }.let(notificationEventsDao::insertList)
        }
        shownNotificationsDao.insertList(liveList)
        onApiUsed?.invoke(apiUsed)
        newStreams
    }

    suspend fun syncNotificationUsers(networkLibrary: String?, gqlHeaders: Map<String, String>) = withContext(Dispatchers.IO) {
        if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank()) {
            throw MissingAuthenticationException("notification-user/follow sync")
        }
        val users = mutableListOf<NotificationUser>()
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
            users.addAll(items.mapNotNull { item ->
                item?.node?.takeIf {
                    it.self?.follower?.notificationSettings?.isEnabled == true
                }?.id?.let(::NotificationUser)
            })
            offset = items.lastOrNull()?.cursor?.toString()
        } while (!offset.isNullOrBlank() && follows.pageInfo?.hasNextPage == true)
        notificationUsersDao.replaceAll(users)
        val enabledIds = users.mapTo(hashSetOf()) { it.channelId }
        notificationEventsDao.getAll()
            .filterNot { it.channelId in enabledIds }
            .forEach { notificationEventsDao.delete(it.eventId) }
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

    suspend fun enqueueNotification(stream: Stream, startedAt: Long? = null): String? = withContext(Dispatchers.IO) {
        val start = startedAt ?: stream.startedAtMillis() ?: return@withContext null
        NotificationEvent.fromStream(stream, start)?.also { notificationEventsDao.insert(it) }?.eventId
    }

    suspend fun enqueueStreamOnline(event: LiveStreamOnlineEvent): String? = withContext(Dispatchers.IO) {
        val channelId = event.broadcasterUserId.takeIf { it.isNotBlank() } ?: return@withContext null
        val startedAt = Instant.parseOrNull(event.startedAt)?.toEpochMilliseconds()?.takeIf { it > 0L }
            ?: return@withContext null
        val notification = streamOnlineNotification(event, channelId, startedAt)
        var enqueued = false
        database.runInTransaction {
            // EventSub can race both Helix and a follow/notification preference
            // change. Keep membership, deduplication, and both writes in one
            // transaction so a stale direct alert cannot bypass local state.
            val shown = shownNotificationsDao.getById(channelId)
            if (shouldEnqueueStreamOnline(
                    channelEnabled = notificationUsersDao.getById(channelId) != null,
                    shownStartedAt = shown?.startedAt,
                    eventStartedAt = startedAt,
                )
            ) {
                notificationEventsDao.insert(notification)
                shownNotificationsDao.insertList(listOf(ShownNotification(channelId, startedAt)))
                enqueued = true
            }
        }
        notification.eventId.takeIf { enqueued }
    }

    private fun streamOnlineNotification(event: LiveStreamOnlineEvent, channelId: String, startedAt: Long): NotificationEvent = NotificationEvent(
            eventId = "$channelId:$startedAt",
            channelId = channelId,
            streamId = event.eventId,
            channelLogin = event.broadcasterUserLogin,
            channelName = event.broadcasterUserName ?: event.broadcasterUserLogin,
            channelImageURL = null,
            gameName = null,
            title = null,
            thumbnailURL = null,
            createdAt = event.startedAt,
            viewerCount = null,
            startedAt = startedAt,
            queuedAt = System.currentTimeMillis(),
        )

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
                    if (it.self?.follower?.notificationSettings?.isEnabled == true) {
                        Stream(
                            id = it.stream?.id,
                            channelId = it.id,
                            channelLogin = it.login,
                            channelName = it.displayName,
                            channelImageURL = it.profileImageURL,
                            gameId = it.stream?.game?.id,
                            gameSlug = it.stream?.game?.slug,
                            gameName = it.stream?.game?.displayName,
                            title = it.stream?.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = it.stream?.previewImageURL,
                            createdAt = it.stream?.createdAt?.toString(),
                            viewerCount = it.stream?.viewersCount,
                            tags = it.stream?.freeformTags?.mapNotNull { tag -> tag.name },
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
                if (it.stream?.viewersCount != null) {
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
            if (it.viewerCount != null) {
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

    private fun Stream.startedAtMillis(): Long? = createdAt
        ?.takeIf { it.isNotBlank() }
        ?.let { Instant.parseOrNull(it)?.toEpochMilliseconds() }
        ?.takeIf { it > 0 }
}
