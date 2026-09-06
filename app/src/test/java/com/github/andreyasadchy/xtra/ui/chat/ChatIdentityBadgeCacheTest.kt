package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadge
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadgeKey
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatIdentityBadgeCacheTest {
    private val subscriber = badge("subscriber")
    private val vanity = badge("vanity")

    @After
    fun clearCache() {
        ChatIdentityBadgeCache.clear()
    }

    @Test
    fun `server badge is cached per viewer and channel and empty response clears it`() {
        val cache = ChatIdentityBadgeCache

        cache.updateFromServer("viewer", "channel", subscriber)
        assertEquals(subscriber, cache.get("channel", "viewer"))
        assertNull(cache.get("other-channel", "viewer"))
        assertNull(cache.get("channel", "other-viewer"))

        cache.updateFromServer("viewer", "channel", null)
        assertNull(cache.get("channel", "viewer"))
    }

    @Test
    fun `server trigger badge wins over cached badge and cached badge wins while loading`() {
        val cached = badge("cached")
        val fresh = badge("fresh")
        val loading = ChatIdentityState(loading = true)
        val loaded = ChatIdentityState(displayName = "viewer")

        assertEquals(cached, resolveChatIdentityTriggerBadge(loading, cached))
        assertEquals(
            fresh,
            resolveChatIdentityTriggerBadge(loaded.copy(displayBadges = listOf(fresh)), cached),
        )
    }

    @Test
    fun `selected vanity is cached when the server display list is empty`() {
        val state = ChatIdentityState(
            displayName = "viewer",
            globalBadges = listOf(vanity),
            selectedGlobalBadge = vanity.key,
        )

        ChatIdentityBadgeCache.updateFromServer(
            viewerId = "viewer",
            channelId = "channel",
            triggerBadge = state.resolvedServerChatIdentityTriggerBadge(),
        )

        assertEquals(vanity, ChatIdentityBadgeCache.get("channel", "viewer"))
    }

    private fun badge(setId: String) = ChatIdentityBadge(
        key = ChatIdentityBadgeKey(setId, "1"),
        title = setId,
        description = null,
        imageUrl = "https://example.com/$setId.png",
    )
}
