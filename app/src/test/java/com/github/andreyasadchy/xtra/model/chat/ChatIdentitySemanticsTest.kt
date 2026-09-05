package com.github.andreyasadchy.xtra.model.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatIdentitySemanticsTest {
    private val subscriber = badge("subscriber", "12")
    private val founder = badge("founder", "0")
    private val moderator = badge("moderator", "1")
    private val broadcaster = badge("broadcaster", "1")
    private val globalVanity = badge("global-vanity", "pikachu")
    private val channelVanity = badge("channel-vanity", "1")

    @Test
    fun `subscriber and global vanity keep server order for composer and preview`() {
        val state = state(
            displayBadges = listOf(subscriber, globalVanity),
            globalBadges = listOf(globalVanity),
            selectedGlobalBadge = globalVanity.key,
        )

        assertEquals(subscriber, state.displayBadges.firstOrNull())
        assertEquals(listOf(subscriber, globalVanity), state.displayBadges)
        assertEquals(globalVanity, state.selectedVanityBadge())
    }

    @Test
    fun `custom channel vanity does not replace subscription slot`() {
        val state = state(
            displayBadges = listOf(subscriber, channelVanity),
            channelBadges = listOf(channelVanity),
            useCustomChannelBadge = true,
            selectedChannelBadge = channelVanity.key,
        )

        assertEquals(subscriber, state.displayBadges.first())
        assertEquals(listOf(subscriber, channelVanity), state.displayBadges)
        assertEquals(channelVanity, state.selectedVanityBadge())
    }

    @Test
    fun `turning custom override off selects global vanity only`() {
        val state = state(
            displayBadges = listOf(subscriber, globalVanity),
            globalBadges = listOf(globalVanity),
            selectedGlobalBadge = globalVanity.key,
            channelBadges = listOf(channelVanity),
            useCustomChannelBadge = false,
            selectedChannelBadge = channelVanity.key,
        )

        assertEquals(globalVanity, state.selectedVanityBadge())
        assertEquals(subscriber, state.displayBadges.first())
    }

    @Test
    fun `server display list is the composer source without subscription`() {
        val state = state(displayBadges = listOf(moderator, globalVanity))

        assertEquals(moderator, state.displayBadges.first())
        assertEquals(listOf(moderator, globalVanity), state.displayBadges)
    }

    @Test
    fun `server authority and subscription order is preserved exactly`() {
        val displayBadges = listOf(broadcaster, moderator, subscriber, globalVanity)
        val state = state(displayBadges = displayBadges)

        assertEquals(displayBadges, state.displayBadges)
        assertEquals(broadcaster, state.displayBadges.first())
    }

    @Test
    fun `founder is the subscription slot badge`() {
        assertTrue(founder.isSubscriptionSlotBadge())
        assertFalse(globalVanity.isSubscriptionSlotBadge())

        val data = data(displayBadges = listOf(founder), earnedBadges = emptyList())
        assertEquals(founder, data.subscriberBadge)
    }

    @Test
    fun `earned founder without displayed subscription does not imply active subscription`() {
        val data = data(displayBadges = emptyList(), earnedBadges = listOf(founder))

        assertEquals(founder, data.subscriberBadge)
        assertFalse(data.isSubscribed)
    }

    @Test
    fun `subscription remains visible when no vanity is selected`() {
        val state = state(displayBadges = listOf(subscriber))

        assertEquals(subscriber, state.displayBadges.first())
        assertNull(state.selectedVanityBadge())
    }

    @Test
    fun `server refresh replaces display list while optimistic selection changes only preference`() {
        val before = state(displayBadges = listOf(subscriber, globalVanity))
        val optimistic = before.selectGlobalBadgeOptimistically(channelVanity)
        val refreshed = data(displayBadges = listOf(subscriber, channelVanity))
            .toState(channelId = "channel", viewerId = "viewer")

        assertEquals(channelVanity.key, optimistic.selectedGlobalBadge)
        assertEquals(before.displayBadges, optimistic.displayBadges)
        assertEquals(listOf(subscriber, channelVanity), refreshed.displayBadges)
    }

    @Test
    fun `subscription tenure is optional`() {
        assertEquals(1, data(subscriptionMonths = 1).subscriptionMonths)
        assertEquals(12, data(subscriptionMonths = 12).subscriptionMonths)
        assertNull(data(subscriptionMonths = null).subscriptionMonths)
    }

    private fun state(
        displayBadges: List<ChatIdentityBadge> = emptyList(),
        globalBadges: List<ChatIdentityBadge> = emptyList(),
        selectedGlobalBadge: ChatIdentityBadgeKey? = null,
        channelBadges: List<ChatIdentityBadge> = emptyList(),
        useCustomChannelBadge: Boolean = false,
        selectedChannelBadge: ChatIdentityBadgeKey? = null,
    ) = ChatIdentityState(
        displayBadges = displayBadges,
        globalBadges = globalBadges,
        selectedGlobalBadge = selectedGlobalBadge,
        channelBadges = channelBadges,
        useCustomChannelBadge = useCustomChannelBadge,
        selectedChannelBadge = selectedChannelBadge,
    )

    private fun data(
        displayBadges: List<ChatIdentityBadge> = emptyList(),
        earnedBadges: List<ChatIdentityBadge> = emptyList(),
        subscriptionMonths: Int? = null,
    ) = ChatIdentityData(
        displayName = "viewer",
        nameColor = null,
        displayBadges = displayBadges,
        availableGlobalBadges = emptyList(),
        selectedGlobalBadge = null,
        subscriberBadge = displayBadges.firstOrNull { it.isSubscriptionSlotBadge() }
            ?: earnedBadges.firstOrNull { it.isSubscriptionSlotBadge() },
        channelBadges = earnedBadges.filterNot { it.isSubscriptionSlotBadge() },
        selectedChannelBadge = null,
        isSubscribed = displayBadges.any { it.isSubscriptionSlotBadge() },
        subscriptionMonths = subscriptionMonths,
    )

    private fun badge(setId: String, version: String) = ChatIdentityBadge(
        key = ChatIdentityBadgeKey(setId, version),
        title = setId,
        description = null,
        imageUrl = "https://example.com/$setId-$version.png",
    )
}
