package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.EmoteProvider
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritePickerItemTest {
    @Test
    fun `binding comparison notices refreshed emote assets`() {
        val oldItem = FavoritePickerItem.EmoteItem(
            Emote(name = "Kappa", id = "twitch-a", url1x = "https://example.test/old.png"),
        )
        val refreshedItem = FavoritePickerItem.EmoteItem(
            Emote(name = "Kappa", id = "twitch-a", url1x = "https://example.test/new.png"),
        )

        assertFalse(listOf(oldItem).hasSameFavoritePickerBinding(listOf(refreshedItem)))
    }

    @Test
    fun `binding comparison keeps same names from different providers distinct`() {
        val sevenTv = FavoritePickerItem.EmoteItem(
            Emote(name = "Party", id = "seven-tv", source = Emote.CHANNEL_STV),
        )
        val bttv = FavoritePickerItem.EmoteItem(
            Emote(name = "Party", id = "bttv", source = Emote.CHANNEL_BTTV),
        )

        assertFalse(listOf(sevenTv).hasSameFavoritePickerBinding(listOf(bttv)))
    }

    @Test
    fun `mixed favorites follow the persisted order`() {
        val heart = EmojiPickerCatalog.findByAlias(":heart:")!!
        val firstEmote = Emote(name = "Kappa", id = "twitch-a")
        val secondEmote = Emote(name = "PogChamp", id = "twitch-b")
        val favorites = listOf(
            FavoriteEmote(EmoteProvider.TWITCH.name, "twitch-a", favoritedAt = 1L),
            FavoriteEmote(EmoteProvider.TWEMOJI.name, heart.name, favoritedAt = 2L),
            FavoriteEmote(EmoteProvider.TWITCH.name, "twitch-b", favoritedAt = 3L),
        )

        assertEquals(
            listOf(
                FavoritePickerItem.EmoteItem(firstEmote),
                FavoritePickerItem.EmojiItem(heart),
                FavoritePickerItem.EmoteItem(secondEmote),
            ),
            favoritePickerItems(favorites, listOf(firstEmote, secondEmote), EmojiPickerCatalog.items),
        )
    }

    @Test
    fun `unavailable favorites are omitted without changing available order`() {
        val heart = EmojiPickerCatalog.findByAlias(":heart:")!!
        val emote = Emote(name = "Kappa", id = "twitch-a")
        val favorites = listOf(
            FavoriteEmote(EmoteProvider.TWITCH.name, "missing", favoritedAt = 1L),
            FavoriteEmote(EmoteProvider.TWEMOJI.name, heart.name, favoritedAt = 2L),
            FavoriteEmote(EmoteProvider.TWITCH.name, "twitch-a", favoritedAt = 3L),
        )

        assertEquals(
            listOf(FavoritePickerItem.EmojiItem(heart), FavoritePickerItem.EmoteItem(emote)),
            favoritePickerItems(favorites, listOf(emote), EmojiPickerCatalog.items),
        )
    }
}
