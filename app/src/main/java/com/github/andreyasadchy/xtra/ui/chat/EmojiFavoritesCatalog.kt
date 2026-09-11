package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.EmoteProvider
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmoteKey
import com.github.andreyasadchy.xtra.model.chat.key

internal object EmojiFavoritesCatalog {

    fun key(emoji: EmojiPickerItem): FavoriteEmoteKey =
        FavoriteEmoteKey(EmoteProvider.TWEMOJI, emoji.name)

    fun favoriteValues(favorites: List<FavoriteEmote>): Set<String> = favorites
        .asSequence()
        .mapNotNull { favorite ->
            favorite.key()
                ?.takeIf { it.provider == EmoteProvider.TWEMOJI }
                ?.emoteId
        }
        .toSet()

    fun availableFavorites(
        favorites: List<FavoriteEmote>,
        emojis: List<EmojiPickerItem>,
    ): List<EmojiPickerItem> {
        val availableByName = emojis.associateBy { it.name }
        return favorites.asSequence()
            .mapNotNull { favorite ->
                favorite.key()
                    ?.takeIf { it.provider == EmoteProvider.TWEMOJI }
                    ?.emoteId
                    ?.let(availableByName::get)
            }
            .toList()
    }
}
