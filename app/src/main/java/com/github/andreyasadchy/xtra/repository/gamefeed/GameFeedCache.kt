package com.github.andreyasadchy.xtra.repository.gamefeed

import com.github.andreyasadchy.xtra.db.AppDatabase
import com.github.andreyasadchy.xtra.db.CachedGameFeedItem
import com.github.andreyasadchy.xtra.db.GameFeedDao
import com.github.andreyasadchy.xtra.db.GameFeedState
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.repository.datasource.GameFeedPage
import com.github.andreyasadchy.xtra.repository.ProcessLocalFeedSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val FEED_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L
private const val MAX_FEED_VARIANTS = 40

interface GameFeedCacheStore {
    fun pagingSource(feedKey: GameFeedKey): androidx.paging.PagingSource<Int, CachedGameFeedItem>
    suspend fun state(feedKey: GameFeedKey): GameFeedState?
    suspend fun itemCount(feedKey: GameFeedKey): Int
    suspend fun touchAccess(feedKey: GameFeedKey, nowMs: Long)
    suspend fun markAttempt(feedKey: GameFeedKey, nowMs: Long)
    suspend fun replaceAfterRefresh(feedKey: GameFeedKey, page: GameFeedPage, nowMs: Long, preserveTail: Boolean, pruneStaleOnEnd: Boolean)
    suspend fun appendPage(feedKey: GameFeedKey, page: GameFeedPage, nowMs: Long, pruneStaleOnEnd: Boolean)
    suspend fun pruneStaleGeneration(feedKey: GameFeedKey)
    suspend fun recordFailure(feedKey: GameFeedKey, nowMs: Long, failureBackoffUntil: Long?, rateLimitUntil: Long?)
    suspend fun cleanup(nowMs: Long)
}

internal fun Game.cacheItemKey(): String? {
    return id?.trim()?.takeIf { it.isNotEmpty() }?.let { "id:$it" }
        ?: slug?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "slug:$it" }
        ?: name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "name:$it" }
}

private fun encodeTags(tags: List<Tag>): String = JSONArray().apply {
    tags.forEach { tag ->
        put(JSONObject().apply {
            tag.id?.let { put("id", it) }
            tag.name?.let { put("name", it) }
        })
    }
}.toString()

private fun decodeTags(value: String): List<Tag>? = runCatching {
    JSONArray(value).let { array ->
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            Tag(
                id = item.optString("id").takeIf { it.isNotEmpty() },
                name = item.optString("name").takeIf { it.isNotEmpty() },
            )
        }
    }
}.getOrNull()

internal fun Game.toCachedGameFeedItem(feedKey: String, position: Int, generation: Long): CachedGameFeedItem? {
    return cacheItemKey()?.let { itemKey ->
        CachedGameFeedItem(
            feedKey = feedKey,
            itemKey = itemKey,
            position = position,
            gameId = id,
            gameSlug = slug,
            gameName = name,
            boxArtURL = boxArtURL,
            viewerCount = viewerCount,
            broadcasterCount = broadcasterCount,
            tags = tags?.let(::encodeTags),
            generation = generation,
        )
    }
}

internal fun CachedGameFeedItem.toGame(): Game = Game(
    id = gameId,
    slug = gameSlug,
    name = gameName,
    boxArtURL = boxArtURL,
    viewerCount = viewerCount,
    broadcasterCount = broadcasterCount,
    tags = tags?.let(::decodeTags),
)

private fun refreshCachedGames(feedKey: String, games: List<Game>, generation: Long): List<CachedGameFeedItem> = games
    .distinctBy { it.cacheItemKey() }
    .mapIndexedNotNull { index, game -> game.toCachedGameFeedItem(feedKey, index, generation) }

private fun refreshCachedGamesPreservingTail(
    feedKey: String,
    existing: List<CachedGameFeedItem>,
    games: List<Game>,
    generation: Long,
): List<CachedGameFeedItem> {
    val refreshed = refreshCachedGames(feedKey, games, generation)
    val refreshedKeys = refreshed.map { it.itemKey }.toSet()
    val retainedGenerations = existing.asSequence().map { it.generation }.distinct().sortedDescending()
        .take(GameFeedFreshnessPolicy.MAX_RETAINED_STALE_GENERATIONS).toSet()
    val staleTail = existing.filter { it.generation in retainedGenerations }
        .filterNot { it.itemKey in refreshedKeys }
        .sortedBy { it.position }
        .mapIndexed { index, item -> item.copy(position = refreshed.size + index) }
    return refreshed + staleTail
}

private fun appendCachedGames(
    feedKey: String,
    existing: List<CachedGameFeedItem>,
    games: List<Game>,
    generation: Long,
): List<CachedGameFeedItem> {
    val activeRows = existing.filter { it.generation == generation }.sortedBy { it.position }
    val staleRows = existing.filter { it.generation != generation }.sortedBy { it.position }
    val pageGames = games.mapNotNull { game -> game.cacheItemKey()?.let { it to game } }.distinctBy { it.first }
    val pageByKey = pageGames.toMap()
    val activeKeys = activeRows.map { it.itemKey }.toSet()
    val updatedActive = activeRows.map { item ->
        pageByKey[item.itemKey]?.toCachedGameFeedItem(feedKey, item.position, generation) ?: item
    }
    val newActive = pageGames.filterNot { it.first in activeKeys }
        .mapNotNull { it.second.toCachedGameFeedItem(feedKey, 0, generation) }
    val activePrefix = (updatedActive + newActive).mapIndexed { index, item -> item.copy(position = index, generation = generation) }
    val pageKeys = pageGames.map { it.first }.toSet()
    val staleTail = staleRows.filterNot { it.itemKey in pageKeys }
        .mapIndexed { index, item -> item.copy(position = activePrefix.size + index) }
    return activePrefix + staleTail
}

class GameFeedCache(
    private val database: AppDatabase,
    private val dao: GameFeedDao = database.gameFeedDao(),
) : GameFeedCacheStore {

    private val activeSnapshot = ProcessLocalFeedSnapshot<CachedGameFeedItem>()

    override fun pagingSource(feedKey: GameFeedKey) = dao.pagingSource(feedKey.value)

    fun activeItemsFlow(feedKey: GameFeedKey, limit: Int): Flow<List<CachedGameFeedItem>> =
        activeSnapshot.flow(feedKey.value, limit) {
            withContext(Dispatchers.IO) {
                dao.activeItemsFlow(feedKey.value, Int.MAX_VALUE).first()
            }
        }

    override suspend fun state(feedKey: GameFeedKey): GameFeedState? = withContext(Dispatchers.IO) { dao.state(feedKey.value) }

    override suspend fun itemCount(feedKey: GameFeedKey): Int = withContext(Dispatchers.IO) { dao.activeItemCount(feedKey.value) }

    override suspend fun touchAccess(feedKey: GameFeedKey, nowMs: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            val expired = staleTailExpired(current, nowMs)
            if (expired && current != null) dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            dao.insertState((current ?: GameFeedState(feedKey.value)).copy(
                lastAccessAt = nowMs,
                staleTailRetainedAt = if (expired) null else current?.staleTailRetainedAt,
            ))
        }
    }

    override suspend fun markAttempt(feedKey: GameFeedKey, nowMs: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            val expired = staleTailExpired(current, nowMs)
            if (expired && current != null) dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            dao.insertState((current ?: GameFeedState(feedKey.value)).copy(
                lastAttemptAt = nowMs,
                lastAccessAt = nowMs,
                staleTailRetainedAt = if (expired) null else current?.staleTailRetainedAt,
            ))
        }
    }

    override suspend fun replaceAfterRefresh(
        feedKey: GameFeedKey,
        page: GameFeedPage,
        nowMs: Long,
        preserveTail: Boolean,
        pruneStaleOnEnd: Boolean,
    ) = withContext(Dispatchers.IO) {
        var activeItems: List<CachedGameFeedItem> = emptyList()
        database.runInTransaction {
            val current = dao.state(feedKey.value)
            val generation = (current?.activeGeneration ?: 0L) + 1L
            val expired = staleTailExpired(current, nowMs)
            if (expired && current != null) dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            val items = if (preserveTail) {
                refreshCachedGamesPreservingTail(feedKey.value, dao.itemsForFeed(feedKey.value), page.items, generation)
            } else {
                refreshCachedGames(feedKey.value, page.items, generation)
            }
            if (!preserveTail) dao.deleteItems(feedKey.value)
            if (items.isNotEmpty()) dao.insertItems(items)
            if (page.nextCursor == null && pruneStaleOnEnd) dao.deleteItemsExceptGeneration(feedKey.value, generation)
            activeItems = items.filter { it.generation == generation }
            val retainsTail = preserveTail && !(page.nextCursor == null && pruneStaleOnEnd) && items.any { it.generation != generation }
            dao.insertState(GameFeedState(
                feedKey = feedKey.value,
                nextCursor = page.nextCursor?.value,
                lastSuccessAt = nowMs,
                lastAttemptAt = nowMs,
                lastAccessAt = nowMs,
                nextCursorApi = page.nextCursor?.api,
                activeGeneration = generation,
                staleTailRetainedAt = if (retainsTail) current?.staleTailRetainedAt?.takeUnless { expired } ?: nowMs else null,
            ))
        }
        activeSnapshot.publish(feedKey.value, activeItems)
    }

    override suspend fun appendPage(feedKey: GameFeedKey, page: GameFeedPage, nowMs: Long, pruneStaleOnEnd: Boolean) = withContext(Dispatchers.IO) {
        var activeItems: List<CachedGameFeedItem> = emptyList()
        database.runInTransaction {
            val current = dao.state(feedKey.value) ?: GameFeedState(feedKey.value)
            val expired = staleTailExpired(current, nowMs)
            if (expired) dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            val items = appendCachedGames(feedKey.value, dao.itemsForFeed(feedKey.value), page.items, current.activeGeneration)
            if (items.isNotEmpty()) dao.insertItems(items)
            if (page.nextCursor == null && pruneStaleOnEnd) dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            activeItems = items.filter { it.generation == current.activeGeneration }
            val retainsTail = !(page.nextCursor == null && pruneStaleOnEnd) && items.any { it.generation != current.activeGeneration }
            dao.insertState(current.copy(
                nextCursor = page.nextCursor?.value,
                lastSuccessAt = current.lastSuccessAt ?: nowMs,
                lastAttemptAt = nowMs,
                lastAccessAt = nowMs,
                failureBackoffUntil = null,
                rateLimitUntil = null,
                nextCursorApi = page.nextCursor?.api,
                staleTailRetainedAt = if (retainsTail) current.staleTailRetainedAt?.takeUnless { expired } ?: nowMs else null,
            ))
        }
        activeSnapshot.publish(feedKey.value, activeItems)
    }

    override suspend fun pruneStaleGeneration(feedKey: GameFeedKey) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            dao.state(feedKey.value)?.let { state ->
                dao.deleteItemsExceptGeneration(feedKey.value, state.activeGeneration)
                dao.insertState(state.copy(staleTailRetainedAt = null))
            }
        }
    }

    override suspend fun recordFailure(feedKey: GameFeedKey, nowMs: Long, failureBackoffUntil: Long?, rateLimitUntil: Long?) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            val current = dao.state(feedKey.value) ?: GameFeedState(feedKey.value)
            val expired = staleTailExpired(current, nowMs)
            if (expired) dao.deleteItemsExceptGeneration(feedKey.value, current.activeGeneration)
            dao.insertState(current.copy(
                lastAttemptAt = nowMs,
                lastAccessAt = nowMs,
                failureBackoffUntil = failureBackoffUntil,
                rateLimitUntil = rateLimitUntil,
                staleTailRetainedAt = if (expired) null else current.staleTailRetainedAt,
            ))
        }
    }

    override suspend fun cleanup(nowMs: Long) = withContext(Dispatchers.IO) {
        val removedKeys = mutableSetOf<String>()
        database.runInTransaction {
            val cutoff = nowMs - FEED_RETENTION_MS
            val states = dao.allStates()
            states.filter { staleTailExpired(it, nowMs) }.forEach { state ->
                dao.deleteItemsExceptGeneration(state.feedKey, state.activeGeneration)
                dao.insertState(state.copy(staleTailRetainedAt = null))
            }
            states.filter { it.lastAccessAt <= cutoff }.forEach { state ->
                dao.deleteItems(state.feedKey)
                dao.deleteState(state.feedKey)
                removedKeys += state.feedKey
            }
            states.asSequence()
                .filter { it.lastAccessAt >= cutoff }
                .sortedByDescending { it.lastAccessAt }
                .drop(MAX_FEED_VARIANTS)
                .forEach { state ->
                dao.deleteItems(state.feedKey)
                dao.deleteState(state.feedKey)
                removedKeys += state.feedKey
            }
        }
        removedKeys.forEach(activeSnapshot::clear)
    }

    private fun staleTailExpired(state: GameFeedState?, nowMs: Long): Boolean = state != null &&
        state.activeGeneration != 0L && state.staleTailRetainedAt?.let { nowMs - it >= GameFeedFreshnessPolicy.MAX_RETAINED_STALE_TAIL_AGE_MS } == true
}
