package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.repository.projectDropsForChannel
import com.github.andreyasadchy.xtra.repository.mergeDropsWithDashboard
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCatalogItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GqlDropsParserTest {
    @Test
    fun `30 of 60 minutes is half complete and not claimable`() {
        val drop = GqlDropsParser.parseInventory(inventoryJson(30, 60, "instance-1"))!!.single()

        assertEquals(50, drop.progressPercent)
        assertFalse(drop.isClaimable)
    }

    @Test
    fun `60 of 60 minutes with an instance id is claimable`() {
        val drop = GqlDropsParser.parseInventory(inventoryJson(60, 60, "instance-1"))!!.single()

        assertTrue(drop.isClaimable)
    }

    @Test
    fun `claimed drop is not claimable`() {
        val drop = GqlDropsParser.parseInventory(
            inventoryJson(60, 60, "instance-1", isClaimed = true),
        )!!.single()

        assertFalse(drop.isClaimable)
    }

    @Test
    fun `empty campaigns is a valid empty inventory`() {
        assertEquals(emptyList<Any>(), GqlDropsParser.parseInventory(emptyInventoryJson()))
    }

    @Test
    fun `missing campaigns signals a schema change`() {
        assertNull(GqlDropsParser.parseInventory("""{"data":{"currentUser":{"inventory":{}}}}"""))
    }

    @Test
    fun `eligible claim succeeds`() {
        assertTrue(GqlDropsParser.claimSucceeded(claimJson("ELIGIBLE_FOR_ALL")))
    }

    @Test
    fun `already claimed is treated as success`() {
        assertTrue(GqlDropsParser.claimSucceeded(claimJson("DROP_INSTANCE_ALREADY_CLAIMED")))
    }

    @Test
    fun `graphql errors fail parsing and claiming`() {
        assertNull(GqlDropsParser.parseInventory("""{"errors":[{"message":"failed"}]}"""))
        assertFalse(GqlDropsParser.claimSucceeded("""{"errors":[{"message":"failed"}]}"""))
        assertFalse(GqlDropsParser.claimSucceeded("""{"data":{"errors":[{"message":"failed"}]}}"""))
    }

    @Test
    fun `all benefit edges are preserved`() {
        val drop = GqlDropsParser.parseInventory(inventoryJson(30, 60, "instance-1", benefits = true))!!.single()

        assertEquals(2, drop.benefits.size)
        assertEquals("Reward", drop.benefits[0].name)
        assertEquals("Bonus", drop.benefits[1].name)
    }

    @Test
    fun `dashboard parses active and upcoming campaigns`() {
        val campaigns = GqlDropsParser.parseDashboard(
            """{"data":{"viewerDropsDashboard":{"campaigns":[
                {"id":"active","name":"Active","isUpcoming":false,"game":{"displayName":"Game"},"drops":[]},
                {"id":"upcoming","name":"Upcoming","isUpcoming":true,"game":{"displayName":"Game"},"drops":[]}
            ]}}}""",
        )!!

        assertEquals(listOf("active", "upcoming"), campaigns.map { it.id })
        assertFalse(campaigns[0].isUpcoming)
        assertTrue(campaigns[1].isUpcoming)
    }

    @Test
    fun `dashboard parses Twitch current user drop campaigns`() {
        val campaigns = GqlDropsParser.parseDashboard(
            """{"data":{"currentUser":{"dropCampaigns":[
                {"id":"active","status":"ACTIVE","name":"Active","game":{"displayName":"Game"},"timeBasedDrops":[]},
                {"id":"upcoming","status":"UPCOMING","name":"Upcoming","game":{"displayName":"Game"},"timeBasedDrops":[]}
            ]}}}""",
        )!!

        assertEquals(listOf("active", "upcoming"), campaigns.map { it.id })
        assertFalse(campaigns[0].isUpcoming)
        assertTrue(campaigns[1].isUpcoming)
    }

    @Test
    fun `available drops projection excludes unrelated account campaigns`() {
        val drops = GqlDropsParser.parseInventory(
            inventoryJson(30, 60, "instance-1")
                .replace("campaign-1", "campaign-included"),
        )!!
        val unrelated = drops.single().copy(id = "unrelated", campaignId = "other")

        assertEquals(
            listOf("drop-1"),
            projectDropsForChannel(drops + unrelated, setOf("campaign-included")).map { it.id },
        )
    }

    @Test
    fun `available drops parser returns campaign and drop ids`() {
        val ids = GqlDropsParser.parseAvailableDropIds(
            """{"data":{"availableDrops":{"drops":[{"id":"drop-1"}],"dropCampaigns":[{"id":"campaign-1"}]}}}""",
        )

        assertEquals(setOf("drop-1", "campaign-1"), ids)
    }

    @Test
    fun `available drops parser reads Twitch channel campaign shape`() {
        val ids = GqlDropsParser.parseAvailableDropIds(
            """{"data":{"channel":{"viewerDropCampaigns":[
                {"id":"campaign-1","timeBasedDrops":[{"id":"drop-1"}]}
            ]}}}""",
        )

        assertEquals(setOf("campaign-1", "drop-1"), ids)
    }

    @Test
    fun `missing available drops schema is not treated as empty`() {
        assertNull(GqlDropsParser.parseAvailableDropIds("""{"data":{}}"""))
    }

    @Test
    fun `current drop parser reads the session drop id`() {
        assertEquals(
            setOf("drop-1"),
            GqlDropsParser.parseCurrentDropIds(
                """{"data":{"currentUser":{"dropCurrentSession":{"dropID":"drop-1"}}}}""",
            ),
        )
    }

    @Test
    fun `inventory and dashboard merge by campaign and drop ids`() {
        val drop = GqlDropsParser.parseInventory(inventoryJson(30, 60, "instance-1"))!!.single()
        val merged = mergeDropsWithDashboard(
            listOf(drop.copy(campaignName = null, gameName = null)),
            listOf(
                TwitchDropCampaign(
                    id = "campaign-1",
                    name = "Dashboard campaign",
                    gameName = "Dashboard game",
                    imageUrl = "https://example.com/game.png",
                    startTime = null,
                    endTime = null,
                    isUpcoming = false,
                    drops = listOf(TwitchDropCatalogItem("drop-1", "Dashboard drop", 60, emptyList())),
                ),
            ),
        ).single()

        assertEquals("Dashboard campaign", merged.campaignName)
        assertEquals("Dashboard game", merged.gameName)
    }

    private fun inventoryJson(
        current: Int,
        required: Int,
        instanceId: String?,
        isClaimed: Boolean = false,
        benefits: Boolean = false,
    ): String {
        val instanceField = instanceId?.let { "\"dropInstanceID\":\"$it\"," }.orEmpty()
        val benefitEdges = if (benefits) {
            "[{\"benefit\":{\"name\":\"Reward\",\"imageAssetURL\":\"https://example.com/reward.png\"}},{\"benefit\":{\"name\":\"Bonus\"}}]"
        } else {
            "[{\"benefit\":{\"name\":\"Reward\",\"imageAssetURL\":\"https://example.com/reward.png\"}}]"
        }
        return """
            {
              "data": {
                "currentUser": {
                  "inventory": {
                    "dropCampaignsInProgress": [{
                      "id": "campaign-1",
                      "name": "Campaign",
                      "game": {"displayName": "Game", "boxArtURL": "https://example.com/game.png"},
                      "timeBasedDrops": [{
                        "id": "drop-1",
                        "name": "Drop",
                        "requiredMinutesWatched": $required,
                        "self": {
                          $instanceField
                          "currentMinutesWatched": $current,
                          "isClaimed": $isClaimed
                        },
                        "benefitEdges": $benefitEdges
                      }]
                    }]
                  }
                }
              }
            }
        """.trimIndent()
    }

    private fun emptyInventoryJson(): String =
        """{"data":{"currentUser":{"inventory":{"dropCampaignsInProgress":[]}}}}"""

    private fun claimJson(status: String): String =
        """{"data":{"claimDropRewards":{"status":"$status"}}}"""
}
