package com.github.andreyasadchy.xtra.ui.chat.v2.catalog

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec

enum class ChatAssetProvider { TWITCH, SEVEN_TV, BTTV, FFZ }

enum class ChatEmoteScope { GLOBAL, CHANNEL, PERSONAL, LEGACY_COMBINED }

/**
 * Keeps provider scopes independent. The effective map is only a lookup projection;
 * it must never be used as the provider's persisted state because aliases can collide.
 */
data class ScopedEmoteCatalog(
    val global: Map<String, ChatCatalogEmote> = emptyMap(),
    val channel: Map<String, ChatCatalogEmote> = emptyMap(),
    val personal: Map<String, ChatCatalogEmote> = emptyMap(),
    /** Entries from the pre-v2 combined cache, whose original scope is unknown. */
    val legacyCombined: Map<String, ChatCatalogEmote> = emptyMap(),
) {
    private val effectiveProjection: Map<String, ChatCatalogEmote> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        legacyCombined + global + personal + channel
    }

    /** Channel wins over personal, which wins over global, matching legacy precedence. */
    val effective: Map<String, ChatCatalogEmote>
        get() = effectiveProjection

    operator fun get(name: String): ChatCatalogEmote? =
        channel[name] ?: personal[name] ?: global[name] ?: legacyCombined[name]

    operator fun contains(name: String): Boolean = get(name) != null

    val values: Collection<ChatCatalogEmote>
        get() = effectiveValues()

    /** Builds the merged projection once for consumers such as the picker. */
    fun effectiveValues(): Collection<ChatCatalogEmote> =
        (legacyCombined + global + personal + channel).values

    fun isEmpty(): Boolean =
        global.isEmpty() && channel.isEmpty() && personal.isEmpty() && legacyCombined.isEmpty()

    companion object {
        fun fromEffective(emotes: Map<String, ChatCatalogEmote>): ScopedEmoteCatalog {
            val scopes = emotes.entries.groupBy { it.value.scope }
            return ScopedEmoteCatalog(
                global = scopes[ChatEmoteScope.GLOBAL].orEmpty().associate { it.toPair() },
                channel = scopes[ChatEmoteScope.CHANNEL].orEmpty().associate { it.toPair() },
                personal = scopes[ChatEmoteScope.PERSONAL].orEmpty().associate { it.toPair() },
                legacyCombined = scopes[ChatEmoteScope.LEGACY_COMBINED].orEmpty().associate { it.toPair() },
            )
        }
    }
}

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
    val sevenTv: ScopedEmoteCatalog = ScopedEmoteCatalog(),
    val bttv: ScopedEmoteCatalog = ScopedEmoteCatalog(),
    val ffz: ScopedEmoteCatalog = ScopedEmoteCatalog(),
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
    val thirdPartyRefreshFailed: Boolean = false,
)
