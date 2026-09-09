package com.github.andreyasadchy.xtra.model.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DropStreamFilterTest {
    private val filter = DropStreamFilter(
        campaignId = "campaign",
        campaignName = "Campaign",
        gameId = "game-1",
        gameName = "Black Desert",
        dropIds = setOf("drop-1"),
    )

    @Test
    fun `game id is preferred when both results provide it`() {
        assertTrue(filter.matchesGame("game-1", "Different label"))
        assertFalse(filter.matchesGame("game-2", "Black Desert"))
    }

    @Test
    fun `game name is used when a result has no game id`() {
        assertTrue(filter.matchesGame(null, "black desert"))
        assertFalse(filter.matchesGame(null, "Another game"))
    }

    @Test
    fun `only DropsEnabled streams reach exact campaign matching`() {
        assertTrue(filter.matchesDropsEnabledTag(listOf("DropsEnabled", "English")))
        assertTrue(filter.matchesDropsEnabledTag(listOf("dropsenabled")))
        assertFalse(filter.matchesDropsEnabledTag(listOf("English")))
        assertFalse(filter.matchesDropsEnabledTag(null))
    }

    @Test
    fun `campaign and drop ids are matched against channel catalog`() {
        val campaign = channelCampaign(
            id = "other-campaign",
            dropId = "drop-1",
        )

        assertTrue(filter.matchesChannelCampaigns(listOf(campaign)))
        assertFalse(filter.copy(dropIds = setOf("other-drop")).matchesChannelCampaigns(listOf(campaign)))
    }

    @Test
    fun `campaign id does not satisfy an explicit drop filter`() {
        val campaign = channelCampaign(
            id = "campaign",
            dropId = "other-drop",
        )

        assertFalse(filter.matchesChannelCampaigns(listOf(campaign)))
        assertTrue(
            filter.matchesChannelCampaigns(
                listOf(channelCampaign(id = "campaign", dropId = "drop-1")),
            ),
        )
        assertFalse(filter.matchesAvailableDropIds(setOf("campaign", "other-drop")))
        assertTrue(filter.matchesAvailableDropIds(setOf("campaign", "drop-1")))
    }

    @Test
    fun `game and drop eligibility come from the same filter`() {
        val gameBFilter = filter.copy(
            campaignId = "campaign-b",
            campaignName = "Campaign B",
            gameId = "game-2",
            gameName = "Game B",
            dropIds = setOf("drop-b"),
        )
        val campaignB = channelCampaign(
            id = "campaign-b",
            dropId = "drop-b",
        )

        assertFalse(
            listOf(filter, gameBFilter).matchesDropStream(
                streamGameId = "game-1",
                streamGameName = "Game A",
                campaigns = listOf(campaignB),
            ),
        )
    }

    @Test
    fun `multiple filters match any selected drop`() {
        val gameBFilter = filter.copy(
            campaignId = "campaign-b",
            campaignName = "Campaign B",
            gameId = "game-2",
            gameName = "Game B",
            dropIds = setOf("drop-b"),
        )

        assertTrue(
            listOf(filter, gameBFilter).matchesDropStream(
                streamGameId = "game-1",
                streamGameName = "Black Desert",
                campaigns = listOf(channelCampaign(id = "campaign", dropId = "drop-1")),
            ),
        )
        assertTrue(
            listOf(filter, gameBFilter).matchesDropStream(
                streamGameId = "game-2",
                streamGameName = "Game B",
                campaigns = listOf(channelCampaign(id = "campaign-b", dropId = "drop-b")),
            ),
        )
    }

    @Test
    fun `drop search queries are unique game names in selection order`() {
        val secondGame = filter.copy(gameId = "game-2", gameName = "Game B")
        val duplicateGame = filter.copy(gameName = " black desert ")

        assertEquals(
            listOf("Black Desert", "Game B"),
            listOf(filter, secondGame, duplicateGame).searchQueries(),
        )
    }

    private fun channelCampaign(
        id: String,
        dropId: String,
    ) = TwitchChannelDropCampaign(
        id = id,
        name = "Campaign",
        gameId = "game-1",
        gameName = "Black Desert",
        imageUrl = null,
        detailsUrl = null,
        startTime = null,
        endTime = null,
        includesWatchRequirement = true,
        includesSubscriptionRequirement = false,
        isSitewide = false,
        isRewardCampaign = false,
        localizedTitle = null,
        earnInstructions = null,
        drops = listOf(
            TwitchChannelDrop(
                id = dropId,
                name = "Drop",
                startTime = null,
                endTime = null,
                requiredMinutesWatched = 30,
                requiredSubs = 0,
                benefits = emptyList(),
                isEventBased = false,
            ),
        ),
    )
}
