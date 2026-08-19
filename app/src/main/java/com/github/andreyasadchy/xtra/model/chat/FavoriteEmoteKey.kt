package com.github.andreyasadchy.xtra.model.chat

enum class EmoteProvider {
    TWITCH,
    SEVENTV,
    BTTV,
    FFZ,
}

data class FavoriteEmoteKey(
    val provider: EmoteProvider,
    val emoteId: String,
)

fun Emote.favoriteKey(): FavoriteEmoteKey? {
    val normalizedId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val provider = when (source) {
        null -> EmoteProvider.TWITCH
        Emote.PERSONAL_STV, Emote.CHANNEL_STV, Emote.GLOBAL_STV -> EmoteProvider.SEVENTV
        Emote.CHANNEL_BTTV, Emote.GLOBAL_BTTV -> EmoteProvider.BTTV
        Emote.CHANNEL_FFZ, Emote.GLOBAL_FFZ -> EmoteProvider.FFZ
        else -> return null
    }
    return FavoriteEmoteKey(provider, normalizedId)
}

fun FavoriteEmote.key(): FavoriteEmoteKey? {
    val normalizedId = emoteId.trim().takeIf { it.isNotEmpty() } ?: return null
    val parsedProvider = runCatching { EmoteProvider.valueOf(provider) }.getOrNull() ?: return null
    return FavoriteEmoteKey(parsedProvider, normalizedId)
}

/**
 * Keeps the live emote instance, including its current name and URLs, as the
 * source of truth for Favorites.
 */
object FavoriteEmoteCatalog {

    fun deduplicate(emotes: List<Emote>): List<Emote> {
        val result = mutableListOf<Emote>()
        val indexesByKey = mutableMapOf<FavoriteEmoteKey, Int>()
        emotes.forEach { emote ->
            val key = emote.favoriteKey()
            if (key == null) {
                result += emote
            } else {
                val existingIndex = indexesByKey[key]
                if (existingIndex == null) {
                    indexesByKey[key] = result.size
                    result += emote
                } else if (scopePriority(emote) < scopePriority(result[existingIndex])) {
                    result[existingIndex] = emote
                }
            }
        }
        return result
    }

    fun availableFavorites(
        favorites: List<FavoriteEmote>,
        emotes: List<Emote>,
    ): List<Emote> {
        val availableByKey = deduplicate(emotes).mapNotNull { emote ->
            emote.favoriteKey()?.let { it to emote }
        }.toMap()
        return favorites.mapNotNull { favorite ->
            favorite.key()?.let(availableByKey::get)
        }
    }

    /**
     * Reorders the currently available favorites while keeping unavailable
     * favorites in their existing global slots.
     */
    fun reorderAvailableFavorites(
        currentOrder: List<FavoriteEmoteKey>,
        availableOrder: List<FavoriteEmoteKey>,
    ): List<FavoriteEmoteKey> {
        val currentKeys = currentOrder.toSet()
        val reorderedAvailable = availableOrder
            .filter(currentKeys::contains)
            .distinct()
        if (reorderedAvailable.isEmpty()) return currentOrder

        val reorderedKeys = reorderedAvailable.toSet()
        var nextAvailableIndex = 0
        return currentOrder.map { key ->
            if (key in reorderedKeys) {
                reorderedAvailable[nextAvailableIndex++]
            } else {
                key
            }
        }
    }

    fun removeMatchingScope(
        emotes: MutableList<Emote>,
        removed: List<Emote>,
        source: Int,
    ) {
        val removedKeys = removed.mapNotNull { it.favoriteKey() }.toSet()
        val removedNames = removed.map { it.name }.toSet()
        emotes.removeAll { emote ->
            val key = emote.favoriteKey()
            emote.source == source &&
                    ((key != null && key in removedKeys) ||
                            (key == null && emote.name in removedNames))
        }
    }

    private fun scopePriority(emote: Emote): Int {
        return when (emote.source) {
            Emote.CHANNEL_STV, Emote.CHANNEL_BTTV, Emote.CHANNEL_FFZ -> 0
            Emote.PERSONAL_STV -> 1
            Emote.GLOBAL_STV, Emote.GLOBAL_BTTV, Emote.GLOBAL_FFZ -> 2
            else -> 0
        }
    }
}
