package com.github.andreyasadchy.xtra.ui.drops

import com.github.andreyasadchy.xtra.model.ui.matchesDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign

/** A stream search is useful only while the campaign is active and has a game name. */
internal fun campaignCanFindLiveStreams(campaign: TwitchDropCampaign): Boolean =
    !campaign.isUpcoming &&
        !campaign.gameName.isNullOrBlank()

internal fun restoreDropsTab(savedTab: Int?): Int =
    savedTab?.coerceIn(0, 2) ?: 0

internal fun channelOffersCampaign(
    availableIds: Set<String>,
    campaignId: String,
    dropIds: Set<String>,
): Boolean = matchesDropCampaign(availableIds, campaignId, dropIds)
