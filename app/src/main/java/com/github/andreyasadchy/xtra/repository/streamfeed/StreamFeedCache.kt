package com.github.andreyasadchy.xtra.repository.streamfeed

import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.db.CachedStreamFeedItem
import com.github.andreyasadchy.xtra.db.StreamFeedDao
import com.github.andreyasadchy.xtra.db.StreamFeedState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.datasource.StreamFeedPage
import kotlinx.coroutines.Dispatchers
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
        preserveTail: Boolean,
        pruneStaleOnEnd: Boolean,
    )
    suspend fun appendPage(
        feedKey: StreamFeedKey,
        page: StreamFeedPage,
        nowMs: Long,
        pruneStaleOnEnd: Boolean,
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

/**
 * Apply a fresh first page without dropping the rows loaded by an older
 * generation. The returned layout is already normalized so Room can upsert
 * it in one transaction without duplicate positions.
 */
internal fun refreshCachedItemsPreservingTail(
    feedKey: String,
    existing: List<CachedStreamFeedItem>,
    streams: List<Stream>,
    generation: Long,
): List<CachedStreamFeedItem> {
    val refreshed = refreshCachedItems(feedKey, streams, generation)
    val refreshedKeys = refreshed.map { it.itemKey }.toSet()
    val staleTail = existing
        .filterNot { it.itemKey in refreshedKeys }
        .sortedBy { it.position }
        .mapIndexed { index, item ->
            item.copy(position = refreshed.size + index)
        }
    return refreshed + staleTail
}

/**
 * Apply one newly downloaded page while retaining an older generation as a
 * stale tail. Active rows form a contiguous prefix; every remaining stale
 * row is reindexed after it. This makes position ordering deterministic even
 * when the remote page adds, removes, or overlaps different channels.
 */
internal fun appendCachedPage(
    feedKey: String,
    existing: List<CachedStreamFeedItem>,
    streams: List<Stream>,
    generation: Long = 0L,
): List<CachedStreamFeedItem> {
    val activeRows = existing
        .filter { it.generation == generation }
        .sortedBy { it.position }
    val staleRows = existing
        .filter { it.generation != generation }
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
    val pageKeys = pageStreams.map { it.first }.toSet()
    val staleTail = staleRows
        .filterNot { it.itemKey in pageKeys }
        .mapIndexed { index, item ->
            item.copy(position = activePrefix.size + index)
        }
    return activePrefix + staleTail
}

/**
 * Room is the source of truth for stream-feed data. All replacement and page
 * application methods below run their writes in one SQLite transaction.
 */
class StreamFeedCache(
    private val database: AppDatabase,
    private val dao: StreamFeedDao = database.streamFeedDao(),
) : StreamFeedCacheStore {

    override fun pagingSource(feedKey: StreamFeedKey) = dao.pagingSource(feedKey.value)

    override suspend fun state(feedKey: StreamFeedKey): StreamFeedState? = withContext(Dispatchers.IO) {
        dao.state(feedKey.value)
    }

    override suspend fun itemCount(feedKey: StreamFeedKey): Int = withContext(Dispatchers.IO) {
        dao.itemsForFeed(feedKey.value).size
    }

    override suspend fun touchAccess(feedKey: StreamFeedKey, nowMs: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            dao.insertState(
                (current ?: StreamFeedState(feedKey.value)).copy(lastAccessAt = nowMs)
            )
        }
    }

    override suspend fun markAttempt(feedKey: StreamFeedKey, nowMs: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            dao.insertState(
                (current ?: StreamFeedState(feedKey.value)).copy(
                    lastAttemptAt = nowMs,
                    lastAccessAt = nowMs,
                )
            )
        }
    }

    /** Replace the cached refresh page only after its network request succeeded. */
    override suspend fun replaceAfterRefresh(
        feedKey: StreamFeedKey,
        page: StreamFeedPage,
        nowMs: Long,
        preserveTail: Boolean,
        pruneStaleOnEnd: Boolean,
    ) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            val generation = (current?.activeGeneration ?: 0L) + 1L
            val items = if (preserveTail) {
                refreshCachedItemsPreservingTail(
                    feedKey = feedKey.value,
                    existing = dao.itemsForFeed(feedKey.value),
                    streams = page.items,
                    generation = generation,
                )
            } else {
                refreshCachedItems(feedKey.value, page.items, generation)
            }
            if (!preserveTail) {
                dao.deleteItems(feedKey.value)
            }
            if (items.isNotEmpty()) {
                dao.insertItems(items)
            }
            if (page.nextCursor == null && pruneStaleOnEnd) {
                dao.deleteItemsExceptGeneration(feedKey.value, generation)
            }
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
                )
            )
        }
    }

    /** Append a downloaded page while keeping an existing channel's position stable. */
    override suspend fun appendPage(
        feedKey: StreamFeedKey,
        page: StreamFeedPage,
        nowMs: Long,
        pruneStaleOnEnd: Boolean,
    ) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value) ?: StreamFeedState(feedKey.value)
            val existing = dao.itemsForFeed(feedKey.value)
            val items = appendCachedPage(feedKey.value, existing, page.items, current.activeGeneration)
            if (items.isNotEmpty()) {
                dao.insertItems(items)
            }
            if (page.nextCursor == null && pruneStaleOnEnd) {
                dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            }
            dao.insertState(
                current.copy(
                    nextCursor = page.nextCursor?.value,
                    lastSuccessAt = current.lastSuccessAt ?: nowMs,
                    lastAttemptAt = nowMs,
                    lastAccessAt = nowMs,
                    failureBackoffUntil = null,
                    rateLimitUntil = null,
                    nextCursorApi = page.nextCursor?.api,
                )
            )
        }
    }

    override suspend fun pruneStaleGeneration(feedKey: StreamFeedKey) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            dao.state(feedKey.value)?.let { state ->
                dao.deleteItemsExceptGeneration(feedKey.value, state.activeGeneration)
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
        database.runInTransaction {
            val cutoff = nowMs - FEED_RETENTION_MS
            val states = dao.allStates()
            states.filter { it.lastAccessAt <= cutoff }
                .forEach { state ->
                    dao.deleteItems(state.feedKey)
                    dao.deleteState(state.feedKey)
                }
            states.asSequence()
                .filter { it.lastAccessAt >= cutoff }
                .drop(MAX_FEED_VARIANTS)
                .forEach { state ->
                    dao.deleteItems(state.feedKey)
                    dao.deleteState(state.feedKey)
                }
        }
    }
}
