package com.github.andreyasadchy.xtra.repository.streamfeed

import android.content.Context
import com.github.andreyasadchy.xtra.graphql.type.Language
import com.github.andreyasadchy.xtra.graphql.type.StreamSort
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.LocalChannelFollowsRepository
import com.github.andreyasadchy.xtra.repository.datasource.FollowedStreamsPageLoader
import com.github.andreyasadchy.xtra.repository.datasource.GameStreamsPageLoader
import com.github.andreyasadchy.xtra.repository.datasource.TopStreamsPageLoader
import com.github.andreyasadchy.xtra.ui.common.StreamsSortDialog
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import java.util.Locale

object StreamFeedSpecs {

    fun top(
        context: Context,
        graphQLRepository: GraphQLRepository,
        helixRepository: HelixRepository,
        sort: String,
        tags: Iterable<String>?,
        languages: Iterable<String>?,
    ): StreamFeedSpec {
        val apiTags = apiValues(tags)
        val apiLanguages = apiValues(languages)
        return StreamFeedSpec(
            key = StreamFeedKey.top(sort, apiTags, apiLanguages),
            loader = TopStreamsPageLoader(
                gqlQueryLanguages = apiLanguages.takeIf { it.isNotEmpty() }?.mapNotNull { value -> Language.entries.find { it.rawValue == value } },
                gqlQuerySort = querySort(sort),
                gqlLanguages = apiLanguages.takeIf { it.isNotEmpty() },
                gqlSort = querySortName(sort),
                tags = apiTags.takeIf { it.isNotEmpty() },
                gqlHeaders = { TwitchApiHelper.getGQLHeaders(context, true) },
                graphQLRepository = graphQLRepository,
                helixHeaders = { TwitchApiHelper.getHelixHeaders(context) },
                helixRepository = helixRepository,
                networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            ),
        )
    }

    fun followed(
        context: Context,
        userId: String?,
        localChannelFollowsRepository: LocalChannelFollowsRepository,
        graphQLRepository: GraphQLRepository,
        helixRepository: HelixRepository,
        sort: String = followedSort(context),
    ): StreamFeedSpec {
        return StreamFeedSpec(
            key = StreamFeedKey.followed(userId, sort),
            loader = FollowedStreamsPageLoader(
                userId = userId,
                sort = sort,
                gqlQuerySort = querySort(sort),
                localChannelFollowsRepository = localChannelFollowsRepository,
                gqlHeaders = { TwitchApiHelper.getGQLHeaders(context, true) },
                graphQLRepository = graphQLRepository,
                helixHeaders = { TwitchApiHelper.getHelixHeaders(context) },
                helixRepository = helixRepository,
                networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            ),
        )
    }

    fun game(
        context: Context,
        graphQLRepository: GraphQLRepository,
        helixRepository: HelixRepository,
        gameId: String?,
        gameSlug: String?,
        gameName: String?,
        sort: String,
        tags: Iterable<String>?,
        languages: Iterable<String>?,
    ): StreamFeedSpec {
        val apiTags = apiValues(tags)
        val apiLanguages = apiValues(languages)
        return StreamFeedSpec(
            key = StreamFeedKey.game(gameId, gameSlug, gameName, sort, apiTags, apiLanguages),
            loader = GameStreamsPageLoader(
                gameId = gameId,
                gameSlug = gameSlug,
                gameName = gameName,
                gqlQueryLanguages = apiLanguages.takeIf { it.isNotEmpty() }?.mapNotNull { value -> Language.entries.find { it.rawValue == value } },
                gqlQuerySort = querySort(sort),
                gqlLanguages = apiLanguages.takeIf { it.isNotEmpty() },
                gqlSort = querySortName(sort),
                tags = apiTags.takeIf { it.isNotEmpty() },
                gqlHeaders = { TwitchApiHelper.getGQLHeaders(context, true) },
                graphQLRepository = graphQLRepository,
                helixHeaders = { TwitchApiHelper.getHelixHeaders(context) },
                helixRepository = helixRepository,
                networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            ),
        )
    }

    private fun apiValues(values: Iterable<String>?): List<String> {
        return values?.toList().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedBy { it.lowercase(Locale.ROOT) }
    }

    private fun querySort(sort: String): StreamSort = when (sort) {
        StreamsSortDialog.RELEVANCE -> StreamSort.RELEVANCE
        StreamsSortDialog.SORT_VIEWERS_ASC -> StreamSort.VIEWER_COUNT_ASC
        StreamsSortDialog.RECENT -> StreamSort.RECENT
        else -> StreamSort.VIEWER_COUNT
    }

    private fun querySortName(sort: String): String = when (sort) {
        StreamsSortDialog.RELEVANCE -> "RELEVANCE"
        StreamsSortDialog.SORT_VIEWERS_ASC -> "VIEWER_COUNT_ASC"
        StreamsSortDialog.RECENT -> "RECENT"
        else -> "VIEWER_COUNT"
    }

    private fun followedSort(context: Context): String = StreamsSortDialog.defaultSort(context)
}
