package com.github.andreyasadchy.xtra.model.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FavoriteEmoteKeyTest {

    @Test
    fun providerScopesShareOneSevenTvKey() {
        val expected = FavoriteEmoteKey(EmoteProvider.SEVENTV, "7tv-id")

        assertEquals(expected, emote(Emote.PERSONAL_STV, "7tv-id").favoriteKey())
        assertEquals(expected, emote(Emote.CHANNEL_STV, "7tv-id").favoriteKey())
        assertEquals(expected, emote(Emote.GLOBAL_STV, "7tv-id").favoriteKey())
    }

    @Test
    fun everyThirdPartyScopeMapsToItsProvider() {
        assertEquals(EmoteProvider.BTTV, emote(Emote.CHANNEL_BTTV, "bttv-id").favoriteKey()?.provider)
        assertEquals(EmoteProvider.BTTV, emote(Emote.GLOBAL_BTTV, "bttv-id").favoriteKey()?.provider)
        assertEquals(EmoteProvider.FFZ, emote(Emote.CHANNEL_FFZ, "ffz-id").favoriteKey()?.provider)
        assertEquals(EmoteProvider.FFZ, emote(Emote.GLOBAL_FFZ, "ffz-id").favoriteKey()?.provider)
    }

    @Test
    fun providersWithTheSameNameAndIdRemainIndependent() {
        val twitch = Emote(name = "OMEGALUL", id = "same")
        val ffz = emote(Emote.CHANNEL_FFZ, "same", "OMEGALUL")

        assertEquals(FavoriteEmoteKey(EmoteProvider.TWITCH, "same"), twitch.favoriteKey())
        assertEquals(FavoriteEmoteKey(EmoteProvider.FFZ, "same"), ffz.favoriteKey())
    }

    @Test
    fun renamedEmoteKeepsItsFavoriteKey() {
        val before = emote(Emote.CHANNEL_BTTV, "bttv-id", "old_name")
        val after = emote(Emote.CHANNEL_BTTV, "bttv-id", "new_name")

        assertEquals(before.favoriteKey(), after.favoriteKey())
    }

    @Test
    fun blankIdCannotBecomeANameBasedFavorite() {
        assertNull(Emote(name = "PogChamp", id = null).favoriteKey())
        assertNull(Emote(name = "PogChamp", id = "   ").favoriteKey())
    }

    @Test
    fun scopeDuplicateUsesChannelInstance() {
        val global = emote(Emote.GLOBAL_STV, "same", "global_alias")
        val personal = emote(Emote.PERSONAL_STV, "same", "personal_alias")
        val channel = emote(Emote.CHANNEL_STV, "same", "channel_alias")

        val result = FavoriteEmoteCatalog.deduplicate(listOf(global, personal, channel))

        assertEquals(1, result.size)
        assertSame(channel, result.single())
    }

    @Test
    fun removingChannelScopeKeepsGlobalFavoriteFallback() {
        val global = emote(Emote.GLOBAL_STV, "same", "global_alias")
        val channel = emote(Emote.CHANNEL_STV, "same", "channel_alias")
        val catalog = mutableListOf(global, channel)

        FavoriteEmoteCatalog.removeMatchingScope(catalog, listOf(channel), Emote.CHANNEL_STV)

        assertEquals(listOf(global), catalog)
        assertEquals(
            listOf(global),
            FavoriteEmoteCatalog.availableFavorites(
                listOf(FavoriteEmote("SEVENTV", "same", 1L)),
                catalog,
            ),
        )
    }

    @Test
    fun projectionKeepsFavoriteOrderAndHidesUnavailableRows() {
        val favorites = listOf(
            FavoriteEmote("BTTV", "b", 30),
            FavoriteEmote("SEVENTV", "s", 20),
            FavoriteEmote("FFZ", "missing", 10),
        )
        val available = listOf(
            emote(Emote.GLOBAL_STV, "s", "renamed_7tv"),
            emote(Emote.CHANNEL_BTTV, "b", "bttv"),
        )

        val result = FavoriteEmoteCatalog.availableFavorites(favorites, available)

        assertEquals(listOf("bttv", "renamed_7tv"), result.map { it.name })
    }

    private fun emote(source: Int, id: String, name: String = "emote") = Emote(
        source = source,
        id = id,
        name = name,
    )
}
