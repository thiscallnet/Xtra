package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.User

internal data class ChannelFallbackResolution(
    val snapshot: ChannelPageCacheSnapshot?,
    val shouldPersist: Boolean,
    val streamValidated: Boolean = false,
    val followerCountValidated: Boolean = false,
)

/**
 * Keeps a failed stream request distinct from a successful offline response.
 * A channel snapshot cannot encode that distinction, so failed stream loads
 * may only be persisted when an existing snapshot is available to preserve.
 */
internal fun resolveChannelFallback(
    cached: ChannelPageCacheSnapshot?,
    streamResult: Result<Stream?>,
    userResult: Result<User?>,
): ChannelFallbackResolution {
    val freshStream = streamResult.getOrNull()
    val stream = streamResult.getOrElse { cached?.stream }
    val user = userResult.getOrNull()
        ?: cached?.user
        ?: freshStream?.let {
            User(
                id = it.channelId,
                login = it.channelLogin,
                name = it.channelName,
                profileImageURL = it.channelImageURL,
            )
        }
    val snapshot = user?.let { ChannelPageCacheSnapshot(user = it, stream = stream) }
    val hasFreshUser = userResult.getOrNull() != null
    val shouldPersist = snapshot != null && when {
        freshStream != null -> true
        streamResult.isSuccess -> hasFreshUser || cached != null
        else -> hasFreshUser && cached != null
    }
    return ChannelFallbackResolution(
        snapshot = snapshot,
        shouldPersist = shouldPersist,
        streamValidated = streamResult.isSuccess,
        // Helix user fallback does not include the channel follower count.
        followerCountValidated = false,
    )
}

internal fun mergeGameFallback(cached: Game?, helixGame: Game): Game {
    if (cached == null) return helixGame
    return Game(
        id = helixGame.id ?: cached.id,
        slug = cached.slug ?: helixGame.slug,
        name = helixGame.name ?: cached.name,
        boxArtURL = helixGame.boxArtURL ?: cached.boxArtURL,
        viewerCount = cached.viewerCount,
        broadcasterCount = cached.broadcasterCount,
        followerCount = cached.followerCount,
        tags = cached.tags,
        vodPosition = cached.vodPosition,
        vodDuration = cached.vodDuration,
        accountFollow = cached.accountFollow,
        localFollow = cached.localFollow,
    )
}
