package com.github.andreyasadchy.xtra.model.chat

/** The stable identity Twitch uses for a badge, rather than its display title. */
data class ChatIdentityBadgeKey(
    val setId: String,
    val version: String,
)

data class ChatIdentityBadge(
    val key: ChatIdentityBadgeKey,
    val title: String?,
    val description: String?,
    val imageUrl: String,
)

data class ChatIdentityCampaignReward(
    val id: String,
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val earnableUntil: String? = null,
)

data class ChatIdentityCampaignBadge(
    val key: ChatIdentityBadgeKey,
    val title: String?,
    val description: String?,
    val imageUrl: String,
    val owned: Boolean,
)

data class ChatIdentityCampaign(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val earnedBadges: Int,
    val totalBadges: Int,
    val brand: String? = null,
    val game: String? = null,
    val rewards: List<ChatIdentityCampaignReward> = emptyList(),
    val badges: List<ChatIdentityCampaignBadge> = emptyList(),
    val startsAt: String? = null,
    val endsAt: String? = null,
    val rewardCampaignIds: List<String> = emptyList(),
)

data class ChatIdentityData(
    val displayName: String,
    val nameColor: String?,
    val displayBadges: List<ChatIdentityBadge>,
    val availableGlobalBadges: List<ChatIdentityBadge>,
    val selectedGlobalBadge: ChatIdentityBadge?,
    val subscriberBadge: ChatIdentityBadge?,
    val channelBadges: List<ChatIdentityBadge>,
    val selectedChannelBadge: ChatIdentityBadge?,
    val isSubscribed: Boolean,
    val canUseCustomNameColor: Boolean = false,
    val subscriptionMonths: Int? = null,
    val campaigns: List<ChatIdentityCampaign> = emptyList(),
)

data class ChatIdentityColor(
    val name: String,
    val hex: String,
)

/**
 * Twitch's standard chat colors as exposed by the current web chat identity UI.
 * Keep this in one place because the current raw identity query returns the selected
 * color, but not the standard palette/capability list.
 */
val TWITCH_STANDARD_CHAT_COLORS = listOf(
    ChatIdentityColor("Red", "#FF0000"),
    ChatIdentityColor("Blue", "#0000FF"),
    ChatIdentityColor("Green", "#008000"),
    ChatIdentityColor("Firebrick", "#B22222"),
    ChatIdentityColor("Coral", "#FF7F50"),
    ChatIdentityColor("Yellowgreen", "#9ACD32"),
    ChatIdentityColor("Orangered", "#FF4500"),
    ChatIdentityColor("Seagreen", "#2E8B57"),
    ChatIdentityColor("Goldenrod", "#DAA520"),
    ChatIdentityColor("Chocolate", "#D2691E"),
    ChatIdentityColor("Cadetblue", "#5F9EA0"),
    ChatIdentityColor("Dodgerblue", "#1E90FF"),
    ChatIdentityColor("Hotpink", "#FF69B4"),
    ChatIdentityColor("Blueviolet", "#8A2BE2"),
    ChatIdentityColor("Springgreen", "#00FF7F"),
)

data class ChatIdentityState(
    val loading: Boolean = false,
    val loadedChannelId: String? = null,
    val loadedViewerId: String? = null,
    val displayName: String = "",
    val nameColor: String? = null,
    val displayBadges: List<ChatIdentityBadge> = emptyList(),
    val globalBadges: List<ChatIdentityBadge> = emptyList(),
    val selectedGlobalBadge: ChatIdentityBadgeKey? = null,
    val subscriberBadge: ChatIdentityBadge? = null,
    val isSubscribed: Boolean = false,
    val subscriptionMonths: Int? = null,
    val channelBadges: List<ChatIdentityBadge> = emptyList(),
    val useCustomChannelBadge: Boolean = false,
    val selectedChannelBadge: ChatIdentityBadgeKey? = null,
    val campaigns: List<ChatIdentityCampaign> = emptyList(),
    val standardNameColors: List<ChatIdentityColor> = TWITCH_STANDARD_CHAT_COLORS,
    val canUseCustomNameColor: Boolean = false,
    val badgeSelectionAvailable: Boolean = false,
    val channelBadgeOverrideAvailable: Boolean = false,
    val mutationInProgress: Boolean = false,
    val error: String? = null,
)

fun ChatIdentityState.selectedVanityBadge(): ChatIdentityBadge? {
    if (useCustomChannelBadge) {
        selectedChannelBadge?.let { key ->
            channelBadges.firstOrNull { it.key == key }?.let { return it }
        }
    }

    return selectedGlobalBadge?.let { key ->
        globalBadges.firstOrNull { it.key == key }
    }
}

fun ChatIdentityBadge.isSubscriptionSlotBadge(): Boolean =
    key.setId.equals("subscriber", ignoreCase = true) ||
        key.setId.equals("founder", ignoreCase = true)

internal fun ChatIdentityData.toState(channelId: String, viewerId: String? = null): ChatIdentityState {
    val selectableSelectedGlobalBadge = selectedGlobalBadge
        ?.takeUnless(ChatIdentityBadge::isSubscriptionSlotBadge)
    val selectableSelectedChannelBadge = selectedChannelBadge
        ?.takeUnless(ChatIdentityBadge::isSubscriptionSlotBadge)
    val selectableGlobalBadges = (availableGlobalBadges + listOfNotNull(selectedGlobalBadge))
        .filterNot(ChatIdentityBadge::isSubscriptionSlotBadge)
        .distinctBy { it.key }
    return ChatIdentityState(
        loadedChannelId = channelId,
        loadedViewerId = viewerId,
        displayName = displayName,
        nameColor = nameColor,
        displayBadges = displayBadges,
        globalBadges = selectableGlobalBadges,
        selectedGlobalBadge = selectableSelectedGlobalBadge?.key,
        subscriberBadge = subscriberBadge,
        isSubscribed = isSubscribed,
        subscriptionMonths = subscriptionMonths,
        channelBadges = channelBadges
            .filterNot(ChatIdentityBadge::isSubscriptionSlotBadge)
            .distinctBy { it.key },
        useCustomChannelBadge = selectableSelectedChannelBadge != null,
        selectedChannelBadge = selectableSelectedChannelBadge?.key,
        campaigns = campaigns,
        canUseCustomNameColor = canUseCustomNameColor,
        badgeSelectionAvailable = true,
        channelBadgeOverrideAvailable = true,
    )
}

internal fun ChatIdentityState.selectGlobalBadgeOptimistically(
    badge: ChatIdentityBadge?,
): ChatIdentityState = copy(selectedGlobalBadge = badge?.key)

internal fun ChatIdentityState.selectChannelBadgeOptimistically(
    badge: ChatIdentityBadge?,
): ChatIdentityState = copy(
    useCustomChannelBadge = badge != null,
    selectedChannelBadge = badge?.key,
)
