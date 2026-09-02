package com.github.andreyasadchy.xtra.ui.chat.v2.catalog

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec

enum class ChatAssetProvider { TWITCH, SEVEN_TV, BTTV, FFZ }

enum class ChatEmoteScope { GLOBAL, CHANNEL, PERSONAL }

data class ChatCatalogEmote(
    val name: String,
    val asset: ChatAssetSpec,
    val provider: ChatAssetProvider,
    val animated: Boolean,
    val zeroWidth: Boolean = false,
    val id: String = name,
    val scope: ChatEmoteScope = ChatEmoteScope.GLOBAL,
)

data class ChatCatalogBadge(
    val name: String,
    val asset: ChatAssetSpec,
    val provider: ChatAssetProvider,
    val setId: String = name,
    val versionId: String = "default",
    val info: String? = null,
)

data class ChatCatalogSnapshot(
    val revision: Long,
    val twitch: Map<String, ChatCatalogEmote> = emptyMap(),
    val sevenTv: Map<String, ChatCatalogEmote> = emptyMap(),
    val bttv: Map<String, ChatCatalogEmote> = emptyMap(),
    val ffz: Map<String, ChatCatalogEmote> = emptyMap(),
    val badges: Map<String, ChatCatalogBadge> = emptyMap(),
)

/**
 * The catalog and its local-cache hydration status are one observable state transition.
 * Consumers must not combine independent flows for these values: that could expose a hydrated
 * flag alongside the previous, still-empty catalog for one emission.
 */
data class ChatCatalogState(
    val snapshot: ChatCatalogSnapshot,
    val hydrated: Boolean,
    val refreshFailed: Boolean = false,
)
