package com.github.andreyasadchy.xtra.ui.chat.v2.catalog

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatReward

enum class ChatAssetProvider { TWITCH, SEVEN_TV, BTTV, FFZ }

enum class ChatEmoteScope { GLOBAL, CHANNEL, PERSONAL, LEGACY_COMBINED }

/**
 * Keeps provider scopes independent. The effective map is only a lookup projection;
 * it must never be used as the provider's persisted state because aliases can collide.
 */
data class ScopedEmoteCatalog(
    val global: Map<String, ChatCatalogEmote> = emptyMap(),
    val channel: Map<String, ChatCatalogEmote> = emptyMap(),
    /** Personal emotes keyed by the 7TV set owned by the message sender. */
    val personal: Map<String, Map<String, ChatCatalogEmote>> = emptyMap(),
    /** Live 7TV set updates whose ownership is not known yet. Never used for chat lookup. */
    val pending: Map<String, Map<String, ChatCatalogEmote>> = emptyMap(),
    /** Entries from the pre-v2 combined cache, whose original scope is unknown. */
    val legacyCombined: Map<String, ChatCatalogEmote> = emptyMap(),
) {
    private val personalProjection: Map<String, ChatCatalogEmote> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildMap {
            personal.values.forEach { putAll(it) }
        }
    }
    private val effectiveProjection: Map<String, ChatCatalogEmote> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        legacyCombined + global + personalProjection + channel
    }

    /** Channel wins over personal, which wins over global, matching legacy precedence. */
    val effective: Map<String, ChatCatalogEmote>
        get() = effectiveProjection

    operator fun get(name: String): ChatCatalogEmote? = channel[name] ?: global[name] ?: legacyCombined[name]

    fun lookup(name: String, personalSetId: String? = null): ChatCatalogEmote? =
        channel[name] ?: personalSetId?.takeIf { it.isNotBlank() }?.let { personal[it]?.get(name) }
            ?: global[name] ?: legacyCombined[name]

    operator fun contains(name: String): Boolean = get(name) != null

    val values: Collection<ChatCatalogEmote>
        get() = effectiveValues()

    /** Builds the merged projection once for consumers such as the picker. */
    fun effectiveValues(): Collection<ChatCatalogEmote> =
        (legacyCombined + global + personalProjection + channel).values

    fun isEmpty(): Boolean =
        global.isEmpty() && channel.isEmpty() && personal.isEmpty() && pending.isEmpty() && legacyCombined.isEmpty()

    companion object {
        fun fromEffective(emotes: Map<String, ChatCatalogEmote>): ScopedEmoteCatalog {
            val scopes = emotes.entries.groupBy { it.value.scope }
            return ScopedEmoteCatalog(
                global = scopes[ChatEmoteScope.GLOBAL].orEmpty().associate { it.toPair() },
                channel = scopes[ChatEmoteScope.CHANNEL].orEmpty().associate { it.toPair() },
                // The old value-only form has no sender/set association. Keep it available to
                // pickers under an empty synthetic set, but never resolve it for chat messages.
                personal = scopes[ChatEmoteScope.PERSONAL].orEmpty().associate { it.toPair() }
                    .takeIf { it.isNotEmpty() }?.let { mapOf("" to it) }.orEmpty(),
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

data class ChatCatalogCheermote(
    val asset: ChatAssetSpec,
    val color: Int?,
    val animated: Boolean = false,
)

data class ChatNamePaint(
    val colors: List<Int> = emptyList(),
    val imageUrl: String? = null,
    val colorPositions: List<Float> = emptyList(),
    val type: String? = null,
    val angle: Int? = null,
    val repeat: Boolean = false,
    val shadows: List<ChatNamePaintShadow> = emptyList(),
)

data class ChatNamePaintShadow(
    val xOffset: Float,
    val yOffset: Float,
    val radius: Float,
    val color: Int,
)

data class ChatUserDecoration(
    val paintId: String? = null,
    val badgeId: String? = null,
    val personalEmoteSetId: String? = null,
)

data class ChatDecorationSnapshot(
    val users: Map<String, ChatUserDecoration> = emptyMap(),
    val paints: Map<String, ChatNamePaint> = emptyMap(),
    val badges: Map<String, ChatCatalogBadge> = emptyMap(),
)

sealed interface ChatDecorationUpdate {
    data class EmoteSet(
        val setId: String,
        val added: Map<String, ChatCatalogEmote> = emptyMap(),
        val removedNames: Set<String> = emptySet(),
    ) : ChatDecorationUpdate
    data class Paint(val id: String, val paint: ChatNamePaint) : ChatDecorationUpdate
    data class Badge(val id: String, val badge: ChatCatalogBadge) : ChatDecorationUpdate
    data class User(
        val userId: String,
        val paintId: String? = null,
        val badgeId: String? = null,
        val personalEmoteSetId: String? = null,
    ) : ChatDecorationUpdate
}

data class ChatCatalogSnapshot(
    val revision: Long,
    val twitch: Map<String, ChatCatalogEmote> = emptyMap(),
    val sevenTv: ScopedEmoteCatalog = ScopedEmoteCatalog(),
    /** The actual 7TV channel set ID, needed to route live set mutations safely. */
    val sevenTvChannelSetId: String? = null,
    val bttv: ScopedEmoteCatalog = ScopedEmoteCatalog(),
    val ffz: ScopedEmoteCatalog = ScopedEmoteCatalog(),
    val badges: Map<String, ChatCatalogBadge> = emptyMap(),
    val cheermotes: Map<String, ChatCatalogCheermote> = emptyMap(),
    val userDecorations: Map<String, ChatUserDecoration> = emptyMap(),
    val namePaints: Map<String, ChatNamePaint> = emptyMap(),
    val sevenTvBadges: Map<String, ChatCatalogBadge> = emptyMap(),
    /** Runtime channel-point metadata; intentionally not persisted with emote catalogs. */
    val channelPointRewards: Map<String, ChatReward> = emptyMap(),
    /** Built-in rewards keyed by upper-cased GQL type (e.g. SEND_HIGHLIGHTED_MESSAGE). */
    val automaticChannelPointRewards: Map<String, ChatReward> = emptyMap(),
    /** Changes when runtime reward metadata changes without a provider catalog refresh. */
    val channelPointRewardsRevision: Int = 0,
)

/**
 * The catalog and its local-cache hydration status are one observable state transition.
 * Consumers must not combine independent flows for these values: that could expose a hydrated
 * flag alongside the previous, still-empty catalog for one emission.
 */
data class ChatCatalogState(
    val snapshot: ChatCatalogSnapshot,
    val hydrated: Boolean,
    val badgesSettled: Boolean = false,
    /** True after the first aggregate structural-catalog attempt returns. */
    val structuralCatalogSettled: Boolean = false,
    val refreshFailed: Boolean = false,
    val thirdPartyRefreshFailed: Boolean = false,
)

internal fun ChatCatalogState.isReadyForChatPublication(showBadges: Boolean): Boolean =
    hydrated && structuralCatalogSettled && (!showBadges || badgesSettled)
