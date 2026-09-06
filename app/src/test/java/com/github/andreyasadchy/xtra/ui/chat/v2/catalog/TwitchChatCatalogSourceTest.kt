package com.github.andreyasadchy.xtra.ui.chat.v2.catalog

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.recommendations.EmoteUsageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TwitchChatCatalogSourceTest {
    @Test
    fun `subscriber emotes use global usage while follower emotes use channel usage`() {
        assertEquals(ChatEmoteScope.GLOBAL, twitchEmoteScope("subscriptions"))
        assertEquals(ChatEmoteScope.CHANNEL, twitchEmoteScope("follower"))
        assertEquals(ChatEmoteScope.CHANNEL, twitchEmoteScope("channelpoints"))
        assertEquals(ChatEmoteScope.CHANNEL, twitchEmoteScope("CHANNEL_POINTS"))
        assertEquals(ChatEmoteScope.GLOBAL, twitchEmoteScope("unknown"))

        val subscriber = emote("Subscriber", "subscriber", twitchEmoteScope("subscriptions"))
        val follower = emote("Follower", "follower", twitchEmoteScope("follower"))
        assertEquals(
            EmoteUsageKeys.forEmote(subscriber, "channel-a", "viewer-a"),
            EmoteUsageKeys.forEmote(subscriber, "channel-b", "viewer-a"),
        )
        assertNotEquals(
            EmoteUsageKeys.forEmote(follower, "channel-a", "viewer-a"),
            EmoteUsageKeys.forEmote(follower, "channel-b", "viewer-a"),
        )
    }

    private fun emote(name: String, id: String, scope: ChatEmoteScope) = ChatCatalogEmote(
        name = name,
        id = id,
        asset = ChatAssetSpec(ChatAssetKey(id), 56, 56, 28),
        provider = ChatAssetProvider.TWITCH,
        animated = false,
        scope = scope,
    )
}
