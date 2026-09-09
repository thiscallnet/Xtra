package com.github.andreyasadchy.xtra.model.ui

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

private const val DROPS_ENABLED_TAG = "DropsEnabled"

/**
 * Search context for the regular live-stream search screen.
 *
 * The game identity narrows the search results, while the campaign/drop IDs are checked against
 * Twitch's channel-specific Drops response before a result is shown.
 */
@Parcelize
data class DropStreamFilter(
    val campaignId: String,
    val campaignName: String,
    val gameId: String?,
    val gameName: String,
    val dropIds: Set<String>,
    val dropNames: List<String> = emptyList(),
) : Parcelable {
    val displayName: String
        get() = when {
            dropNames.size == 1 -> dropNames.first()
            dropNames.size > 1 -> "${dropNames.first()} +${dropNames.size - 1}"
            else -> campaignName
        }
}

internal fun DropStreamFilter.matchesAvailableDropIds(availableIds: Set<String>): Boolean =
    if (dropIds.isNotEmpty()) {
        dropIds.any(availableIds::contains)
    } else {
        campaignId in availableIds
    }

internal fun DropStreamFilter.matchesDropsEnabledTag(tags: List<String>?): Boolean =
    tags.orEmpty().any { it.equals(DROPS_ENABLED_TAG, ignoreCase = true) }

internal fun DropStreamFilter.matchesChannelCampaigns(
    campaigns: List<TwitchChannelDropCampaign>,
): Boolean = if (dropIds.isNotEmpty()) {
    campaigns.any { campaign ->
        campaign.drops.any { drop -> drop.id in dropIds }
    }
} else {
    campaigns.any { campaign -> campaign.id == campaignId }
}

internal fun DropStreamFilter.matchesGame(
    streamGameId: String?,
    streamGameName: String?,
): Boolean = when {
    !gameId.isNullOrBlank() && !streamGameId.isNullOrBlank() -> gameId == streamGameId
    else -> streamGameName?.equals(gameName, ignoreCase = true) == true
}

internal fun List<DropStreamFilter>.matchesDropStream(
    streamGameId: String?,
    streamGameName: String?,
    campaigns: List<TwitchChannelDropCampaign>,
): Boolean = any { filter ->
    filter.matchesGame(streamGameId, streamGameName) &&
        filter.matchesChannelCampaigns(campaigns)
}

internal fun matchesDropCampaign(
    availableIds: Set<String>,
    campaignId: String,
    dropIds: Set<String>,
): Boolean = if (dropIds.isNotEmpty()) {
    dropIds.any(availableIds::contains)
} else {
    campaignId in availableIds
}
