package com.github.andreyasadchy.xtra.repository.gamefeed

import android.content.Context
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.repository.HelixRepository
import com.github.andreyasadchy.xtra.repository.datasource.GameFeedPageLoader
import com.github.andreyasadchy.xtra.repository.datasource.TwitchGameFeedPageLoader
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs

object GameFeedSpecs {
    fun top(
        context: Context,
        graphQLRepository: GraphQLRepository,
        helixRepository: HelixRepository,
        tags: Iterable<String>?,
        pageSize: Int = 30,
    ): GameFeedSpec {
        val normalizedTags = tags?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
        val loader: GameFeedPageLoader = TwitchGameFeedPageLoader(
            tags = normalizedTags?.takeIf { it.isNotEmpty() },
            gqlHeaders = { TwitchApiHelper.getGQLHeaders(context) },
            graphQLRepository = graphQLRepository,
            helixHeaders = { TwitchApiHelper.getHelixHeaders(context) },
            helixRepository = helixRepository,
            enableIntegrity = context.prefs().getBoolean(C.ENABLE_INTEGRITY, false),
            networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            pageSize = pageSize,
        )
        return GameFeedSpec(GameFeedKey.top(normalizedTags), loader)
    }
}
