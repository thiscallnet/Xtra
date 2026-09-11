package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.repository.projectDropsForChannel
import com.github.andreyasadchy.xtra.repository.mergeDropsWithDashboard
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCatalogItem
import com.github.andreyasadchy.xtra.model.ui.TwitchDropImageSource
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
    fun `duplicate inventory rows merge progress claim state and benefits`() {
        val drops = GqlDropsParser.parseInventory(
            """{"data":{"currentUser":{"inventory":{"dropCampaignsInProgress":[
                {"id":"campaign-1","timeBasedDrops":[{"id":"drop-1","requiredMinutesWatched":60,
                    "self":{"dropInstanceID":"old","currentMinutesWatched":20,"isClaimed":false},
                    "benefitEdges":[{"benefit":{"name":"Reward A"}}]}]},
                {"id":"campaign-1","timeBasedDrops":[{"id":"drop-1","requiredMinutesWatched":90,
                    "self":{"dropInstanceID":"new","currentMinutesWatched":45,"isClaimed":true},
                    "benefitEdges":[{"benefit":{"name":"Reward B"}}]}]}
            ]}}}}""",
        )!!

        assertEquals(1, drops.size)
        assertEquals(45, drops.single().currentMinutesWatched)
        assertEquals(90, drops.single().requiredMinutesWatched)
        assertEquals("new", drops.single().dropInstanceId)
        assertTrue(drops.single().isClaimed)
        assertEquals(2, drops.single().benefits.size)
    }

    @Test
    fun `duplicate dashboard campaigns merge catalog details`() {
        val campaigns = GqlDropsParser.parseDashboard(
            """{"data":{"viewerDropsDashboard":{"campaigns":[
                {"id":"campaign-1","name":"","isUpcoming":false,
                 "game":{"displayName":"Game"},"drops":[{"id":"drop-1","requiredMinutesWatched":60,
                 "benefitEdges":[{"benefit":{"name":"Reward A"}}]}]},
                {"id":"campaign-1","name":"Campaign","isUpcoming":true,
                 "game":{"id":"game-1","displayName":"Game"},"drops":[{"id":"drop-1","requiredMinutesWatched":90,
                 "benefitEdges":[{"benefit":{"name":"Reward B"}}]}]}
            ]}}}""",
        )!!

        assertEquals(1, campaigns.size)
        assertEquals("Campaign", campaigns.single().name)
        assertFalse(campaigns.single().isUpcoming)
        assertEquals("game-1", campaigns.single().gameId)
        assertEquals(90, campaigns.single().drops.single().requiredMinutesWatched)
        assertEquals(2, campaigns.single().drops.single().benefits.size)
    }

    @Test
    fun `reward artwork is never treated as resizable game box art`() {
        val drop = GqlDropsParser.parseInventory(
            inventoryJson(30, 60, "instance-1").replace(
                "https://example.com/reward.png",
                "https://static-cdn.jtvnw.net/ttv-boxart/reward-160x160.jpg",
            ),
        )!!.single()

        assertEquals(TwitchDropImageSource.ORIGINAL, drop.imageSource)
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
    fun `available drops parser preserves channel catalog requirements and rewards`() {
        val campaigns = GqlDropsParser.parseAvailableDrops(
            """{"data":{"channel":{"viewerDropCampaigns":[
                {"id":"campaign-1","name":"Campaign","game":{"id":"42","name":"Game"},
                 "detailsURL":"https://example.com","endAt":"2026-09-30T15:59:59.999Z",
                 "imageURL":"campaign.png","summary":{"includesMWRequirement":true,"includesSubRequirement":true},
                 "localizedContent":{"title":"Watch to earn Drops!","earnInstructions":"Watch {Channel}"},
                 "timeBasedDrops":[{"id":"drop-1","name":"Watch reward","requiredMinutesWatched":30,
                   "requiredSubs":0,"benefitEdges":[{"benefit":{"name":"Reward","imageAssetURL":"reward.png"}}]}],
                 "eventBasedDrops":[{"id":"drop-2","name":"Sub reward","requiredMinutesWatched":0,
                   "requiredSubs":1,"benefitEdges":[]}]}
            ]}}}""",
        )!!

        assertEquals(1, campaigns.size)
        assertEquals("42", campaigns[0].gameId)
        assertEquals("https://example.com", campaigns[0].detailsUrl)
        assertTrue(campaigns[0].includesWatchRequirement)
        assertTrue(campaigns[0].includesSubscriptionRequirement)
        assertEquals(2, campaigns[0].drops.size)
        assertEquals(30, campaigns[0].drops[0].requiredMinutesWatched)
        assertEquals(1, campaigns[0].drops[1].requiredSubs)
        assertTrue(campaigns[0].drops[1].isEventBased)
        assertEquals("Reward", campaigns[0].drops[0].benefits.single().name)
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
    fun `current drop progress parser reads the watched minutes`() {
        assertEquals(
            DropProgressUpdate("drop-1", 37, 60),
            GqlDropsParser.parseCurrentDropProgress(
                """{"data":{"currentUser":{"dropCurrentSession":{"dropID":"drop-1","currentMinutesWatched":37,"requiredMinutesWatched":60}}}}""",
            ),
        )
    }

    @Test
    fun `user drop event parser reads progress updates`() {
        assertEquals(
            DropProgressUpdate("drop-1", 38, 60),
            GqlDropsParser.parseDropProgressMessage(
                org.json.JSONObject(
                    """{"type":"drop-progress","data":{"drop_id":"drop-1","current_progress_min":38,"required_progress_min":60}}""",
                ),
            ),
        )
    }

    @Test
    fun `non-progress user drop events are ignored`() {
        assertNull(
            GqlDropsParser.parseDropProgressMessage(
                org.json.JSONObject("""{"type":"drop-claim","data":{}}"""),
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


    @Test
    fun `hermes drops are reduced to typed safe metadata`() {
        val message = org.json.JSONObject(
            """
            {
              "type": "drop-progress",
              "drop_id": "drop-42",
              "data": {
                "current_progress_min": 12,
                "required_progress_min": 30,
                "access_token": "must-not-be-retained"
              }
            }
            """.trimIndent(),
        )

        val update = GqlDropsParser.parseDropProgressMessage(message)

        assertTrue(update != null)
        assertEquals("drop-42", update?.dropId)
        assertEquals(12, update?.currentMinutesWatched)
        assertEquals(30, update?.requiredMinutesWatched)
        assertFalse(update.toString().contains("must-not-be-retained"))
    }

    @Test
    fun `hermes claim is typed without retaining payload`() {
        val message = org.json.JSONObject(
            """
            {
              "type": "drop-claim",
              "data": {
                "dropId": "drop-42",
                "claimed": true,
                "raw_payload": "private-data"
              }
            }
            """.trimIndent(),
        )

        val update = GqlDropsParser.parseDropClaimMessage(message)

        assertTrue(update != null)
        assertEquals("drop-42", update?.dropId)
        assertEquals(true, update?.claimed)
        assertFalse(update.toString().contains("private-data"))
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
