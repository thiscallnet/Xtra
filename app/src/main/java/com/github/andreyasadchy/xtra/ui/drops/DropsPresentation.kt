package com.github.andreyasadchy.xtra.ui.drops

import com.github.andreyasadchy.xtra.model.ui.matchesDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import kotlin.time.Clock
import kotlin.time.Instant

/** A stream search is useful only while the campaign is active and has a game name. */
internal fun campaignCanFindLiveStreams(
    campaign: TwitchDropCampaign,
    now: Instant = Clock.System.now(),
): Boolean =
    !campaign.isUpcoming &&
        !campaign.gameName.isNullOrBlank() &&
        (campaign.endTime?.let { end -> Instant.parseOrNull(end)?.let { now < it } } ?: true)

internal fun dropCanFindLiveStreams(
    drop: TwitchDrop,
    now: Instant = Clock.System.now(),
): Boolean {
    if (drop.campaignId.isNullOrBlank() || drop.gameName.isNullOrBlank()) return false
    val endTime = drop.campaignEndTime?.let(Instant::parseOrNull) ?: return true
    return now < endTime
}

internal fun restoreDropsTab(savedTab: Int?): Int =
    savedTab?.coerceIn(0, 2) ?: 0

internal fun channelOffersCampaign(
    availableIds: Set<String>,
    campaignId: String,
    dropIds: Set<String>,
): Boolean = matchesDropCampaign(availableIds, campaignId, dropIds)
