package com.github.andreyasadchy.xtra.ui.chat.v2.domain

/** The channel-point reward metadata needed to render a redemption in chat. */
data class ChatReward(
    val title: String,
    val cost: Int? = null,
    val imageUrl: String? = null,
)

/** GQL automatic reward type for the Highlight My Message redemption. */
const val HIGHLIGHTED_MESSAGE_REWARD_TYPE = "SEND_HIGHLIGHTED_MESSAGE"

fun ChatMessage.requiresInitialRewardMetadata(): Boolean =
    rewardId != null || twitchType == TwitchChatMessageType.Highlighted

/**
 * Runtime channel-point metadata. Custom rewards are keyed by reward ID while
 * automatic (built-in) rewards are keyed by their upper-cased GQL type, since
 * real Highlight My Message events carry no custom reward ID on either IRC
 * (`msg-id=highlighted-message` without `custom-reward-id`) or EventSub
 * (`message_type=channel_points_highlighted` without
 * `channel_points_custom_reward_id`).
 */
data class ChatRewardCatalog(
    val byId: Map<String, ChatReward> = emptyMap(),
    val automaticByType: Map<String, ChatReward> = emptyMap(),
) {
    fun rewardFor(message: ChatMessage): ChatReward? {
        message.rewardId?.let { id ->
            byId[id]?.let { return it }
        }
        if (message.twitchType == TwitchChatMessageType.Highlighted) {
            automaticByType[HIGHLIGHTED_MESSAGE_REWARD_TYPE]?.let { return it }
        }
        // Inline EventSub/PubSub metadata covers redemptions whose reward is
        // not (or not yet) in the catalog. Only highlights carry reward
        // meaning without a reward ID.
        if (message.rewardId == null && message.twitchType != TwitchChatMessageType.Highlighted) return null
        return message.rewardTitle?.let { title ->
            ChatReward(title, message.rewardCost, message.rewardImageUrl)
        }
    }
}
