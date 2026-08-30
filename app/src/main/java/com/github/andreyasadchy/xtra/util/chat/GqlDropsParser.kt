package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.model.ui.TwitchDropBenefit
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCampaign
import com.github.andreyasadchy.xtra.model.ui.TwitchDropCatalogItem
import org.json.JSONArray
import org.json.JSONObject

internal object GqlDropsParser {

    fun parseInventory(body: String): List<TwitchDrop>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null

        if (root.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            return null
        }

        val inventory = root
            .optJSONObject("data")
            ?.optJSONObject("currentUser")
            ?.optJSONObject("inventory")
            ?: return null

        // Distinguish "valid empty inventory" from "Twitch changed the schema".
        if (!inventory.has("dropCampaignsInProgress")) {
            return null
        }

        val campaigns = inventory.optJSONArray("dropCampaignsInProgress")
            ?: return emptyList()

        return buildList {
            for (campaignIndex in 0 until campaigns.length()) {
                val campaign =
                    campaigns.optJSONObject(campaignIndex) ?: continue

                val campaignId = campaign.optionalString("id")
                val campaignName = campaign.optionalString("name")

                val game = campaign.optJSONObject("game")
                val gameName = game?.optionalString("displayName", "name")
                val gameImageUrl = game?.optionalString("boxArtURL")

                val drops = campaign.optJSONArray("timeBasedDrops") ?: continue

                for (dropIndex in 0 until drops.length()) {
                    val drop = drops.optJSONObject(dropIndex) ?: continue
                    val id = drop.optionalString("id") ?: continue
                    val self = drop.optJSONObject("self")

                    val required =
                        drop.optionalInt("requiredMinutesWatched") ?: 0
                    val current =
                        self?.optionalInt("currentMinutesWatched") ?: 0

                    val benefits = parseBenefits(drop)
                    val benefit = benefits.firstOrNull()

                    add(
                        TwitchDrop(
                            id = id,
                            campaignId = campaignId,
                            campaignName = campaignName,
                            gameName = gameName,
                            name = drop.optionalString("name"),
                            rewardName = benefit?.name,
                            imageUrl = benefit?.imageUrl ?: gameImageUrl,
                            dropInstanceId =
                                self?.optionalString("dropInstanceID"),
                            currentMinutesWatched = current.coerceAtLeast(0),
                            requiredMinutesWatched = required.coerceAtLeast(0),
                                isClaimed =
                                    self?.optBoolean("isClaimed", false) == true,
                                benefits = benefits,
                                campaignStartTime = campaign.optionalString("startTime", "startDate"),
                                campaignEndTime = campaign.optionalString("endTime", "endDate"),
                            ),
                    )
                }
            }
        }
    }

    fun parseDashboard(body: String): List<TwitchDropCampaign>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (hasErrors(root)) return null
        val data = root.optJSONObject("data") ?: return null
        val currentUser = data.optJSONObject("currentUser")
        val dashboard = data.optJSONObject("viewerDropsDashboard")
            ?: currentUser?.optJSONObject("dropsDashboard")
            ?: currentUser?.optJSONObject("dropDashboard")
        val campaigns = when {
            dashboard?.has("campaigns") == true -> dashboard.optJSONArray("campaigns")
                ?: JSONArray()
            dashboard?.has("dropCampaigns") == true -> dashboard.optJSONArray("dropCampaigns")
                ?: JSONArray()
            currentUser?.has("dropCampaigns") == true -> currentUser.optJSONArray("dropCampaigns")
                ?: JSONArray()
            else -> return null
        }

        return buildList {
            for (index in 0 until campaigns.length()) {
                val campaign = campaigns.optJSONObject(index) ?: continue
                val id = campaign.optionalString("id") ?: continue
                val game = campaign.optJSONObject("game")
                val gameImage = game?.optionalString(
                    "boxArtURL",
                    "boxArtUrl",
                    "imageURL",
                    "imageUrl",
                    "imageAssetURL",
                ) ?: campaign.optionalString("imageURL", "imageUrl", "imageAssetURL", "thumbnailURL")
                val drops = campaign.optJSONArray("timeBasedDrops")
                    ?: campaign.optJSONArray("drops")
                    ?: JSONArray()
                val catalog = parseCatalogDrops(drops)
                add(
                    TwitchDropCampaign(
                        id = id,
                        name = campaign.optionalString("name"),
                        gameName = game?.optionalString("displayName", "name"),
                        imageUrl = gameImage,
                        startTime = campaign.optionalString("startTime", "startDate"),
                        endTime = campaign.optionalString("endTime", "endDate"),
                        isUpcoming = campaign.optBoolean(
                            "isUpcoming",
                            campaign.optionalString("status")?.equals("UPCOMING", true) == true,
                        ),
                        drops = catalog,
                    ),
                )
            }
        }
    }

    /** Parses lazy campaign enrichment without assuming the private schema is stable. */
    fun parseCampaignDetails(body: String, fallbackCampaignId: String): TwitchDropCampaign? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (hasErrors(root)) return null
        val data = root.optJSONObject("data") ?: return null
        val campaign = findObject(data, "dropCampaign") ?: return null
        if (!campaign.has("timeBasedDrops")) return null

        val game = campaign.optJSONObject("game")
        val drops = campaign.optJSONArray("timeBasedDrops") ?: JSONArray()
        val catalog = parseCatalogDrops(drops)

        return TwitchDropCampaign(
            id = campaign.optionalString("id") ?: fallbackCampaignId,
            name = campaign.optionalString("name"),
            gameName = game?.optionalString("displayName", "name"),
            imageUrl = game?.optionalString(
                "boxArtURL",
                "boxArtUrl",
                "imageURL",
                "imageUrl",
                "imageAssetURL",
            ) ?: campaign.optionalString("imageURL", "imageUrl", "imageAssetURL", "thumbnailURL"),
            startTime = campaign.optionalString("startTime", "startDate", "startAt"),
            endTime = campaign.optionalString("endTime", "endDate", "endAt"),
            isUpcoming = campaign.optBoolean(
                "isUpcoming",
                campaign.optionalString("status")?.equals("UPCOMING", true) == true,
            ),
            drops = catalog,
        )
    }

    /** Returns null for a private-API/schema failure, and an empty set for valid no-results. */
    fun parseAvailableDropIds(body: String): Set<String>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (hasErrors(root)) return null
        val channel = root.optJSONObject("data")?.optJSONObject("channel")
        if (channel?.has("viewerDropCampaigns") == true) {
            val channelCampaigns = channel.optJSONArray("viewerDropCampaigns") ?: return emptySet()
            return channelCampaigns.objects()
                .flatMap { campaign ->
                    listOfNotNull(campaign.optionalString("id")) +
                        campaign.optJSONArray("timeBasedDrops").objects()
                            .mapNotNull { it.optionalString("id") }
                }
                .toSet()
        }
        return parseDropIds(body, "availableDrops")
    }

    fun parseCurrentDropIds(body: String): Set<String>? = parseDropIds(body, "currentDrop")

    private fun parseDropIds(body: String, preferredKey: String): Set<String>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (hasErrors(root)) return null
        val data = root.optJSONObject("data") ?: return null
        val node = findObject(data, preferredKey)
            ?: findObject(data, "dropCurrentSession")
            ?: findObject(data, "drops")
            ?: return null
        val arrays = listOf("drops", "availableDrops", "dropCampaigns", "campaigns")
            .mapNotNull { node.optJSONArray(it) }
        val hasIdentity = node.has("id") ||
            node.has("dropID") ||
            node.has("dropId")
        if (arrays.isEmpty() && !hasIdentity) return null
        return buildSet {
            val items = arrays.flatMap { array ->
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
            } + listOfNotNull(node.takeIf { arrays.isEmpty() && hasIdentity })
            for (item in items) {
                item.optionalString("id", "dropID", "dropId")?.let(::add)
                item.optJSONArray("timeBasedDrops").objects().forEach { drop ->
                    drop.optionalString("id")?.let(::add)
                }
            }
        }
    }

    private fun hasErrors(root: JSONObject): Boolean =
        root.optJSONArray("errors")?.length()?.let { it > 0 } == true

    private fun parseBenefits(drop: JSONObject): List<TwitchDropBenefit> =
        drop.optJSONArray("benefitEdges")
            .objects()
            .mapNotNull { it.optJSONObject("benefit") }
            .map {
                TwitchDropBenefit(
                    name = it.optionalString("name"),
                    imageUrl = it.optionalString("imageAssetURL", "imageURL"),
                )
            }

    private fun parseCatalogDrops(drops: JSONArray): List<TwitchDropCatalogItem> =
        drops.objects().mapNotNull { drop ->
            val id = drop.optionalString("id") ?: return@mapNotNull null
            TwitchDropCatalogItem(
                id = id,
                name = drop.optionalString("name"),
                requiredMinutesWatched =
                    (drop.optionalInt("requiredMinutesWatched") ?: 0).coerceAtLeast(0),
                benefits = parseBenefits(drop),
            )
        }

    private fun findObject(root: JSONObject, key: String): JSONObject? {
        if (root.has(key)) return root.optJSONObject(key)
        val keys = root.keys()
        while (keys.hasNext()) {
            val value = root.opt(keys.next())
            when (value) {
                is JSONObject -> findObject(value, key)?.let { return it }
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        value.optJSONObject(index)?.let { findObject(it, key)?.let { found -> return found } }
                    }
                }
            }
        }
        return null
    }

    fun claimSucceeded(body: String): Boolean {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return false

        if (root.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            return false
        }

        val data = root.optJSONObject("data") ?: return false

        if (data.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            return false
        }

        val status = data
            .optJSONObject("claimDropRewards")
            ?.optionalString("status")
            ?.uppercase()
            ?: return false

        return status == "ELIGIBLE_FOR_ALL" ||
            status == "DROP_INSTANCE_ALREADY_CLAIMED"
    }

    private fun JSONObject.optionalString(
        vararg keys: String,
    ): String? = keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) {
            null
        } else {
            optString(key).takeIf { it.isNotBlank() }
        }
    }

    private fun JSONObject.optionalInt(
        vararg keys: String,
    ): Int? = keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) {
            null
        } else {
            opt(key)?.toString()?.toDoubleOrNull()?.toInt()
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else buildList {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
}
