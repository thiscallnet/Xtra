package com.github.andreyasadchy.xtra.repository.datasource

import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.util.C
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlin.time.Instant

/**
 * A page cursor carries the API that created it. Twitch cursors are not
 * interchangeable between GraphQL, persisted GraphQL, and Helix, so this
 * identity must survive the loader instance and process that created it.
 */
data class StreamFeedCursor(
    val api: String,
    val value: String,
)

data class StreamFeedPage(
    val items: List<Stream>,
    val nextCursor: StreamFeedCursor?,
)

interface StreamFeedPageLoader {
    suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage
}

internal fun String?.toStreamFeedCursor(api: String): StreamFeedCursor? {
    return this?.takeIf { it.isNotBlank() }?.let { StreamFeedCursor(api, it) }
}

class StreamFeedIntegrityException : IOException(C.FAILED_INTEGRITY_CHECK)

private fun checkIntegrity(enabled: Boolean, errors: Iterable<Any?>?) {
    if (enabled && errors?.any { it.toString().contains(C.FAILED_INTEGRITY_CHECK) } == true) {
        throw StreamFeedIntegrityException()
    }
}

/** Select an API only for the first page; subsequent cursors stay on it. */
internal suspend fun <T> loadFollowedFirstPageWithFallback(
    onApiSelected: (String) -> Unit,
    gql: suspend () -> T,
    persistedGql: suspend () -> T,
    helix: suspend () -> T,
): T {
    return try {
        onApiSelected(C.GQL)
        gql()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (error is StreamFeedIntegrityException) throw error
        try {
            onApiSelected(C.GQL_PERSISTED_QUERY)
            persistedGql()
        } catch (error2: Exception) {
            if (error2 is CancellationException) throw error2
            if (error2 is StreamFeedIntegrityException) throw error2
            onApiSelected(C.HELIX)
            helix()
        }
    }
}

internal suspend fun <T> loadFollowedPageForCursor(
    cursor: StreamFeedCursor,
    gql: suspend (String) -> T,
    persistedGql: suspend (String) -> T,
    helix: suspend (String) -> T,
): T = when (cursor.api) {
    C.GQL -> gql(cursor.value)
    C.GQL_PERSISTED_QUERY -> persistedGql(cursor.value)
    C.HELIX -> helix(cursor.value)
    else -> throw IOException("Unknown followed-stream cursor API: ${cursor.api}")
}

class TopStreamsPageLoader(
    private val gqlQueryLanguages: List<Language>?,
    private val gqlQuerySort: StreamSort?,
    private val gqlLanguages: List<String>?,
    private val gqlSort: String?,
    private val tags: List<String>?,
    private val gqlHeaders: () -> Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: () -> Map<String, String>,
    private val helixRepository: HelixRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
    private val pageSize: Int = 30,
) : StreamFeedPageLoader {
    private var api: String? = null

    override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
        if (cursor == null) api = null
        if (cursor != null) return loadFromApi(cursor)
        return if (api == null) {
            try {
                api = C.GQL
                loadFromApi(cursor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is StreamFeedIntegrityException) throw error
                try {
                    api = C.GQL_PERSISTED_QUERY
                    loadFromApi(cursor)
                } catch (error2: Exception) {
                    if (error2 is CancellationException) throw error2
                    if (error2 is StreamFeedIntegrityException) throw error2
                    api = C.HELIX
                    loadFromApi(cursor)
                }
            }
        } else {
            loadFromApi(cursor)
        }
    }

    private suspend fun loadFromApi(cursor: StreamFeedCursor?): StreamFeedPage = when (cursor?.api ?: api) {
        C.GQL -> gqlQueryLoad(cursor?.value)
        C.GQL_PERSISTED_QUERY -> gqlLoad(cursor?.value)
        C.HELIX -> if (!helixHeaders()[C.HEADER_TOKEN].isNullOrBlank() && (gqlSort == "VIEWER_COUNT" || gqlSort == null) && tags.isNullOrEmpty() && gqlQueryLanguages.isNullOrEmpty() && gqlLanguages.isNullOrEmpty()) {
            helixLoad(cursor?.value)
        } else {
            throw IOException("Helix cannot represent this stream filter")
        }
        else -> error("No stream API selected")
    }

    private suspend fun gqlQueryLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadQueryTopStreams(
            networkLibrary,
            gqlHeaders(),
            gqlQuerySort,
            tags,
            gqlQueryLanguages,
            pageSize,
            cursor,
        )
        checkIntegrity(enableIntegrity, response.errors)
        val data = response.data!!.streams!!
        val edges = data.edges!!
        val items = edges.mapNotNull { edge ->
            edge?.node?.let { node ->
                Stream(
                    id = node.id,
                    channelId = node.broadcaster?.id,
                    channelLogin = node.broadcaster?.login,
                    channelName = node.broadcaster?.displayName,
                    channelImageURL = node.broadcaster?.profileImageURL,
                    gameId = node.game?.id,
                    gameSlug = node.game?.slug,
                    gameName = node.game?.displayName,
                    title = node.broadcaster?.broadcastSettings?.title,
                    thumbnailURL = node.previewImageURL,
                    createdAt = node.createdAt?.toString(),
                    viewerCount = node.viewersCount,
                    tags = node.freeformTags?.mapNotNull { tag -> tag.name },
                ).takeIf { it.channelId != null || it.channelLogin != null }
            }
        }
        return StreamFeedPage(
            items = items,
            nextCursor = edges.lastOrNull()?.cursor?.toString()
                ?.takeIf { !it.isNullOrBlank() && data.pageInfo?.hasNextPage != false }
                .toStreamFeedCursor(C.GQL),
        )
    }

    private suspend fun gqlLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadTopStreams(
            networkLibrary,
            gqlHeaders(),
            gqlSort,
            tags,
            gqlLanguages,
            pageSize,
            cursor,
        )
        checkIntegrity(enableIntegrity, response.errors)
        val data = response.data!!.streams
        val edges = data.edges
        return StreamFeedPage(
            items = edges.mapNotNull { edge ->
                edge.node.let { node ->
                    Stream(
                        id = node.id,
                        channelId = node.broadcaster?.id,
                        channelLogin = node.broadcaster?.login,
                        channelName = node.broadcaster?.displayName,
                        channelImageURL = node.broadcaster?.profileImageURL,
                        gameId = node.game?.id,
                        gameSlug = node.game?.slug,
                        gameName = node.game?.displayName,
                        title = node.title,
                        thumbnailURL = node.previewImageURL,
                        createdAt = node.createdAt,
                        viewerCount = node.viewersCount,
                        tags = node.freeformTags?.mapNotNull { tag -> tag.name },
                    ).takeIf { it.channelId != null || it.channelLogin != null }
                }
            },
            nextCursor = edges.lastOrNull()?.cursor
                ?.takeIf { !it.isNullOrBlank() && data.pageInfo?.hasNextPage != false }
                .toStreamFeedCursor(C.GQL_PERSISTED_QUERY),
        )
    }

    private suspend fun helixLoad(cursor: String?): StreamFeedPage {
        val response = helixRepository.getStreams(
            networkLibrary = networkLibrary,
            headers = helixHeaders(),
            limit = pageSize,
            offset = cursor,
        )
        val users = response.data.mapNotNull { it.channelId }.let {
            helixRepository.getUsers(networkLibrary, helixHeaders(), ids = it).data
        }
        return StreamFeedPage(
            items = response.data.mapNotNull { item ->
                Stream(
                    id = item.id,
                    channelId = item.channelId,
                    channelLogin = item.channelLogin,
                    channelName = item.channelName,
                    channelImageURL = item.channelId?.let { id -> users.find { user -> user.id == id }?.profileImageURL },
                    gameId = item.gameId,
                    gameName = item.gameName,
                    title = item.title,
                    thumbnailURL = item.thumbnailURL,
                    createdAt = item.startedAt,
                    viewerCount = item.viewerCount,
                    tags = item.tags,
                ).takeIf { it.channelId != null || it.channelLogin != null }
            },
            nextCursor = response.pagination?.cursor.toStreamFeedCursor(C.HELIX),
        )
    }
}

class FollowedStreamsPageLoader(
    private val userId: String?,
    private val sort: String,
    private val gqlQuerySort: StreamSort,
    private val localChannelFollowsRepository: LocalChannelFollowsRepository,
    private val gqlHeaders: () -> Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: () -> Map<String, String>,
    private val helixRepository: HelixRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
) : StreamFeedPageLoader {
    private var api: String? = null

    override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
        if (cursor != null) {
            // The persisted cursor is authoritative. This also works after a
            // process restart or when another loader refreshed the feed.
            return loadFromCursor(cursor)
        }
        api = null

        val hasRemoteCredentials = !gqlHeaders()[C.HEADER_TOKEN].isNullOrBlank() ||
                !helixHeaders()[C.HEADER_TOKEN].isNullOrBlank()
        val localIds = localChannelFollowsRepository.getAll().mapNotNull { it.userId }.distinct()
        val localItems = if (localIds.isEmpty()) {
            emptyList()
        } else {
            try {
                loadLocalWithFallback(localIds)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is StreamFeedIntegrityException || !hasRemoteCredentials) throw error
                emptyList()
            }
        }
        val remotePage = if (hasRemoteCredentials) {
            // A followed-account request is required to be successful before
            // it can replace a prior cache. Local follows are an additive
            // fallback, not permission to destructively publish a partial feed.
            loadFirstPageWithFallback()
        } else {
            null
        }
        val merged = merge(localItems, remotePage?.items.orEmpty())
        return StreamFeedPage(merged, remotePage?.nextCursor)
    }

    private suspend fun loadFirstPageWithFallback(): StreamFeedPage {
        return loadFollowedFirstPageWithFallback(
            onApiSelected = { api = it },
            gql = { gqlQueryLoad(null) },
            persistedGql = { gqlLoad(null) },
            helix = { helixLoad(null) },
        )
    }

    private suspend fun loadFromCursor(cursor: StreamFeedCursor): StreamFeedPage {
        val page = loadFollowedPageForCursor(
            cursor = cursor,
            gql = { gqlQueryLoad(it) },
            persistedGql = { gqlLoad(it) },
            helix = { helixLoad(it) },
        )
        return page.copy(items = merge(emptyList(), page.items))
    }

    private suspend fun loadLocalWithFallback(ids: List<String>): List<Stream> {
        if (ids.isEmpty()) return emptyList()
        return try {
            gqlQueryLocal(ids)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is StreamFeedIntegrityException) throw error
            if (helixHeaders()[C.HEADER_TOKEN].isNullOrBlank()) throw error
            helixLocal(ids)
        }
    }

    private suspend fun gqlQueryLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadQueryUserFollowedStreams(networkLibrary, gqlHeaders(), 100, cursor, gqlQuerySort)
        checkIntegrity(enableIntegrity, response.errors)
        val data = response.data!!.user!!.followedLiveUsers!!
        val edges = data.edges!!
        return StreamFeedPage(
            items = edges.mapNotNull { edge ->
                edge?.node?.let { node ->
                    Stream(
                        id = node.stream?.id,
                        channelId = node.id,
                        channelLogin = node.login,
                        channelName = node.displayName,
                        channelImageURL = node.profileImageURL,
                        gameId = node.stream?.game?.id,
                        gameSlug = node.stream?.game?.slug,
                        gameName = node.stream?.game?.displayName,
                        title = node.stream?.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = node.stream?.previewImageURL,
                        createdAt = node.stream?.createdAt?.toString(),
                        viewerCount = node.stream?.viewersCount,
                        tags = node.stream?.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                }
            },
            nextCursor = edges.lastOrNull()?.cursor?.toString()
                ?.takeIf { !it.isNullOrBlank() && data.pageInfo?.hasNextPage != false }
                .toStreamFeedCursor(C.GQL),
        )
    }

    private suspend fun gqlLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadFollowedStreams(networkLibrary, gqlHeaders(), 100, cursor)
        checkIntegrity(enableIntegrity, response.errors)
        val data = response.data!!.currentUser.followedLiveUsers
        val edges = data.edges
        return StreamFeedPage(
            items = edges.map { edge ->
                edge.node.let { node ->
                    Stream(
                        id = node.stream?.id,
                        channelId = node.id,
                        channelLogin = node.login,
                        channelName = node.displayName,
                        channelImageURL = node.profileImageURL,
                        gameId = node.stream?.game?.id,
                        gameSlug = node.stream?.game?.slug,
                        gameName = node.stream?.game?.displayName,
                        title = node.stream?.title,
                        thumbnailURL = node.stream?.previewImageURL,
                        createdAt = node.stream?.createdAt,
                        viewerCount = node.stream?.viewersCount,
                        tags = node.stream?.freeformTags?.mapNotNull { tag -> tag.name },
                    )
                }
            },
            nextCursor = edges.lastOrNull()?.cursor
                ?.takeIf { !it.isNullOrBlank() && data.pageInfo?.hasNextPage != false }
                .toStreamFeedCursor(C.GQL_PERSISTED_QUERY),
        )
    }

    private suspend fun helixLoad(cursor: String?): StreamFeedPage {
        val response = helixRepository.getFollowedStreams(networkLibrary, helixHeaders(), userId, 100, cursor)
        val users = response.data.mapNotNull { it.channelId }.let {
            helixRepository.getUsers(networkLibrary, helixHeaders(), ids = it).data
        }
        return StreamFeedPage(
            items = response.data.map { item ->
                Stream(
                    id = item.id,
                    channelId = item.channelId,
                    channelLogin = item.channelLogin,
                    channelName = item.channelName,
                    channelImageURL = item.channelId?.let { id -> users.find { user -> user.id == id }?.profileImageURL },
                    gameId = item.gameId,
                    gameName = item.gameName,
                    title = item.title,
                    thumbnailURL = item.thumbnailURL,
                    createdAt = item.startedAt,
                    viewerCount = item.viewerCount,
                    tags = item.tags,
                )
            },
            nextCursor = response.pagination?.cursor.toStreamFeedCursor(C.HELIX),
        )
    }

    private suspend fun gqlQueryLocal(ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map { chunk ->
            val response = graphQLRepository.loadQueryUsersStream(networkLibrary, gqlHeaders(), chunk)
            checkIntegrity(enableIntegrity, response.errors)
            response.data!!.users!!
        }.flatMap { it }
        return items.mapNotNull { item ->
            item?.takeIf { it.stream?.viewersCount != null }?.let {
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
            }
        }
    }

    private suspend fun helixLocal(ids: List<String>): List<Stream> {
        val items = ids.chunked(100).map {
            helixRepository.getStreams(networkLibrary, helixHeaders(), ids = it).data
        }.flatMap { it }
        val users = items.mapNotNull { it.channelId }.chunked(100).map {
            helixRepository.getUsers(networkLibrary, helixHeaders(), ids = it).data
        }.flatMap { it }
        return items.mapNotNull { item ->
            item.takeIf { it.viewerCount != null }?.let {
                Stream(
                    id = it.id,
                    channelId = it.channelId,
                    channelLogin = it.channelLogin,
                    channelName = it.channelName,
                    channelImageURL = it.channelId?.let { id -> users.find { user -> user.id == id }?.profileImageURL },
                    gameId = it.gameId,
                    gameName = it.gameName,
                    title = it.title,
                    thumbnailURL = it.thumbnailURL,
                    createdAt = it.startedAt,
                    viewerCount = it.viewerCount,
                    tags = it.tags,
                )
            }
        }
    }

    private fun merge(local: List<Stream>, remote: List<Stream>): List<Stream> {
        val result = remote.toMutableList()
        local.forEach { stream ->
            val existing = result.indexOfFirst { sameChannel(it, stream) }
            if (existing < 0) result += stream
        }
        return when (sort) {
            StreamsSortDialog.SORT_VIEWERS_ASC -> result.sortedBy { it.viewerCount ?: Int.MAX_VALUE }
            StreamsSortDialog.SORT_VIEWERS -> result.sortedByDescending { it.viewerCount ?: -1 }
            StreamsSortDialog.RECENT -> result.sortedByDescending {
                it.createdAt?.let { createdAt -> Instant.parseOrNull(createdAt)?.toEpochMilliseconds() }
                    ?: Long.MIN_VALUE
            }
            else -> result
        }
    }

    private fun sameChannel(first: Stream, second: Stream): Boolean {
        return when {
            !first.channelId.isNullOrBlank() && !second.channelId.isNullOrBlank() -> first.channelId == second.channelId
            !first.channelLogin.isNullOrBlank() && !second.channelLogin.isNullOrBlank() -> first.channelLogin.equals(second.channelLogin, true)
            else -> first.id != null && first.id == second.id
        }
    }
}

class GameStreamsPageLoader(
    private val gameId: String?,
    private val gameSlug: String?,
    private val gameName: String?,
    private val gqlQueryLanguages: List<Language>?,
    private val gqlQuerySort: StreamSort?,
    private val gqlLanguages: List<String>?,
    private val gqlSort: String?,
    private val tags: List<String>?,
    private val gqlHeaders: () -> Map<String, String>,
    private val graphQLRepository: GraphQLRepository,
    private val helixHeaders: () -> Map<String, String>,
    private val helixRepository: HelixRepository,
    private val enableIntegrity: Boolean,
    private val networkLibrary: String?,
    private val pageSize: Int = 30,
) : StreamFeedPageLoader {
    private var api: String? = null

    override suspend fun load(cursor: StreamFeedCursor?): StreamFeedPage {
        if (cursor == null) api = null
        if (cursor != null) return loadFromApi(cursor)
        return if (api == null) {
            try {
                api = C.GQL
                loadFromApi(cursor)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is StreamFeedIntegrityException) throw error
                try {
                    api = C.GQL_PERSISTED_QUERY
                    loadFromApi(cursor)
                } catch (error2: Exception) {
                    if (error2 is CancellationException) throw error2
                    if (error2 is StreamFeedIntegrityException) throw error2
                    api = C.HELIX
                    loadFromApi(cursor)
                }
            }
        } else {
            loadFromApi(cursor)
        }
    }

    private suspend fun loadFromApi(cursor: StreamFeedCursor?): StreamFeedPage = when (cursor?.api ?: api) {
        C.GQL -> if (gameId != null || gameSlug != null || gameName != null) gqlQueryLoad(cursor?.value) else throw IOException("Missing game identity")
        C.GQL_PERSISTED_QUERY -> gqlLoad(cursor?.value)
        C.HELIX -> if (!helixHeaders()[C.HEADER_TOKEN].isNullOrBlank() && (gqlSort == "VIEWER_COUNT" || gqlSort == null) && tags.isNullOrEmpty() && gqlQueryLanguages.isNullOrEmpty() && gqlLanguages.isNullOrEmpty()) {
            helixLoad(cursor?.value)
        } else {
            throw IOException("Helix cannot represent this game stream filter")
        }
        else -> error("No stream API selected")
    }

    private suspend fun gqlQueryLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadQueryGameStreams(
            networkLibrary = networkLibrary,
            headers = gqlHeaders(),
            id = gameId,
            slug = gameSlug.takeIf { gameId.isNullOrBlank() },
            name = gameName.takeIf { gameId.isNullOrBlank() && gameSlug.isNullOrBlank() },
            sort = gqlQuerySort,
            tags = tags,
            languages = gqlQueryLanguages,
            first = pageSize,
            after = cursor,
        )
        checkIntegrity(enableIntegrity, response.errors)
        val data = response.data!!.game!!.streams!!
        val edges = data.edges!!
        return StreamFeedPage(
            items = edges.mapNotNull { edge ->
                edge?.node?.let { node ->
                    Stream(
                        id = node.id,
                        channelId = node.broadcaster?.id,
                        channelLogin = node.broadcaster?.login,
                        channelName = node.broadcaster?.displayName,
                        channelImageURL = node.broadcaster?.profileImageURL,
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName,
                        title = node.broadcaster?.broadcastSettings?.title,
                        thumbnailURL = node.previewImageURL,
                        createdAt = node.createdAt?.toString(),
                        viewerCount = node.viewersCount,
                        tags = node.freeformTags?.mapNotNull { tag -> tag.name },
                    ).takeIf { it.channelId != null || it.channelLogin != null }
                }
            },
            nextCursor = edges.lastOrNull()?.cursor?.toString()
                ?.takeIf { !it.isNullOrBlank() && data.pageInfo?.hasNextPage != false }
                .toStreamFeedCursor(C.GQL),
        )
    }

    private suspend fun gqlLoad(cursor: String?): StreamFeedPage {
        val response = graphQLRepository.loadGameStreams(networkLibrary, gqlHeaders(), gameSlug, gqlSort, tags, gqlLanguages, pageSize, cursor)
        checkIntegrity(enableIntegrity, response.errors)
        val data = response.data!!.game.streams
        val edges = data.edges
        return StreamFeedPage(
            items = edges.mapNotNull { edge ->
                edge.node.let { node ->
                    Stream(
                        id = node.id,
                        channelId = node.broadcaster?.id,
                        channelLogin = node.broadcaster?.login,
                        channelName = node.broadcaster?.displayName,
                        channelImageURL = node.broadcaster?.profileImageURL,
                        gameId = gameId,
                        gameSlug = gameSlug,
                        gameName = gameName,
                        title = node.title,
                        thumbnailURL = node.previewImageURL,
                        createdAt = node.createdAt,
                        viewerCount = node.viewersCount,
                        tags = node.freeformTags?.mapNotNull { tag -> tag.name },
                    ).takeIf { it.channelId != null || it.channelLogin != null }
                }
            },
            nextCursor = edges.lastOrNull()?.cursor
                ?.takeIf { !it.isNullOrBlank() && data.pageInfo?.hasNextPage != false }
                .toStreamFeedCursor(C.GQL_PERSISTED_QUERY),
        )
    }

    private suspend fun helixLoad(cursor: String?): StreamFeedPage {
        val response = helixRepository.getStreams(
            networkLibrary,
            helixHeaders(),
            gameId = gameId,
            limit = pageSize,
            offset = cursor,
        )
        val users = response.data.mapNotNull { it.channelId }.let {
            helixRepository.getUsers(networkLibrary, helixHeaders(), ids = it).data
        }
        return StreamFeedPage(
            items = response.data.mapNotNull { item ->
                Stream(
                    id = item.id,
                    channelId = item.channelId,
                    channelLogin = item.channelLogin,
                    channelName = item.channelName,
                    channelImageURL = item.channelId?.let { id -> users.find { user -> user.id == id }?.profileImageURL },
                    gameId = gameId,
                    gameSlug = gameSlug,
                    gameName = gameName,
                    title = item.title,
                    thumbnailURL = item.thumbnailURL,
                    createdAt = item.startedAt,
                    viewerCount = item.viewerCount,
                    tags = item.tags,
                ).takeIf { it.channelId != null || it.channelLogin != null }
            },
            nextCursor = response.pagination?.cursor.toStreamFeedCursor(C.HELIX),
        )
    }
}
