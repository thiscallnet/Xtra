package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatDecorationSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatRewardCatalog

/** Collection-owned projection of immutable metadata. Message arrivals do not copy catalog maps. */
internal class ChatPresentationCatalog {
    private var source: ChatCatalogSnapshot? = null
    private var rewards: ChatRewardCatalog? = null
    private var decorations: ChatDecorationSnapshot? = null
    private var resolved: ChatCatalogSnapshot? = null

    fun resolve(
        catalog: ChatCatalogSnapshot,
        rewardCatalog: ChatRewardCatalog,
        decorationCatalog: ChatDecorationSnapshot,
    ): ChatCatalogSnapshot {
        val previous = resolved
        if (previous != null && source === catalog && rewards === rewardCatalog && decorations === decorationCatalog) {
            return previous
        }
        val next = catalog.copy(
            channelPointRewards = rewardCatalog.byId,
            automaticChannelPointRewards = rewardCatalog.automaticByType,
            channelPointRewardsRevision = if (rewards === rewardCatalog && previous != null) {
                previous.channelPointRewardsRevision
            } else rewardCatalog.hashCode(),
            // Live v2 data wins over the legacy fallback. Unchanged maps remain shared
            // even when an unrelated provider publishes a new catalog revision.
            userDecorations = if (previous != null && source?.userDecorations === catalog.userDecorations &&
                decorations?.users === decorationCatalog.users
            ) previous.userDecorations else merge(decorationCatalog.users, catalog.userDecorations),
            namePaints = if (previous != null && source?.namePaints === catalog.namePaints &&
                decorations?.paints === decorationCatalog.paints
            ) previous.namePaints else merge(decorationCatalog.paints, catalog.namePaints),
            sevenTvBadges = if (previous != null && source?.sevenTvBadges === catalog.sevenTvBadges &&
                decorations?.badges === decorationCatalog.badges
            ) previous.sevenTvBadges else merge(decorationCatalog.badges, catalog.sevenTvBadges),
        )
        source = catalog
        rewards = rewardCatalog
        decorations = decorationCatalog
        resolved = next
        return next
    }

    private fun <K, V> merge(fallback: Map<K, V>, current: Map<K, V>): Map<K, V> = when {
        fallback.isEmpty() -> current
        current.isEmpty() -> fallback
        fallback === current -> current
        else -> fallback + current
    }
}
