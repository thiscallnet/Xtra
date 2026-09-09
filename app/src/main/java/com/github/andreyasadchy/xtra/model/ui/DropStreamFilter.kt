package com.github.andreyasadchy.xtra.model.ui

private const val DROPS_ENABLED_TAG = "DropsEnabled"

/**
 * Search context for the regular live-stream search screen.
 *
 * The game identity narrows the search results, while the campaign/drop IDs are checked against
 * Twitch's channel-specific Drops response before a result is shown.
 */
data class DropStreamFilter(
    val campaignId: String,
    val campaignName: String,
    val gameId: String?,
    val gameName: String,
    val dropIds: Set<String>,
)

internal fun DropStreamFilter.matchesAvailableDropIds(availableIds: Set<String>): Boolean =
    campaignId in availableIds || dropIds.any(availableIds::contains)

internal fun DropStreamFilter.matchesDropsEnabledTag(tags: List<String>?): Boolean =
    tags.orEmpty().any { it.equals(DROPS_ENABLED_TAG, ignoreCase = true) }

internal fun DropStreamFilter.matchesChannelCampaigns(
    campaigns: List<TwitchChannelDropCampaign>,
): Boolean = campaigns.any { campaign ->
    campaign.id == campaignId || campaign.drops.any { drop -> drop.id in dropIds }
}

internal fun DropStreamFilter.matchesGame(
    streamGameId: String?,
    streamGameName: String?,
): Boolean = when {
    !gameId.isNullOrBlank() && !streamGameId.isNullOrBlank() -> gameId == streamGameId
    else -> streamGameName?.equals(gameName, ignoreCase = true) == true
}

internal fun matchesDropCampaign(
    availableIds: Set<String>,
    campaignId: String,
    dropIds: Set<String>,
): Boolean = campaignId in availableIds || dropIds.any(availableIds::contains)
