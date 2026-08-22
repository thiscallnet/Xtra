package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.db.MetadataCacheDao
import com.github.andreyasadchy.xtra.db.MetadataCacheEntry
import com.github.andreyasadchy.xtra.model.helix.channel.ChannelInformation
import com.github.andreyasadchy.xtra.model.helix.chat.ChatSettings
import com.github.andreyasadchy.xtra.model.helix.user.BlockedUser
import com.github.andreyasadchy.xtra.model.helix.user.User as HelixUser
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.model.ui.UpcomingStream
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

private const val ACCOUNT_KIND = "account"
private const val CHANNEL_KIND = "channel"
private const val GAME_KIND = "game"
private const val FOLLOWING_OVERVIEW_KIND = "following_overview"
private const val LOCAL_FOLLOWING_OVERVIEW_KEY = "local"
private const val MAX_CACHE_ENTRIES = 240
private const val METADATA_CACHE_FRESHNESS_VERSION = 1

/**
 * Freshness is deliberately separate from durable retention. A cached object
 * can remain useful for bootstrap while its volatile fields must be treated as
 * stale and revalidated immediately by the owning screen.
 */
internal object MetadataCachePolicy {
    const val DURABLE_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000L
    const val CURRENT_STREAM_BOOTSTRAP_MAX_AGE_MS = 2L * 60 * 60 * 1_000L
    const val GAME_STATS_BOOTSTRAP_MAX_AGE_MS = 15L * 60 * 1_000L
    const val FOLLOWER_COUNT_BOOTSTRAP_MAX_AGE_MS = 6L * 60 * 60 * 1_000L
    const val ACCOUNT_SENSITIVE_BOOTSTRAP_MAX_AGE_MS = 10L * 60 * 1_000L
    const val ACCOUNT_SETTINGS_BOOTSTRAP_MAX_AGE_MS = 6L * 60 * 60 * 1_000L
}

data class AccountCacheSnapshot(
    val user: HelixUser? = null,
    val scopes: Set<String> = emptySet(),
    val chatColor: String? = null,
    val channel: ChannelInformation? = null,
    val chatSettings: ChatSettings? = null,
    val blockedUsers: List<BlockedUser> = emptyList(),
    val blockedUsersCursor: String? = null,
)

data class ChannelPageCacheSnapshot(
    val user: User,
    val stream: Stream? = null,
)

data class GamePageCacheSnapshot(
    val game: Game,
)

data class FollowingOverviewCacheSnapshot(
    val recentVideos: List<Video> = emptyList(),
    val upcomingStreams: List<UpcomingStream> = emptyList(),
)

/**
 * Durable stale-while-revalidate metadata for screens that are useful before
 * their network response arrives. Live stream values are still refreshed by
 * the existing stream-feed coordinator; this cache only supplies fast UI data.
 */
class MetadataCache(
    private val database: AppDatabase,
    private val json: Json,
    private val dao: MetadataCacheDao = database.metadataCache(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun readAccount(userId: String?, login: String?): AccountCacheSnapshot? = withContext(Dispatchers.IO) {
        val hit = readPayload<AccountCachePayload>(
            kind = ACCOUNT_KIND,
            keys = identityKeys("id", "login", userId, login),
            expectedStableId = userId,
            identity = { it.user?.id },
        )
            ?: return@withContext null
        val sensitiveFresh = hit.isFresh(
            freshnessVersion = hit.payload.freshnessVersion,
            validatedAt = hit.payload.scopesValidatedAt,
            maxAgeMs = MetadataCachePolicy.ACCOUNT_SENSITIVE_BOOTSTRAP_MAX_AGE_MS,
        )
        val settingsFresh = hit.isFresh(
            freshnessVersion = hit.payload.freshnessVersion,
            validatedAt = hit.payload.chatSettingsValidatedAt,
            maxAgeMs = MetadataCachePolicy.ACCOUNT_SETTINGS_BOOTSTRAP_MAX_AGE_MS,
        )
        AccountCacheSnapshot(
            user = hit.payload.user,
            scopes = hit.payload.scopes.takeIf { sensitiveFresh }?.toSet().orEmpty(),
            chatColor = hit.payload.chatColor.takeIf {
                hit.isFresh(
                    freshnessVersion = hit.payload.freshnessVersion,
                    validatedAt = hit.payload.chatColorValidatedAt,
                    maxAgeMs = MetadataCachePolicy.ACCOUNT_SETTINGS_BOOTSTRAP_MAX_AGE_MS,
                )
            },
            channel = hit.payload.channel.takeIf {
                hit.isFresh(
                    freshnessVersion = hit.payload.freshnessVersion,
                    validatedAt = hit.payload.channelValidatedAt,
                    maxAgeMs = MetadataCachePolicy.ACCOUNT_SETTINGS_BOOTSTRAP_MAX_AGE_MS,
                )
            },
            chatSettings = hit.payload.chatSettings.takeIf { settingsFresh },
            blockedUsers = hit.payload.blockedUsers.takeIf {
                hit.isFresh(
                    freshnessVersion = hit.payload.freshnessVersion,
                    validatedAt = hit.payload.blockedUsersValidatedAt,
                    maxAgeMs = MetadataCachePolicy.ACCOUNT_SENSITIVE_BOOTSTRAP_MAX_AGE_MS,
                )
            }.orEmpty(),
            blockedUsersCursor = hit.payload.blockedUsersCursor.takeIf {
                hit.isFresh(
                    freshnessVersion = hit.payload.freshnessVersion,
                    validatedAt = hit.payload.blockedUsersValidatedAt,
                    maxAgeMs = MetadataCachePolicy.ACCOUNT_SENSITIVE_BOOTSTRAP_MAX_AGE_MS,
                )
            },
        )
    }

    suspend fun writeAccount(
        userId: String?,
        login: String?,
        snapshot: AccountCacheSnapshot,
        nowMs: Long = clockMs(),
        scopesValidated: Boolean = false,
        chatColorValidated: Boolean = false,
        channelValidated: Boolean = false,
        chatSettingsValidated: Boolean = false,
        blockedUsersValidated: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val payload = AccountCachePayload(
            freshnessVersion = METADATA_CACHE_FRESHNESS_VERSION,
            user = snapshot.user,
            scopes = snapshot.scopes.sorted(),
            chatColor = snapshot.chatColor,
            channel = snapshot.channel,
            chatSettings = snapshot.chatSettings,
            blockedUsers = snapshot.blockedUsers,
            blockedUsersCursor = snapshot.blockedUsersCursor,
        )
        writePayload(
            kind = ACCOUNT_KIND,
            keys = identityKeys("id", "login", userId ?: snapshot.user?.id, login ?: snapshot.user?.login),
            payload = payload,
            stableId = userId ?: snapshot.user?.id,
            identity = { it.user?.id },
            nowMs = nowMs,
            mergePayload = { previous, previousUpdatedAt ->
                payload.copy(
                    scopesValidatedAt = fieldValidationTimestamp(
                        validated = scopesValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.scopesValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                    chatColorValidatedAt = fieldValidationTimestamp(
                        validated = chatColorValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.chatColorValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                    channelValidatedAt = fieldValidationTimestamp(
                        validated = channelValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.channelValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                    chatSettingsValidatedAt = fieldValidationTimestamp(
                        validated = chatSettingsValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.chatSettingsValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                    blockedUsersValidatedAt = fieldValidationTimestamp(
                        validated = blockedUsersValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.blockedUsersValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                )
            },
        )
    }

    suspend fun clearAccount(userId: String?, login: String?) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            identityKeys("id", "login", userId, login).forEach { dao.delete(ACCOUNT_KIND, it) }
        }
    }

    suspend fun readChannel(channelId: String?, login: String?): ChannelPageCacheSnapshot? = withContext(Dispatchers.IO) {
        val hit = readPayload<ChannelPageCachePayload>(
            kind = CHANNEL_KIND,
            keys = identityKeys("id", "login", channelId, login),
            expectedStableId = channelId,
            identity = { it.user?.id ?: it.stream?.channelId },
        )
            ?: return@withContext null
        val user = hit.payload.user?.toUiUser(
            includeFollowerCount = hit.isFresh(
                freshnessVersion = hit.payload.freshnessVersion,
                validatedAt = hit.payload.followerCountValidatedAt,
                maxAgeMs = MetadataCachePolicy.FOLLOWER_COUNT_BOOTSTRAP_MAX_AGE_MS,
            ),
        ) ?: return@withContext null
        ChannelPageCacheSnapshot(
            user = user,
            stream = hit.payload.stream?.toUiStream(
                includeLiveState = hit.isFresh(
                    freshnessVersion = hit.payload.freshnessVersion,
                    validatedAt = hit.payload.streamValidatedAt,
                    maxAgeMs = MetadataCachePolicy.CURRENT_STREAM_BOOTSTRAP_MAX_AGE_MS,
                ),
            ),
        )
    }

    suspend fun writeChannel(
        channelId: String?,
        login: String?,
        snapshot: ChannelPageCacheSnapshot,
        nowMs: Long = clockMs(),
        streamValidated: Boolean = false,
        followerCountValidated: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val payload = ChannelPageCachePayload(
            freshnessVersion = METADATA_CACHE_FRESHNESS_VERSION,
            user = snapshot.user.toCacheUser(),
            stream = snapshot.stream?.toCacheStream(),
        )
        writePayload(
            kind = CHANNEL_KIND,
            keys = identityKeys("id", "login", channelId ?: snapshot.user.id, login ?: snapshot.user.login),
            payload = payload,
            stableId = channelId ?: snapshot.user.id,
            identity = { it.user?.id ?: it.stream?.channelId },
            nowMs = nowMs,
            mergePayload = { previous, previousUpdatedAt ->
                payload.copy(
                    streamValidatedAt = fieldValidationTimestamp(
                        validated = streamValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.streamValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                    followerCountValidatedAt = fieldValidationTimestamp(
                        validated = followerCountValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.followerCountValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                )
            },
        )
    }

    suspend fun readGame(gameId: String?, slug: String?, name: String?): GamePageCacheSnapshot? = withContext(Dispatchers.IO) {
        val hit = readPayload<GamePageCachePayload>(
            kind = GAME_KIND,
            keys = gameIdentityKeys(gameId, slug, name),
            expectedStableId = gameId,
            identity = { it.game?.id },
        )
            ?: return@withContext null
        hit.payload.game?.let {
            GamePageCacheSnapshot(
                it.toUiGame(
                    includeLiveStats = hit.isFresh(
                        freshnessVersion = hit.payload.freshnessVersion,
                        validatedAt = hit.payload.liveStatsValidatedAt,
                        maxAgeMs = MetadataCachePolicy.GAME_STATS_BOOTSTRAP_MAX_AGE_MS,
                    ),
                    includeFollowerCount = hit.isFresh(
                        freshnessVersion = hit.payload.freshnessVersion,
                        validatedAt = hit.payload.followerCountValidatedAt,
                        maxAgeMs = MetadataCachePolicy.FOLLOWER_COUNT_BOOTSTRAP_MAX_AGE_MS,
                    ),
                ),
            )
        }
    }

    suspend fun writeGame(
        gameId: String?,
        slug: String?,
        name: String?,
        snapshot: GamePageCacheSnapshot,
        nowMs: Long = clockMs(),
        liveStatsValidated: Boolean = false,
        followerCountValidated: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val payload = GamePageCachePayload(
            freshnessVersion = METADATA_CACHE_FRESHNESS_VERSION,
            game = snapshot.game.toCacheGame(),
        )
        writePayload(
            kind = GAME_KIND,
            keys = gameIdentityKeys(gameId ?: snapshot.game.id, slug ?: snapshot.game.slug, name ?: snapshot.game.name),
            payload = payload,
            stableId = gameId ?: snapshot.game.id,
            identity = { it.game?.id },
            nowMs = nowMs,
            mergePayload = { previous, previousUpdatedAt ->
                payload.copy(
                    liveStatsValidatedAt = fieldValidationTimestamp(
                        validated = liveStatsValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.liveStatsValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                    followerCountValidatedAt = fieldValidationTimestamp(
                        validated = followerCountValidated,
                        nowMs = nowMs,
                        previousVersion = previous?.freshnessVersion,
                        previousTimestamp = previous?.followerCountValidatedAt,
                        previousUpdatedAt = previousUpdatedAt,
                    ),
                )
            },
        )
    }

    suspend fun readFollowingOverview(userId: String?): FollowingOverviewCacheSnapshot? = withContext(Dispatchers.IO) {
        val hit = readPayload<FollowingOverviewCachePayload>(
            kind = FOLLOWING_OVERVIEW_KIND,
            keys = followingOverviewIdentityKeys(userId),
            expectedStableId = userId,
            identity = { it.userId },
        ) ?: return@withContext null
        FollowingOverviewCacheSnapshot(
            recentVideos = hit.payload.recentVideos.map(CachedFollowingVideo::toVideo),
            upcomingStreams = hit.payload.upcomingStreams
                .map(CachedUpcomingStream::toUpcomingStream)
                .filter { it.startTimeMillis > hit.readAtMs }
                .sortedBy(UpcomingStream::startTimeMillis),
        )
    }

    suspend fun writeFollowingOverview(
        userId: String?,
        snapshot: FollowingOverviewCacheSnapshot,
        nowMs: Long = clockMs(),
        replaceRecentVideos: Boolean = true,
        replaceUpcomingStreams: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val payload = FollowingOverviewCachePayload(
            freshnessVersion = METADATA_CACHE_FRESHNESS_VERSION,
            userId = userId,
            recentVideos = snapshot.recentVideos.map(Video::toCachedFollowingVideo),
            upcomingStreams = snapshot.upcomingStreams.map(UpcomingStream::toCachedUpcomingStream),
        )
        writePayload(
            kind = FOLLOWING_OVERVIEW_KIND,
            keys = followingOverviewIdentityKeys(userId),
            payload = payload,
            stableId = userId,
            identity = { it.userId },
            nowMs = nowMs,
            mergePayload = { previous, _ ->
                payload.copy(
                    recentVideos = if (replaceRecentVideos) payload.recentVideos else previous?.recentVideos.orEmpty(),
                    upcomingStreams = if (replaceUpcomingStreams) payload.upcomingStreams else previous?.upcomingStreams.orEmpty(),
                )
            },
        )
    }

    private inline fun <reified T> readPayload(
        kind: String,
        keys: List<String>,
        expectedStableId: String?,
        noinline identity: (T) -> String?,
    ): CacheHit<T>? {
        val expectedIdentity = normalizeIdentity(expectedStableId)
        for (key in keys) {
            val entry = dao.entry(kind, key) ?: continue
            val nowMs = clockMs()
            if (entry.updatedAt <= nowMs - MetadataCachePolicy.DURABLE_RETENTION_MS) {
                dao.delete(kind, key)
                continue
            }
            val payload = runCatching { json.decodeFromString<T>(entry.payload) }.getOrNull()
            if (payload == null) {
                dao.delete(kind, key)
                continue
            }
            val payloadIdentity = normalizeIdentity(identity(payload))
            if (expectedIdentity != null && payloadIdentity != null && expectedIdentity != payloadIdentity) {
                dao.delete(kind, key)
                continue
            }
            dao.touch(kind, key, nowMs)
            return CacheHit(
                payload = payload,
                updatedAt = entry.updatedAt,
                readAtMs = nowMs,
            )
        }
        return null
    }

    private inline fun <reified T> writePayload(
        kind: String,
        keys: List<String>,
        payload: T,
        stableId: String?,
        noinline identity: (T) -> String?,
        nowMs: Long,
        noinline mergePayload: (T?, Long?) -> T,
    ) {
        if (keys.isEmpty()) return
        database.runInTransaction {
            val distinctKeys = keys.distinct()
            normalizeIdentity(stableId)?.let { stableIdentity ->
                dao.entries(kind)
                    .filter { it.cacheKey !in distinctKeys }
                    .filter { entry ->
                        runCatching { json.decodeFromString<T>(entry.payload) }
                            .getOrNull()
                            ?.let { normalizeIdentity(identity(it)) == stableIdentity }
                            ?: false
                    }
                    .forEach { dao.delete(kind, it.cacheKey) }
            }
            val stableIdentity = normalizeIdentity(stableId)
            val previous = distinctKeys.asSequence()
                .mapNotNull { key ->
                    dao.entry(kind, key)?.let { entry ->
                        runCatching { json.decodeFromString<T>(entry.payload) }
                            .getOrNull()
                            ?.takeIf { previousPayload ->
                                stableIdentity == null || normalizeIdentity(identity(previousPayload)) == stableIdentity
                            }
                            ?.let { previousPayload -> ExistingPayload(previousPayload, entry.updatedAt) }
                    }
                }
                .firstOrNull()
            val payloadJson = json.encodeToString(mergePayload(previous?.payload, previous?.updatedAt))
            distinctKeys.forEach { key ->
                dao.insert(
                    MetadataCacheEntry(
                        kind = kind,
                        cacheKey = key,
                        payload = payloadJson,
                        updatedAt = nowMs,
                        lastAccessAt = nowMs,
                    ),
                )
            }
            cleanup(nowMs)
        }
    }

    private fun cleanup(nowMs: Long) {
        val cutoff = nowMs - MetadataCachePolicy.DURABLE_RETENTION_MS
        val entries = dao.allEntries()
        entries.filter { it.lastAccessAt <= cutoff }
            .forEach { dao.delete(it.kind, it.cacheKey) }
        entries.asSequence()
            .filter { it.lastAccessAt > cutoff }
            .drop(MAX_CACHE_ENTRIES)
            .forEach { dao.delete(it.kind, it.cacheKey) }
    }

    private data class CacheHit<T>(
        val payload: T,
        val updatedAt: Long,
        val readAtMs: Long,
    )

    private data class ExistingPayload<T>(
        val payload: T,
        val updatedAt: Long,
    )

    private fun CacheHit<*>.isFresh(
        freshnessVersion: Int,
        validatedAt: Long?,
        maxAgeMs: Long,
    ): Boolean {
        val effectiveValidatedAt = validatedAt
            ?: updatedAt.takeIf { freshnessVersion < METADATA_CACHE_FRESHNESS_VERSION }
        return effectiveValidatedAt?.let {
            (readAtMs - it).coerceAtLeast(0L) <= maxAgeMs
        } == true
    }

    private fun fieldValidationTimestamp(
        validated: Boolean,
        nowMs: Long,
        previousVersion: Int?,
        previousTimestamp: Long?,
        previousUpdatedAt: Long?,
    ): Long? {
        if (validated) return nowMs
        return previousTimestamp
            ?: previousUpdatedAt?.takeIf {
                (previousVersion ?: 0) < METADATA_CACHE_FRESHNESS_VERSION
            }
    }

    private fun normalizeIdentity(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)

    companion object {
        internal fun followingOverviewIdentityKeys(userId: String?): List<String> =
            userId?.takeIf { it.isNotBlank() }?.let { identityKeys("id", "login", it, null) }
                ?: listOf(LOCAL_FOLLOWING_OVERVIEW_KEY)

        internal fun identityKeys(
            firstPrefix: String,
            secondPrefix: String,
            first: String?,
            second: String?,
        ): List<String> = buildList {
            first?.trim()?.takeIf { it.isNotEmpty() }?.let { add("$firstPrefix:${normalize(it)}") }
            second?.trim()?.takeIf { it.isNotEmpty() }?.let { add("$secondPrefix:${normalize(it)}") }
        }.distinct()

        internal fun gameIdentityKeys(gameId: String?, slug: String?, name: String?): List<String> = buildList {
            gameId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("id:${normalize(it)}") }
            slug?.trim()?.takeIf { it.isNotEmpty() }?.let { add("slug:${normalize(it)}") }
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { add("name:${normalize(it)}") }
        }.distinct()

        private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
    }
}

@Serializable
private data class AccountCachePayload(
    val freshnessVersion: Int = 0,
    val user: HelixUser? = null,
    val scopes: List<String> = emptyList(),
    val chatColor: String? = null,
    val channel: ChannelInformation? = null,
    val chatSettings: ChatSettings? = null,
    val blockedUsers: List<BlockedUser> = emptyList(),
    val blockedUsersCursor: String? = null,
    val scopesValidatedAt: Long? = null,
    val chatColorValidatedAt: Long? = null,
    val channelValidatedAt: Long? = null,
    val chatSettingsValidatedAt: Long? = null,
    val blockedUsersValidatedAt: Long? = null,
)

@Serializable
private data class ChannelPageCachePayload(
    val freshnessVersion: Int = 0,
    val user: CachedChannelUser? = null,
    val stream: CachedChannelStream? = null,
    val streamValidatedAt: Long? = null,
    val followerCountValidatedAt: Long? = null,
)

@Serializable
private data class CachedChannelUser(
    val id: String? = null,
    val login: String? = null,
    val name: String? = null,
    val profileImageURL: String? = null,
    val type: String? = null,
    val broadcasterType: String? = null,
    val createdAt: String? = null,
    val followerCount: Int? = null,
    val bannerImageURL: String? = null,
    val lastBroadcast: String? = null,
)

@Serializable
private data class CachedChannelStream(
    val id: String? = null,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    val channelImageURL: String? = null,
    val gameId: String? = null,
    val gameSlug: String? = null,
    val gameName: String? = null,
    val title: String? = null,
    val thumbnailURL: String? = null,
    val createdAt: String? = null,
    val viewerCount: Int? = null,
    val tags: List<String>? = null,
)

@Serializable
private data class GamePageCachePayload(
    val freshnessVersion: Int = 0,
    val game: CachedGame? = null,
    val liveStatsValidatedAt: Long? = null,
    val followerCountValidatedAt: Long? = null,
)

@Serializable
private data class FollowingOverviewCachePayload(
    val freshnessVersion: Int = 0,
    val userId: String? = null,
    val recentVideos: List<CachedFollowingVideo> = emptyList(),
    val upcomingStreams: List<CachedUpcomingStream> = emptyList(),
)

@Serializable
private data class CachedFollowingVideo(
    val id: String? = null,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    val channelImageURL: String? = null,
    val gameId: String? = null,
    val gameSlug: String? = null,
    val gameName: String? = null,
    val title: String? = null,
    val thumbnailURL: String? = null,
    val createdAt: String? = null,
    val viewCount: Int? = null,
    val durationSeconds: Int? = null,
    val type: String? = null,
    val animatedPreviewURL: String? = null,
)

@Serializable
private data class CachedUpcomingStream(
    val id: String,
    val channelId: String? = null,
    val channelLogin: String? = null,
    val channelName: String? = null,
    val channelImageURL: String? = null,
    val title: String? = null,
    val gameName: String? = null,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val isRecurring: Boolean,
)

private fun Video.toCachedFollowingVideo() = CachedFollowingVideo(
    id = id,
    channelId = channelId,
    channelLogin = channelLogin,
    channelName = channelName,
    channelImageURL = channelImageURL,
    gameId = gameId,
    gameSlug = gameSlug,
    gameName = gameName,
    title = title,
    thumbnailURL = thumbnailURL,
    createdAt = createdAt,
    viewCount = viewCount,
    durationSeconds = durationSeconds,
    type = type,
    animatedPreviewURL = animatedPreviewURL,
)

private fun CachedFollowingVideo.toVideo() = Video(
    id = id,
    channelId = channelId,
    channelLogin = channelLogin,
    channelName = channelName,
    channelImageURL = channelImageURL,
    gameId = gameId,
    gameSlug = gameSlug,
    gameName = gameName,
    title = title,
    thumbnailURL = thumbnailURL,
    createdAt = createdAt,
    viewCount = viewCount,
    durationSeconds = durationSeconds,
    type = type,
    animatedPreviewURL = animatedPreviewURL,
)

private fun UpcomingStream.toCachedUpcomingStream() = CachedUpcomingStream(
    id = id,
    channelId = channelId,
    channelLogin = channelLogin,
    channelName = channelName,
    channelImageURL = channelImageURL,
    title = title,
    gameName = gameName,
    startTimeMillis = startTimeMillis,
    endTimeMillis = endTimeMillis,
    isRecurring = isRecurring,
)

private fun CachedUpcomingStream.toUpcomingStream() = UpcomingStream(
    id = id,
    channelId = channelId,
    channelLogin = channelLogin,
    channelName = channelName,
    channelImageURL = channelImageURL,
    title = title,
    gameName = gameName,
    startTimeMillis = startTimeMillis,
    endTimeMillis = endTimeMillis,
    isRecurring = isRecurring,
)

@Serializable
private data class CachedGame(
    val id: String? = null,
    val slug: String? = null,
    val name: String? = null,
    val boxArtURL: String? = null,
    val viewerCount: Int? = null,
    val broadcasterCount: Int? = null,
    val followerCount: Int? = null,
    val tags: List<CachedTag>? = null,
)

@Serializable
private data class CachedTag(
    val id: String? = null,
    val name: String? = null,
)

private fun User.toCacheUser() = CachedChannelUser(
    id = id,
    login = login,
    name = name,
    profileImageURL = profileImageURL,
    type = type,
    broadcasterType = broadcasterType,
    createdAt = createdAt,
    followerCount = followerCount,
    bannerImageURL = bannerImageURL,
    lastBroadcast = lastBroadcast,
)

private fun CachedChannelUser.toUiUser(includeFollowerCount: Boolean) = User(
    id = id,
    login = login,
    name = name,
    profileImageURL = profileImageURL,
    type = type,
    broadcasterType = broadcasterType,
    createdAt = createdAt,
    followerCount = followerCount.takeIf { includeFollowerCount },
    bannerImageURL = bannerImageURL,
    lastBroadcast = lastBroadcast,
)

private fun Stream.toCacheStream() = CachedChannelStream(
    id = id,
    channelId = channelId,
    channelLogin = channelLogin,
    channelName = channelName,
    channelImageURL = channelImageURL,
    gameId = gameId,
    gameSlug = gameSlug,
    gameName = gameName,
    title = title,
    thumbnailURL = thumbnailURL,
    createdAt = createdAt,
    viewerCount = viewerCount,
    tags = tags,
)

private fun CachedChannelStream.toUiStream(includeLiveState: Boolean) = Stream(
    id = id,
    channelId = channelId,
    channelLogin = channelLogin,
    channelName = channelName,
    channelImageURL = channelImageURL,
    gameId = gameId,
    gameSlug = gameSlug,
    gameName = gameName,
    title = title,
    thumbnailURL = thumbnailURL,
    createdAt = createdAt,
    viewerCount = viewerCount.takeIf { includeLiveState },
    tags = tags,
)

private fun Game.toCacheGame() = CachedGame(
    id = id,
    slug = slug,
    name = name,
    boxArtURL = boxArtURL,
    viewerCount = viewerCount,
    broadcasterCount = broadcasterCount,
    followerCount = followerCount,
    tags = tags?.map { CachedTag(it.id, it.name) },
)

private fun CachedGame.toUiGame(
    includeLiveStats: Boolean,
    includeFollowerCount: Boolean,
) = Game(
    id = id,
    slug = slug,
    name = name,
    boxArtURL = boxArtURL,
    viewerCount = viewerCount.takeIf { includeLiveStats },
    broadcasterCount = broadcasterCount.takeIf { includeLiveStats },
    followerCount = followerCount.takeIf { includeFollowerCount },
    tags = tags?.map { Tag(it.id, it.name) },
)
