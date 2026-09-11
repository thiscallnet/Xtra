package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.EmoteProvider
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiFavoritesCatalogTest {
    @Test
    fun `favorite emojis preserve persisted order and ignore emote favorites`() {
        val smile = EmojiPickerCatalog.findByAlias(":smile:")!!
        val heart = EmojiPickerCatalog.findByAlias(":heart:")!!
        val favorites = listOf(
            FavoriteEmote(EmoteProvider.TWEMOJI.name, heart.name, favoritedAt = 2L),
            FavoriteEmote(EmoteProvider.TWITCH.name, "twitch-id", favoritedAt = 1L),
            FavoriteEmote(EmoteProvider.TWEMOJI.name, smile.name, favoritedAt = 3L),
        )

        assertEquals(
            listOf(heart, smile),
            EmojiFavoritesCatalog.availableFavorites(favorites, EmojiPickerCatalog.items),
        )
        assertEquals(setOf(heart.name, smile.name), EmojiFavoritesCatalog.favoriteValues(favorites))
    }

    @Test
    fun `emoji favorite keys use a separate provider namespace`() {
        val smile = EmojiPickerCatalog.findByAlias(":smile:")!!

        assertEquals(EmoteProvider.TWEMOJI, EmojiFavoritesCatalog.key(smile).provider)
        assertEquals(smile.name, EmojiFavoritesCatalog.key(smile).emoteId)
    }
}
