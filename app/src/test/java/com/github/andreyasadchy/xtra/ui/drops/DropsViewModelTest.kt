package com.github.andreyasadchy.xtra.ui.drops

import com.github.andreyasadchy.xtra.model.ui.TwitchDropBenefit
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCatalogItem
import com.github.andreyasadchy.xtra.repository.dropsCacheMustReload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropsViewModelTest {
    @Test
    fun `campaign details keep dashboard image and rewards when fields are omitted`() {
        val dashboard = TwitchDropCampaign(
            id = "campaign",
            name = "Dashboard campaign",
            gameName = "Game",
            imageUrl = "https://example.com/game.png",
            startTime = "start",
            endTime = "end",
            isUpcoming = false,
            drops = listOf(
                TwitchDropCatalogItem(
                    id = "drop",
                    name = "Dashboard drop",
                    requiredMinutesWatched = 60,
                    benefits = listOf(TwitchDropBenefit("Reward", "https://example.com/reward.png")),
                ),
            ),
        )
        val details = dashboard.copy(
            name = null,
            imageUrl = null,
            startTime = null,
            endTime = null,
            drops = emptyList(),
        )

        val merged = mergeCampaignDetails(dashboard, details)

        assertEquals("https://example.com/game.png", merged.imageUrl)
        assertEquals("Dashboard campaign", merged.name)
        assertEquals(dashboard.drops, merged.drops)
    }

    @Test
    fun `partial campaign details retain dashboard-only rewards`() {
        val dashboard = TwitchDropCampaign(
            id = "campaign",
            name = "Campaign",
            gameName = "Game",
            imageUrl = null,
            startTime = null,
            endTime = null,
            isUpcoming = false,
            drops = listOf(
                TwitchDropCatalogItem("drop-a", "A", 60, emptyList()),
                TwitchDropCatalogItem("drop-b", "B", 120, emptyList()),
            ),
        )
        val details = dashboard.copy(
            drops = listOf(TwitchDropCatalogItem("drop-a", "A", 60, emptyList())),
        )

        assertEquals(
            listOf("drop-a", "drop-b"),
            mergeCampaignDetails(dashboard, details).drops.map { it.id },
        )
    }

    @Test
    fun `fresh dashboard status can move enriched campaign from upcoming to active`() {
        val upcoming = TwitchDropCampaign(
            id = "campaign",
            name = "Campaign",
            gameName = "Game",
            imageUrl = null,
            startTime = null,
            endTime = null,
            isUpcoming = true,
            drops = emptyList(),
        )
        val enriched = upcoming.copy(isUpcoming = true)
        val active = upcoming.copy(isUpcoming = false)

        assertEquals(false, mergeCampaignDetails(active, enriched).isUpcoming)
    }

    @Test
    fun `same account cache reloads after logout resets the sentinel`() {
        assertFalse(dropsCacheMustReload("account-a", "account-a"))
        assertTrue(dropsCacheMustReload(null, "account-a"))
    }
}
