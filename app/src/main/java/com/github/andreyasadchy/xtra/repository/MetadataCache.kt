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
import com.github.andreyasadchy.xtra.model.ui.User
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
private const val CACHE_RETENTION_MS = 30L * 24 * 60 * 60 * 1_000L
private const val MAX_CACHE_ENTRIES = 240

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

/**
 * Durable stale-while-revalidate metadata for screens that are useful before
 * their network response arrives. Live stream values are still refreshed by
 * the existing stream-feed coordinator; this cache only supplies fast UI data.
 */
class MetadataCache(
    private val database: AppDatabase,
    private val json: Json,
    private val dao: MetadataCacheDao = database.metadataCache(),
) {

    suspend fun readAccount(userId: String?, login: String?): AccountCacheSnapshot? = withContext(Dispatchers.IO) {
        val hit = readPayload<AccountCachePayload>(
            kind = ACCOUNT_KIND,
            keys = identityKeys("id", "login", userId, login),
            expectedStableId = userId,
            identity = { it.user?.id },
        )
            ?: return@withContext null
        AccountCacheSnapshot(
            user = hit.payload.user,
            scopes = hit.payload.scopes.toSet(),
            chatColor = hit.payload.chatColor,
            channel = hit.payload.channel,
            chatSettings = hit.payload.chatSettings,
            blockedUsers = hit.payload.blockedUsers,
            blockedUsersCursor = hit.payload.blockedUsersCursor,
        )
    }

    suspend fun writeAccount(
        userId: String?,
        login: String?,
        snapshot: AccountCacheSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        writePayload(
            kind = ACCOUNT_KIND,
            keys = identityKeys("id", "login", userId ?: snapshot.user?.id, login ?: snapshot.user?.login),
            payload = AccountCachePayload(
                user = snapshot.user,
                scopes = snapshot.scopes.sorted(),
                chatColor = snapshot.chatColor,
                channel = snapshot.channel,
                chatSettings = snapshot.chatSettings,
                blockedUsers = snapshot.blockedUsers,
                blockedUsersCursor = snapshot.blockedUsersCursor,
            ),
            stableId = userId ?: snapshot.user?.id,
            identity = { it.user?.id },
            nowMs = nowMs,
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
        val user = hit.payload.user?.toUiUser() ?: return@withContext null
        ChannelPageCacheSnapshot(user = user, stream = hit.payload.stream?.toUiStream())
    }

    suspend fun writeChannel(
        channelId: String?,
        login: String?,
        snapshot: ChannelPageCacheSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        writePayload(
            kind = CHANNEL_KIND,
            keys = identityKeys("id", "login", channelId ?: snapshot.user.id, login ?: snapshot.user.login),
            payload = ChannelPageCachePayload(
                user = snapshot.user.toCacheUser(),
                stream = snapshot.stream?.toCacheStream(),
            ),
            stableId = channelId ?: snapshot.user.id,
            identity = { it.user?.id ?: it.stream?.channelId },
            nowMs = nowMs,
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
        hit.payload.game?.let { GamePageCacheSnapshot(it.toUiGame()) }
    }

    suspend fun writeGame(
        gameId: String?,
        slug: String?,
        name: String?,
        snapshot: GamePageCacheSnapshot,
        nowMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        writePayload(
            kind = GAME_KIND,
            keys = gameIdentityKeys(gameId ?: snapshot.game.id, slug ?: snapshot.game.slug, name ?: snapshot.game.name),
            payload = GamePageCachePayload(snapshot.game.toCacheGame()),
            stableId = gameId ?: snapshot.game.id,
            identity = { it.game?.id },
            nowMs = nowMs,
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
            val nowMs = System.currentTimeMillis()
            if (entry.updatedAt <= nowMs - CACHE_RETENTION_MS) {
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
            return CacheHit(payload)
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
            val payloadJson = json.encodeToString(payload)
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
        val cutoff = nowMs - CACHE_RETENTION_MS
        val entries = dao.allEntries()
        entries.filter { it.lastAccessAt <= cutoff }
            .forEach { dao.delete(it.kind, it.cacheKey) }
        entries.asSequence()
            .filter { it.lastAccessAt > cutoff }
            .drop(MAX_CACHE_ENTRIES)
            .forEach { dao.delete(it.kind, it.cacheKey) }
    }

    private data class CacheHit<T>(val payload: T)

    private fun normalizeIdentity(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)

    companion object {
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
    val user: HelixUser? = null,
    val scopes: List<String> = emptyList(),
    val chatColor: String? = null,
    val channel: ChannelInformation? = null,
    val chatSettings: ChatSettings? = null,
    val blockedUsers: List<BlockedUser> = emptyList(),
    val blockedUsersCursor: String? = null,
)

@Serializable
private data class ChannelPageCachePayload(
    val user: CachedChannelUser? = null,
    val stream: CachedChannelStream? = null,
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
    val game: CachedGame? = null,
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

private fun CachedChannelUser.toUiUser() = User(
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

private fun CachedChannelStream.toUiStream() = Stream(
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

private fun CachedGame.toUiGame() = Game(
    id = id,
    slug = slug,
    name = name,
    boxArtURL = boxArtURL,
    viewerCount = viewerCount,
    broadcasterCount = broadcasterCount,
    followerCount = followerCount,
    tags = tags?.map { Tag(it.id, it.name) },
)
