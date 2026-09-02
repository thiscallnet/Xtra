package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmoteCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPickerCatalogTest {
    @Test
    fun v2ProjectionKeepsTwitchAndThirdPartyEmotesForEveryPickerSection() {
        val subscriber = Emote(name = "sub", id = "sub-id")
        val sevenTv = Emote(
            name = "party",
            id = "7tv-id",
            source = Emote.CHANNEL_STV,
        )
        val catalog = ChatViewModel.PickerCatalog(
            twitch = listOf(subscriber),
            thirdParty = listOf(sevenTv),
        )

        assertEquals(listOf(subscriber, sevenTv), catalog.all)
        assertTrue(catalog.twitch.any { it.name == "sub" })
        assertTrue(catalog.thirdParty.any { it.name == "party" })
    }

    @Test
    fun v2ThirdPartyIdentityResolvesFavoritesAndRecents() {
        val sevenTv = Emote(name = "party", id = "7tv-id", source = Emote.CHANNEL_STV)
        val catalog = ChatViewModel.PickerCatalog(emptyList(), listOf(sevenTv))
        val favorite = FavoriteEmote("SEVENTV", "7tv-id", favoritedAt = 1L)

        assertEquals(
            listOf(sevenTv),
            FavoriteEmoteCatalog.availableFavorites(listOf(favorite), catalog.all),
        )
        assertEquals(sevenTv, catalog.all.single { it.name == "party" })
    }

    @Test
    fun v2ThirdPartyProjectionKeepsPersonalSevenTvEmotes() {
        val personal = Emote(
            name = "personalParty",
            id = "personal-7tv-id",
            source = Emote.PERSONAL_STV,
        )

        assertEquals(
            listOf(personal),
            mergePickerThirdPartyEmotes(listOf(personal), emptyList()),
        )
    }

    @Test
    fun initialPersonalSevenTvHydrationMakesTheRestEmotesPickerEligible() {
        var userSetId: String? = null
        var personalEmoteSets = emptyMap<String, List<Emote>>()
        assertTrue(personalSevenTvEmotesForPicker(userSetId, personalEmoteSets[userSetId]).isEmpty())

        userSetId = "personal-set-id"
        personalEmoteSets = mapOf(
            userSetId to listOf(Emote(name = "personalParty", id = "personal-7tv-id")),
        )
        val hydrated = personalSevenTvEmotesForPicker(userSetId, personalEmoteSets[userSetId])

        assertEquals(Emote.PERSONAL_STV, hydrated.single().source)
        assertEquals("personal-7tv-id", hydrated.single().id)
    }
}
