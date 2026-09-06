package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadge
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityState
import com.github.andreyasadchy.xtra.model.chat.selectedVanityBadge
import java.util.LinkedHashMap

internal data class ChatIdentityBadgeCacheKey(
    val viewerId: String,
    val channelId: String,
)

/** Keeps only the last server-selected trigger badge; it is never Chat Identity presentation state. */
internal object ChatIdentityBadgeCache {
    private const val maxEntries = 64
    private val entries = object : LinkedHashMap<ChatIdentityBadgeCacheKey, ChatIdentityBadge>(
        maxEntries,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ChatIdentityBadgeCacheKey, ChatIdentityBadge>?,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(channelId: String, viewerId: String): ChatIdentityBadge? =
        entries[ChatIdentityBadgeCacheKey(viewerId, channelId)]

    @Synchronized
    fun updateFromServer(
        viewerId: String,
        channelId: String,
        triggerBadge: ChatIdentityBadge?,
    ) {
        val key = ChatIdentityBadgeCacheKey(viewerId, channelId)
        val badge = triggerBadge?.takeIf { it.imageUrl.isNotBlank() }
        if (badge == null) entries.remove(key) else entries[key] = badge
    }

    @Synchronized
    fun clear() = entries.clear()
}

internal fun ChatIdentityState.resolvedServerChatIdentityTriggerBadge(): ChatIdentityBadge? =
    displayBadges.firstOrNull()
        ?: takeIf { displayName.isNotBlank() }?.selectedVanityBadge()

internal fun resolveChatIdentityTriggerBadge(
    state: ChatIdentityState,
    cachedBadge: ChatIdentityBadge?,
): ChatIdentityBadge? = state.resolvedServerChatIdentityTriggerBadge() ?: cachedBadge
