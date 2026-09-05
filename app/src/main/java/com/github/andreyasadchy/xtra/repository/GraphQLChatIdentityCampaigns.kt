package com.github.andreyasadchy.xtra.repository

import android.os.SystemClock
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadge
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadgeKey
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityCampaign
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityCampaignBadge
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityCampaignReward
import com.github.andreyasadchy.xtra.model.chat.isSubscriptionSlotBadge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/** The web operation used by Twitch's Chat Identity active collections section. */
internal const val CHAT_IDENTITY_CAMPAIGNS_QUERY = """
    query ViewerDropsDashboard(${'$'}fetchRewardCampaigns: Boolean!) {
      currentUser {
        id
      }
      rewardCampaignsAvailableToUser @include(if: ${'$'}fetchRewardCampaigns) {
        id
        name
        brand
        startsAt
        endsAt
        status
        summary
        isSitewide
        game {
          id
          displayName
        }
        image {
          image1xURL
        }
        unlockRequirements {
          minuteWatchedGoal
          subsGoal
        }
        rewards {
          id
          name
          earnableUntil
          thumbnailImage {
            image1xURL
          }
          bannerImage {
            image1xURL
          }
        }
      }
    }
"""

/** The global badge catalog used by the bounded campaign-to-badge reconciliation fallback. */
internal const val CHAT_IDENTITY_GLOBAL_BADGE_CATALOG_QUERY = """
    query ChatIdentityGlobalBadgeCatalog {
      badges {
        id
        setID
        version
        title
        description
        imageURL(size: QUADRUPLE)
      }
    }
"""

private data class RewardCampaign(
    val id: String,
    val name: String,
    val brand: String?,
    val startsAt: String?,
    val endsAt: String?,
    val status: String?,
    val gameName: String?,
    val imageUrl: String?,
    val rewards: List<ChatIdentityCampaignReward>,
)

private data class CatalogBadge(
    val key: ChatIdentityBadgeKey,
    val title: String?,
    val description: String?,
    val imageUrl: String,
)

/**
 * Twitch exposes reward containers here, not necessarily the chat badges in a collection.
 * Keep that distinction explicit and only show a card after badge metadata gives us a strong
 * relationship to the reward campaign.
 */
internal object ChatIdentityCampaignParser {
    internal fun hasUsableActiveRewardCampaigns(body: String, now: Instant): Boolean =
        parseRewardCampaigns(body)?.any { it.isActiveAt(now) } == true

    fun parse(
        rewardCampaignsBody: String,
        badgeCatalogBody: String,
        ownedBadges: Set<ChatIdentityBadgeKey>,
        now: Instant,
    ): List<ChatIdentityCampaign>? {
        val rewardCampaigns = parseRewardCampaigns(rewardCampaignsBody) ?: return null
        val badgeCatalog = parseBadgeCatalog(badgeCatalogBody) ?: return null
        return project(rewardCampaigns, badgeCatalog, ownedBadges, now)
    }

    internal fun parseDisplayBadges(body: JsonObject): List<ChatIdentityBadge> =
        body.optObject("channel")
            ?.optObject("self")
            ?.optArray("displayBadges")
            .orEmpty()
            .mapNotNull { parseBadge(it) }

    private fun parseRewardCampaigns(body: String): List<RewardCampaign>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.hasErrors()) return null
        val data = root.optJSONObject("data") ?: return null
        if (!data.has("rewardCampaignsAvailableToUser")) return null
        val campaigns = data.optJSONArray("rewardCampaignsAvailableToUser") ?: return emptyList()
        return buildList {
            for (index in 0 until campaigns.length()) {
                val campaign = campaigns.optJSONObject(index) ?: continue
                val id = campaign.optionalString("id") ?: continue
                val name = campaign.optionalString("name") ?: continue
                add(
                    RewardCampaign(
                        id = id,
                        name = name,
                        brand = campaign.optionalString("brand"),
                        startsAt = campaign.optionalString("startsAt"),
                        endsAt = campaign.optionalString("endsAt"),
                        status = campaign.optionalString("status"),
                        gameName = campaign.optJSONObject("game")?.optionalString("displayName"),
                        imageUrl = campaign.optJSONObject("image")?.optionalString("image1xURL"),
                        rewards = parseRewards(campaign.optJSONArray("rewards")),
                    ),
                )
            }
        }
    }

    private fun parseBadgeCatalog(body: String): List<CatalogBadge>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.hasErrors()) return null
        val data = root.optJSONObject("data") ?: return null
        if (!data.has("badges")) return null
        val badges = data.optJSONArray("badges") ?: return emptyList()
        return buildList {
            for (index in 0 until badges.length()) {
                val badge = badges.optJSONObject(index) ?: continue
                val setId = badge.optionalString("setID") ?: continue
                val version = badge.optionalString("version") ?: continue
                val imageUrl = badge.optionalString("imageURL") ?: continue
                add(
                    CatalogBadge(
                        key = ChatIdentityBadgeKey(setId, version),
                        title = badge.optionalString("title"),
                        description = badge.optionalString("description"),
                        imageUrl = imageUrl,
                    ),
                )
            }
        }
    }

    private fun project(
        campaigns: List<RewardCampaign>,
        catalog: List<CatalogBadge>,
        ownedBadges: Set<ChatIdentityBadgeKey>,
        now: Instant,
    ): List<ChatIdentityCampaign> {
        val activeCampaigns = campaigns.filter { it.isActiveAt(now) }
        val grouped = activeCampaigns
            .groupBy(::logicalCampaignKey)
            .filterKeys(String::isNotBlank)
        if (grouped.isEmpty()) return emptyList()

        val matchedGroups = catalog.associateWith { badge ->
            grouped.keys.filter { key -> grouped.getValue(key).any { badge.matches(it) } }
        }
        val ambiguousGroups = matchedGroups.values
            .filter { it.size > 1 }
            .flatten()
            .toSet()

        return grouped.mapNotNull { (logicalKey, sourceCampaigns) ->
            if (logicalKey in ambiguousGroups) return@mapNotNull null
            val badges = matchedGroups.asSequence()
                .filter { (_, matches) -> matches.singleOrNull() == logicalKey }
                .map { (badge, _) ->
                    ChatIdentityCampaignBadge(
                        key = badge.key,
                        title = badge.title,
                        description = badge.description,
                        imageUrl = badge.imageUrl,
                        owned = badge.key in ownedBadges,
                    )
                }
                .distinctBy { it.key }
                .toList()
            if (badges.isEmpty()) return@mapNotNull null

            val rewards = sourceCampaigns.asSequence()
                .flatMap { it.rewards.asSequence() }
                .distinctBy { it.id }
                .toList()
            ChatIdentityCampaign(
                id = logicalKey,
                title = sourceCampaigns.first().name,
                subtitle = sourceCampaigns.first().brand ?: sourceCampaigns.first().gameName,
                imageUrl = sourceCampaigns.firstNotNullOfOrNull { it.imageUrl },
                earnedBadges = badges.count { it.owned },
                totalBadges = badges.size,
                brand = sourceCampaigns.first().brand,
                game = sourceCampaigns.first().gameName,
                rewards = rewards,
                badges = badges,
                startsAt = sourceCampaigns.mapNotNull { it.startsAt }.minOrNull(),
                endsAt = sourceCampaigns.mapNotNull { it.endsAt }.maxOrNull(),
                rewardCampaignIds = sourceCampaigns.map { it.id }.distinct(),
            )
        }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun logicalCampaignKey(campaign: RewardCampaign): String {
        val name = normalize(campaign.name)
        if (name.isBlank()) return ""
        val brand = normalize(campaign.brand)
        val game = normalize(campaign.gameName)
        return if (brand.isNotBlank()) {
            "$brand|$name"
        } else if (game.isNotBlank()) {
            "$name|game:$game"
        } else {
            "name:$name"
        }
    }

    private fun RewardCampaign.isActiveAt(now: Instant): Boolean {
        val normalizedStatus = status?.trim()?.uppercase(Locale.ROOT)
        if (normalizedStatus in setOf("EXPIRED", "ENDED", "COMPLETE", "COMPLETED")) return false
        val starts = startsAt?.toInstantOrNull()
        val ends = endsAt?.toInstantOrNull()
        if (starts != null && starts > now) return false
        if (ends != null && ends <= now) return false
        return true
    }

    private fun CatalogBadge.matches(campaign: RewardCampaign): Boolean {
        val description = normalize(description)
        if (description.isBlank()) return false
        val name = normalize(campaign.name)
        if (name.isBlank()) return false
        if (description.contains(name)) return true
        val nameTerms = meaningfulTerms(name)
        if (nameTerms.size < 2) return false
        if (!nameTerms.all(description::contains)) return false
        val brandTerms = meaningfulTerms(normalize(campaign.brand))
        return brandTerms.isEmpty() || brandTerms.all(description::contains)
    }

    private fun parseRewards(rewards: JSONArray?): List<ChatIdentityCampaignReward> =
        buildList {
            if (rewards == null) return@buildList
            for (index in 0 until rewards.length()) {
                val reward = rewards.optJSONObject(index) ?: continue
                val id = reward.optionalString("id") ?: continue
                add(
                    ChatIdentityCampaignReward(
                        id = id,
                        title = reward.optionalString("name"),
                        description = null,
                        imageUrl = reward.optJSONObject("bannerImage")?.optionalString("image1xURL")
                            ?: reward.optJSONObject("thumbnailImage")?.optionalString("image1xURL"),
                        earnableUntil = reward.optionalString("earnableUntil"),
                    ),
                )
            }
        }

    private fun parseBadge(element: kotlinx.serialization.json.JsonElement): ChatIdentityBadge? {
        val badge = element.jsonObjectOrNull() ?: return null
        val setId = badge.stringOrNull("setID") ?: return null
        val version = badge.stringOrNull("version") ?: return null
        val imageUrl = badge.stringOrNull("imageURL") ?: return null
        return ChatIdentityBadge(
            key = ChatIdentityBadgeKey(setId, version),
            title = badge.stringOrNull("title"),
            description = badge.stringOrNull("description"),
            imageUrl = imageUrl,
        )
    }

    private fun meaningfulTerms(value: String?): List<String> = value.orEmpty()
        .split(' ')
        .filter { it.length >= 3 }
        .filterNot { it in setOf("and", "for", "the", "with") }

    private fun normalize(value: String?): String = Normalizer.normalize(
        value.orEmpty(),
        Normalizer.Form.NFD,
    ).replace("\\p{M}+".toRegex(), "")
        .lowercase(Locale.ROOT)
        .replace("[^a-z0-9]+".toRegex(), " ")
        .trim()

    private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

    private fun JSONObject.hasErrors(): Boolean = optJSONArray("errors")?.length()?.let { it > 0 } == true

    private fun JSONObject.optionalString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        if (!has(key) || isNull(key)) null else optString(key).takeIf(String::isNotBlank)
    }

    private fun JsonObject.optObject(name: String): JsonObject? =
        this[name]?.jsonObjectOrNull()

    private fun JsonObject.optArray(name: String): List<kotlinx.serialization.json.JsonElement>? =
        this[name]?.jsonArrayOrNull()?.toList()

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
        runCatching { jsonArray }.getOrNull()

    private fun JsonObject.stringOrNull(name: String): String? =
        this[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

internal class ChatIdentityCampaignRepository private constructor(
    private val networkLoader: suspend (
        networkLibrary: String?,
        headers: Map<String, String>,
        ownedBadges: List<ChatIdentityBadge>,
    ) -> List<ChatIdentityCampaign>,
    private val elapsedRealtime: () -> Long,
) {
    constructor(graphQLRepository: GraphQLRepository) : this(
        networkLoader = { networkLibrary, headers, ownedBadges ->
            graphQLRepository.loadChatIdentityCampaigns(
                networkLibrary = networkLibrary,
                headers = headers,
                ownedBadges = ownedBadges,
            )
        },
        elapsedRealtime = SystemClock::elapsedRealtime,
    )

    private data class CacheEntry(
        val viewerId: String,
        val loadedAtElapsedRealtime: Long,
        val campaigns: List<ChatIdentityCampaign>,
    )

    private val mutex = Mutex()
    private var cache: CacheEntry? = null

    suspend fun load(
        viewerId: String,
        networkLibrary: String?,
        headers: Map<String, String>,
        ownedBadges: List<ChatIdentityBadge>,
    ): List<ChatIdentityCampaign> = mutex.withLock {
        val now = elapsedRealtime()
        cache?.takeIf {
            it.viewerId == viewerId && now - it.loadedAtElapsedRealtime < CACHE_TTL_MS
        }?.let { return@withLock it.campaigns }

        val campaigns = try {
            networkLoader(networkLibrary, headers, ownedBadges)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        cache = CacheEntry(viewerId, now, campaigns)
        campaigns
    }

    fun clear() {
        cache = null
    }

    companion object {
        internal fun forTesting(
            networkLoader: suspend (
                networkLibrary: String?,
                headers: Map<String, String>,
                ownedBadges: List<ChatIdentityBadge>,
            ) -> List<ChatIdentityCampaign>,
            elapsedRealtime: () -> Long = { 0L },
        ): ChatIdentityCampaignRepository = ChatIdentityCampaignRepository(networkLoader, elapsedRealtime)

        private const val CACHE_TTL_MS = 3 * 60 * 1000L
    }
}

private suspend fun GraphQLRepository.loadChatIdentityCampaigns(
    networkLibrary: String?,
    headers: Map<String, String>,
    ownedBadges: List<ChatIdentityBadge>,
): List<ChatIdentityCampaign> {
    val campaignsBody = executeRawOperation(
        networkLibrary = networkLibrary,
        headers = headers,
        operationName = "ViewerDropsDashboard",
        query = CHAT_IDENTITY_CAMPAIGNS_QUERY,
        variables = kotlinx.serialization.json.buildJsonObject {
            put("fetchRewardCampaigns", true)
        },
    ).toString()
    val now = Instant.now()
    if (!ChatIdentityCampaignParser.hasUsableActiveRewardCampaigns(campaignsBody, now)) {
        return emptyList()
    }
    val badgeCatalogBody = executeRawOperation(
        networkLibrary = networkLibrary,
        headers = headers,
        operationName = "ChatIdentityGlobalBadgeCatalog",
        query = CHAT_IDENTITY_GLOBAL_BADGE_CATALOG_QUERY,
    ).toString()
    return ChatIdentityCampaignParser.parse(
        rewardCampaignsBody = campaignsBody,
        badgeCatalogBody = badgeCatalogBody,
        ownedBadges = ownedBadges
            .filterNot(ChatIdentityBadge::isSubscriptionSlotBadge)
            .map { it.key }
            .toSet(),
        now = now,
    ) ?: emptyList()
}
