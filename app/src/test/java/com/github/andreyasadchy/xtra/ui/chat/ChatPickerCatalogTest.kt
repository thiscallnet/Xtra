package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmote
import com.github.andreyasadchy.xtra.model.chat.FavoriteEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ScopedEmoteCatalog
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.viewerSendableValues
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec
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
    fun v2SendablePickerProjectionKeepsOnlyTheWinningSameNameScope() {
        val global = catalogEmote("same", "global", ChatEmoteScope.GLOBAL)
        val channel = catalogEmote("same", "channel", ChatEmoteScope.CHANNEL)
        val snapshot = ChatCatalogSnapshot(
            revision = 1,
            sevenTv = ScopedEmoteCatalog(
                global = mapOf(global.name to global),
                channel = mapOf(channel.name to channel),
            ),
        )

        assertEquals(listOf(channel), snapshot.viewerSendableValues())
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

    private fun catalogEmote(name: String, id: String, scope: ChatEmoteScope) = ChatCatalogEmote(
        name = name,
        id = id,
        asset = ChatAssetSpec(ChatAssetKey(id), 56, 56, 28),
        provider = ChatAssetProvider.SEVEN_TV,
        animated = false,
        scope = scope,
    )
}
