package com.github.andreyasadchy.xtra.model.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class TwitchDrop(
    val id: String,
    val campaignId: String?,
    val campaignName: String?,
    val gameName: String?,
    val name: String?,
    val rewardName: String?,
    val imageUrl: String?,
    // Claim IDs are session credentials returned by Twitch. Never persist them.
    @Transient
    val dropInstanceId: String? = null,
    val currentMinutesWatched: Int,
    val requiredMinutesWatched: Int,
    val isClaimed: Boolean,
    val benefits: List<TwitchDropBenefit> = emptyList(),
    val campaignStartTime: String? = null,
    val campaignEndTime: String? = null,
) {
    val progressPercent: Int
        get() = if (requiredMinutesWatched <= 0) {
            0
        } else {
            ((currentMinutesWatched * 100) / requiredMinutesWatched)
                .coerceIn(0, 100)
        }

    val isClaimable: Boolean
        get() = !isClaimed &&
            !dropInstanceId.isNullOrBlank() &&
            requiredMinutesWatched > 0 &&
            currentMinutesWatched >= requiredMinutesWatched
}

@Serializable
data class TwitchDropBenefit(
    val name: String?,
    val imageUrl: String?,
)

@Serializable
data class TwitchDropCampaign(
    val id: String,
    val name: String?,
    val gameName: String?,
    val imageUrl: String?,
    val startTime: String?,
    val endTime: String?,
    val isUpcoming: Boolean,
    val drops: List<TwitchDropCatalogItem>,
)

@Serializable
data class TwitchDropCatalogItem(
    val id: String,
    val name: String?,
    val requiredMinutesWatched: Int,
    val benefits: List<TwitchDropBenefit>,
)
