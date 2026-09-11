package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmoteKey
import com.github.andreyasadchy.xtra.model.chat.favoriteKey
import com.github.andreyasadchy.xtra.model.chat.key

internal sealed interface FavoritePickerItem {
    val key: FavoriteEmoteKey

    data class EmoteItem(
        val emote: Emote,
    ) : FavoritePickerItem {
        override val key: FavoriteEmoteKey = emote.favoriteKey()!!
    }

    data class EmojiItem(
        val emoji: EmojiPickerItem,
    ) : FavoritePickerItem {
        override val key: FavoriteEmoteKey = EmojiFavoritesCatalog.key(emoji)
    }
}

internal fun FavoritePickerItem.hasSameFavoritePickerBinding(other: FavoritePickerItem): Boolean = when {
    this is FavoritePickerItem.EmoteItem && other is FavoritePickerItem.EmoteItem ->
        key == other.key && emote.hasSamePickerBinding(other.emote)
    this is FavoritePickerItem.EmojiItem && other is FavoritePickerItem.EmojiItem ->
        key == other.key && emoji == other.emoji
    else -> false
}

internal fun List<FavoritePickerItem>.hasSameFavoritePickerBinding(
    other: List<FavoritePickerItem>,
): Boolean = size == other.size && indices.all { this[it].hasSameFavoritePickerBinding(other[it]) }

internal fun favoritePickerItems(
    favorites: List<FavoriteEmote>,
    emotes: List<Emote>,
    emojis: List<EmojiPickerItem>,
): List<FavoritePickerItem> {
    val emotesByKey = emotes.mapNotNull { emote ->
        emote.favoriteKey()?.let { key -> key to emote }
    }.toMap()
    val emojisByKey = emojis.associateBy(EmojiFavoritesCatalog::key)
    return favorites.mapNotNull { favorite ->
        val key = favorite.key() ?: return@mapNotNull null
        emotesByKey[key]?.let(FavoritePickerItem::EmoteItem)
            ?: emojisByKey[key]?.let(FavoritePickerItem::EmojiItem)
    }
}
