package com.github.andreyasadchy.xtra.repository.streamfeed

import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.db.CachedStreamFeedItem
import com.github.andreyasadchy.xtra.db.StreamFeedDao
import com.github.andreyasadchy.xtra.db.StreamFeedState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPage
import com.github.andreyasadchy.xtra.repository.ProcessLocalFeedSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val FEED_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L
private const val MAX_FEED_VARIANTS = 80

interface StreamFeedCacheStore {
    fun pagingSource(feedKey: StreamFeedKey): androidx.paging.PagingSource<Int, CachedStreamFeedItem>
    suspend fun state(feedKey: StreamFeedKey): StreamFeedState?
    suspend fun itemCount(feedKey: StreamFeedKey): Int
    suspend fun touchAccess(feedKey: StreamFeedKey, nowMs: Long)
    suspend fun markAttempt(feedKey: StreamFeedKey, nowMs: Long)
    suspend fun replaceAfterRefresh(
        feedKey: StreamFeedKey,
        page: StreamFeedPage,
        nowMs: Long,
    )
    suspend fun appendPage(
        feedKey: StreamFeedKey,
        page: StreamFeedPage,
        nowMs: Long,
    )
    suspend fun pruneStaleGeneration(feedKey: StreamFeedKey)
    suspend fun recordFailure(feedKey: StreamFeedKey, nowMs: Long, failureBackoffUntil: Long?, rateLimitUntil: Long?)
    suspend fun invalidatePrefix(prefix: String, nowMs: Long)
    suspend fun cleanup(nowMs: Long)
}

internal fun Stream.cacheItemKey(): String? {
    return channelId?.trim()?.takeIf { it.isNotEmpty() }?.let { "channel:$it" }
        ?: id?.trim()?.takeIf { it.isNotEmpty() }?.let { "stream:$it" }
        ?: channelLogin?.trim()?.takeIf { it.isNotEmpty() }?.let { "login:${it.lowercase()}" }
}

internal fun Stream.toCachedStreamFeedItem(
    feedKey: String,
    position: Int,
    generation: Long = 0L,
): CachedStreamFeedItem? {
    return cacheItemKey()?.let { itemKey ->
        CachedStreamFeedItem(
            feedKey = feedKey,
            itemKey = itemKey,
            position = position,
            streamId = id,
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
            tags = tags?.let(::encodeTags),
            generation = generation,
        )
    }
}

internal fun CachedStreamFeedItem.toStream(): Stream {
    return Stream(
        id = streamId,
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
        tags = tags?.let(::decodeTags),
        thumbnailGeneration = generation,
    )
}

private fun encodeTags(tags: List<String>): String {
    return JSONArray().apply { tags.forEach(::put) }.toString()
}

private fun decodeTags(value: String): List<String>? {
    return runCatching {
        JSONArray(value).let { array ->
            List(array.length()) { index -> array.getString(index) }
        }
    }.getOrNull()
}

internal fun refreshCachedItems(
    feedKey: String,
    streams: List<Stream>,
    generation: Long = 0L,
): List<CachedStreamFeedItem> {
    return streams.distinctBy { it.cacheItemKey() }
        .mapIndexedNotNull { index, stream ->
            stream.toCachedStreamFeedItem(feedKey, index, generation)
        }
}

/** Apply one newly downloaded page to the current refresh generation. */
internal fun appendCachedPage(
    feedKey: String,
    existing: List<CachedStreamFeedItem>,
    streams: List<Stream>,
    generation: Long = 0L,
): List<CachedStreamFeedItem> {
    val activeRows = existing
        .filter { it.generation == generation }
        .sortedBy { it.position }
    val pageStreams = streams
        .mapNotNull { stream -> stream.cacheItemKey()?.let { it to stream } }
        .distinctBy { it.first }
    val pageByKey = pageStreams.toMap()
    val activeKeys = activeRows.map { it.itemKey }.toSet()

    val updatedActive = activeRows.map { existingItem ->
        pageByKey[existingItem.itemKey]?.toCachedStreamFeedItem(
            feedKey = feedKey,
            position = existingItem.position,
            generation = generation,
        ) ?: existingItem
    }
    val newActive = pageStreams
        .filterNot { (itemKey, _) -> itemKey in activeKeys }
        .mapNotNull { (_, stream) -> stream.toCachedStreamFeedItem(feedKey, 0, generation) }
    val activePrefix = (updatedActive + newActive).mapIndexed { index, item ->
        item.copy(position = index, generation = generation)
    }
    return activePrefix
}

private fun Stream.withThumbnailGeneration(generation: Long): Stream = Stream(
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
    thumbnailGeneration = generation,
)

private fun normalizedStreams(streams: List<Stream>, generation: Long): List<Stream> =
    streams.distinctBy { it.cacheItemKey() }
        .mapNotNull { stream ->
            stream.cacheItemKey()?.let { stream.withThumbnailGeneration(generation) }
        }

/** Mirrors [appendCachedPage] while keeping the live in-memory rows as Stream objects. */
private fun appendStreams(
    existing: List<Stream>,
    streams: List<Stream>,
    generation: Long,
): List<Stream> {
    val activeRows = existing.filter { it.thumbnailGeneration == generation }
    val pageStreams = streams
        .mapNotNull { stream -> stream.cacheItemKey()?.let { it to stream } }
        .distinctBy { it.first }
    val pageByKey = pageStreams.toMap()
    val activeKeys = activeRows.mapNotNull { it.cacheItemKey() }.toSet()
    val updatedActive = activeRows.map { old ->
        pageByKey[old.cacheItemKey()]?.withThumbnailGeneration(generation) ?: old
    }
    val newActive = pageStreams
        .filterNot { (key, _) -> key in activeKeys }
        .map { (_, stream) -> stream.withThumbnailGeneration(generation) }
    val activePrefix = updatedActive + newActive
    return activePrefix
}

/**
 * Room is the durable bootstrap/offline store for stream-feed data. The
 * process-local active snapshot is the live UI source; replacement and page
 * application still persist their result in one SQLite transaction.
 */
class StreamFeedCache(
    private val database: AppDatabase,
    private val dao: StreamFeedDao = database.streamFeedDao(),
) : StreamFeedCacheStore {

    private val activeSnapshot = ProcessLocalFeedSnapshot<Stream>()

    override fun pagingSource(feedKey: StreamFeedKey) = dao.pagingSource(feedKey.value)

    fun activeItemsFlow(feedKey: StreamFeedKey, limit: Int): Flow<List<Stream>> {
        return activeSnapshot.flow(feedKey.value, limit) {
            withContext(Dispatchers.IO) {
                dao.allActiveItemsFlow(feedKey.value).first().map(CachedStreamFeedItem::toStream)
            }
        }
    }

    fun allActiveItemsFlow(feedKey: StreamFeedKey): Flow<List<Stream>> {
        return activeSnapshot.flow(feedKey.value, Int.MAX_VALUE) {
            withContext(Dispatchers.IO) {
                dao.allActiveItemsFlow(feedKey.value).first().map(CachedStreamFeedItem::toStream)
            }
        }
    }

    override suspend fun state(feedKey: StreamFeedKey): StreamFeedState? = withContext(Dispatchers.IO) {
        dao.state(feedKey.value)
    }

    override suspend fun itemCount(feedKey: StreamFeedKey): Int = withContext(Dispatchers.IO) {
        dao.activeItemCount(feedKey.value)
    }

    override suspend fun touchAccess(feedKey: StreamFeedKey, nowMs: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            if (current != null) {
                dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            }
            dao.insertState(
                (current ?: StreamFeedState(feedKey.value)).copy(
                    lastAccessAt = nowMs,
                    staleTailRetainedAt = null,
                )
            )
        }
    }

    override suspend fun markAttempt(feedKey: StreamFeedKey, nowMs: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            if (current != null) {
                dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            }
            dao.insertState(
                (current ?: StreamFeedState(feedKey.value)).copy(
                    lastAttemptAt = nowMs,
                    lastAccessAt = nowMs,
                    staleTailRetainedAt = null,
                )
            )
        }
    }

    /** Replace the complete cached snapshot only after its network request succeeded. */
    override suspend fun replaceAfterRefresh(
        feedKey: StreamFeedKey,
        page: StreamFeedPage,
        nowMs: Long,
    ) = withContext(Dispatchers.IO) {
        var activeItems: List<Stream> = emptyList()
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            val generation = (current?.activeGeneration ?: 0L) + 1L
            val items = refreshCachedItems(feedKey.value, page.items, generation)
            dao.deleteItems(feedKey.value)
            if (items.isNotEmpty()) {
                dao.insertItems(items)
            }
            activeItems = normalizedStreams(page.items, generation)
            dao.insertState(
                StreamFeedState(
                    feedKey = feedKey.value,
                    nextCursor = page.nextCursor?.value,
                    lastSuccessAt = nowMs,
                    lastAttemptAt = nowMs,
                    lastAccessAt = nowMs,
                    failureBackoffUntil = null,
                    rateLimitUntil = null,
                    nextCursorApi = page.nextCursor?.api,
                    activeGeneration = generation,
                    staleTailRetainedAt = null,
                )
            )
        }
        activeSnapshot.publish(feedKey.value, activeItems)
    }

    /** Append a downloaded page while keeping an existing channel's position stable. */
    override suspend fun appendPage(
        feedKey: StreamFeedKey,
        page: StreamFeedPage,
        nowMs: Long,
    ) = withContext(Dispatchers.IO) {
        var activeItems: List<Stream> = emptyList()
        database.runInTransaction {
            val current = dao.state(feedKey.value) ?: StreamFeedState(feedKey.value)
            val existing = dao.itemsForFeed(feedKey.value)
            val items = appendCachedPage(feedKey.value, existing, page.items, current.activeGeneration)
            dao.deleteItems(feedKey.value)
            if (items.isNotEmpty()) {
                dao.insertItems(items)
            }
            val existingStreams = (activeSnapshot.current(feedKey.value)
                ?: existing.map(CachedStreamFeedItem::toStream))
                .filter { it.thumbnailGeneration == current.activeGeneration }
            activeItems = appendStreams(
                existing = existingStreams,
                streams = page.items,
                generation = current.activeGeneration,
            )
            dao.insertState(
                current.copy(
                    nextCursor = page.nextCursor?.value,
                    lastSuccessAt = current.lastSuccessAt ?: nowMs,
                    lastAttemptAt = nowMs,
                    lastAccessAt = nowMs,
                    failureBackoffUntil = null,
                    rateLimitUntil = null,
                    nextCursorApi = page.nextCursor?.api,
                    staleTailRetainedAt = null,
                )
            )
        }
        activeSnapshot.publish(feedKey.value, activeItems)
    }

    override suspend fun pruneStaleGeneration(feedKey: StreamFeedKey) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            dao.state(feedKey.value)?.let { state ->
                dao.deleteItemsExceptGeneration(feedKey.value, state.activeGeneration)
                dao.insertState(state.copy(staleTailRetainedAt = null))
            }
        }
    }

    /** Keep old rows and lastSuccessAt intact when an automatic request fails. */
    override suspend fun recordFailure(
        feedKey: StreamFeedKey,
        nowMs: Long,
        failureBackoffUntil: Long?,
        rateLimitUntil: Long?,
    ) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value) ?: StreamFeedState(feedKey.value)
            dao.insertState(
                current.copy(
                    lastAttemptAt = nowMs,
                    lastAccessAt = nowMs,
                    failureBackoffUntil = failureBackoffUntil,
                    rateLimitUntil = rateLimitUntil,
                    staleTailRetainedAt = null,
                )
            )
        }
    }

    override suspend fun invalidatePrefix(prefix: String, nowMs: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            dao.allStates()
                .filter { it.feedKey.startsWith(prefix) }
                .forEach { state ->
                    dao.insertState(
                        state.copy(
                            lastSuccessAt = null,
                            lastAccessAt = nowMs,
                        )
                    )
                }
        }
    }

    override suspend fun cleanup(nowMs: Long) = withContext(Dispatchers.IO) {
        val removedKeys = mutableSetOf<String>()
        database.runInTransaction {
            val cutoff = nowMs - FEED_RETENTION_MS
            val states = dao.allStates()
            states.filter { it.staleTailRetainedAt != null }
                .forEach { state ->
                    dao.deleteItemsExceptGeneration(state.feedKey, state.activeGeneration)
                    dao.insertState(state.copy(staleTailRetainedAt = null))
                }
            states.filter { it.lastAccessAt <= cutoff }
                .forEach { state ->
                    dao.deleteItems(state.feedKey)
                    dao.deleteState(state.feedKey)
                    removedKeys += state.feedKey
                }
            states.asSequence()
                .filter { it.lastAccessAt >= cutoff }
                .drop(MAX_FEED_VARIANTS)
                .forEach { state ->
                    dao.deleteItems(state.feedKey)
                    dao.deleteState(state.feedKey)
                    removedKeys += state.feedKey
                }
        }
        removedKeys.forEach(activeSnapshot::clear)
    }
}
