package com.github.andreyasadchy.xtra.ui.drops

import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropsPresentationTest {
    @Test
    fun `saved tab is restored and invalid values are bounded`() {
        assertEquals(0, restoreDropsTab(null))
        assertEquals(0, restoreDropsTab(-1))
        assertEquals(1, restoreDropsTab(1))
        assertEquals(2, restoreDropsTab(99))
    }

    @Test
    fun `stream search is only offered for active campaigns with game names`() {
        val active = campaign(isUpcoming = false, gameId = "game-1", gameName = "Game")
        assertTrue(campaignCanFindLiveStreams(active))
        assertFalse(campaignCanFindLiveStreams(active.copy(isUpcoming = true)))
        assertTrue(campaignCanFindLiveStreams(active.copy(gameId = null)))
        assertFalse(campaignCanFindLiveStreams(active.copy(gameName = null)))
    }

    @Test
    fun `stream search requires the channel drops response to contain the campaign`() {
        assertTrue(channelOffersCampaign(setOf("campaign", "other-drop"), "campaign", emptySet()))
        assertTrue(channelOffersCampaign(setOf("drop-1"), "campaign", setOf("drop-1")))
        assertFalse(channelOffersCampaign(setOf("other"), "campaign", setOf("drop-1")))
    }

    private fun campaign(
        isUpcoming: Boolean,
        gameId: String?,
        gameName: String?,
    ) = TwitchDropCampaign(
        id = "campaign",
        name = "Campaign",
        gameName = gameName,
        imageUrl = null,
        startTime = null,
        endTime = null,
        isUpcoming = isUpcoming,
        drops = emptyList(),
        gameId = gameId,
    )
}
