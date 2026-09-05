package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadge
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadgeKey
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityData
import com.github.andreyasadchy.xtra.model.chat.isSubscriptionSlotBadge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.intOrNull

/**
 * Full-document operations verified against Twitch's current web Chat Identity chunks.
 * Keep these documents separate from the generated Apollo schema: Twitch's web-only
 * identity surface is not part of that checked-in schema.
 *
 * currentUser.selectedBadge is the global preference. The channel self connection
 * supplies the per-channel preference and the badges Twitch makes available there.
 */
internal const val CHAT_IDENTITY_QUERY = """
    query ChatIdentityQuery(${'$'}channelLogin: String!) {
      channel: user(login: ${'$'}channelLogin) {
        id
        self {
          selectedBadge {
            setID
            version
            title
            description
            imageURL(size: QUADRUPLE)
          }
          availableBadges {
            setID
            version
            title
            description
            imageURL(size: QUADRUPLE)
          }
          displayBadges {
            setID
            version
            title
            description
            imageURL(size: QUADRUPLE)
          }
        }
      }
      currentUser {
        id
        login
        displayName
        chatColor
        hasPrime
        turboStatus {
          hasActiveTurbo
        }
        selectedBadge {
          setID
          version
          title
          description
          imageURL(size: QUADRUPLE)
        }
        availableBadges {
          setID
          version
          title
          description
          imageURL(size: QUADRUPLE)
        }
      }
    }
"""

internal const val CHAT_EARNED_BADGES_CHANNEL_DATA_QUERY = """
    query Chat_EarnedBadges_ChannelData(${'$'}channelID: ID!) {
      currentChannelViewer(channelID: ${'$'}channelID) {
        id
        earnedBadges {
          setID
          version
          title
          description
          imageURL(size: QUADRUPLE)
        }
      }
    }
"""

internal const val CHAT_EARNED_BADGES_INITIAL_SUB_STATUS_QUERY = """
    query Chat_EarnedBadges_InitialSubStatus(${'$'}channelLogin: String!) {
      user(login: ${'$'}channelLogin) {
        id
        self {
          subscriptionBenefit {
            id
          }
          subscriptionTenure(tenureMethod: CUMULATIVE) {
            months
          }
        }
      }
    }
"""

internal const val CHAT_EARNED_BADGES_INITIAL_SUB_STATUS_LEGACY_QUERY = """
    query Chat_EarnedBadges_InitialSubStatus(${'$'}channelLogin: String!) {
      user(login: ${'$'}channelLogin) {
        id
        self {
          subscriptionBenefit {
            id
          }
        }
      }
    }
"""

internal const val CHAT_UPDATE_COLOR_V2_MUTATION = """
    mutation Chat_UpdateChatColorV2(${'$'}input: UpdateChatColorV2Input!) {
      updateChatColorV2(input: ${'$'}input) {
        user {
          id
          chatColor
        }
        error {
          code
        }
      }
    }
"""

internal const val CHAT_SELECT_GLOBAL_BADGE_MUTATION = """
    mutation SelectGlobalBadge(${'$'}input: SelectGlobalBadgeInput!) {
      selectGlobalBadge(input: ${'$'}input) {
        isSuccessful
        user {
          id
          selectedBadge {
            setID
            version
          }
        }
      }
    }
"""

internal const val CHAT_DESELECT_GLOBAL_BADGE_MUTATION = """
    mutation DeselectGlobalBadge {
      deselectGlobalBadge {
        user {
          id
          selectedBadge {
            setID
            version
          }
        }
      }
    }
"""

internal const val CHAT_SELECT_CHANNEL_BADGE_MUTATION = """
    mutation SelectChannelBadge(${'$'}input: SelectChannelBadgeInput!) {
      selectChannelBadge(input: ${'$'}input) {
        isSuccessful
        user {
          id
          selectedBadge {
            setID
            version
          }
        }
      }
    }
"""

internal const val CHAT_DESELECT_CHANNEL_BADGE_MUTATION = """
    mutation DeselectChannelBadge(${'$'}input: DeselectChannelBadgeInput!) {
      deselectChannelBadge(input: ${'$'}input) {
        user {
          id
          selectedBadge {
            setID
            version
          }
        }
      }
    }
"""

class ChatIdentityGraphQlException(message: String) : IllegalStateException(message)

suspend fun GraphQLRepository.loadChatIdentity(
    networkLibrary: String?,
    headers: Map<String, String>,
    viewerId: String,
    channelId: String,
    channelLogin: String,
): ChatIdentityData {
    val identity = executeRawOperation(
        networkLibrary = networkLibrary,
        headers = headers,
        operationName = "ChatIdentityQuery",
        query = CHAT_IDENTITY_QUERY,
        variables = buildJsonObject { put("channelLogin", channelLogin) },
    ).dataOrThrow("ChatIdentityQuery")

    val currentUser = identity.objectOrNull("currentUser")
        ?: throw ChatIdentityGraphQlException("Chat Identity did not return the logged-in user")
    val returnedViewerId = currentUser.stringOrNull("id")
    if (returnedViewerId.isNullOrBlank() || returnedViewerId != viewerId) {
        throw ChatIdentityGraphQlException("Chat Identity returned an unexpected viewer")
    }

    val displayBadges = ChatIdentityCampaignParser.parseDisplayBadges(identity)
    val channelSelf = identity.objectOrNull("channel")?.objectOrNull("self")
    val availableGlobalBadges = currentUser.arrayOrEmpty("availableBadges")
        .mapNotNull(::parseChatIdentityBadge)
    val selectedGlobalBadge = currentUser.objectOrNull("selectedBadge")
        ?.let(::parseChatIdentityBadge)
    val selectedChannelBadge = channelSelf?.objectOrNull("selectedBadge")
        ?.let(::parseChatIdentityBadge)
    val availableChannelBadges = channelSelf?.arrayOrEmpty("availableBadges")
        ?.mapNotNull(::parseChatIdentityBadge)
        .orEmpty()

    val earned = executeRawOperation(
        networkLibrary = networkLibrary,
        headers = headers,
        operationName = "Chat_EarnedBadges_ChannelData",
        query = CHAT_EARNED_BADGES_CHANNEL_DATA_QUERY,
        variables = buildJsonObject { put("channelID", channelId) },
    ).dataOrThrow("Chat_EarnedBadges_ChannelData")
        .objectOrNull("currentChannelViewer")
        ?.arrayOrEmpty("earnedBadges")
        ?.mapNotNull(::parseChatIdentityBadge)
        .orEmpty()

    val subscription = loadSubscriptionStatus(
        networkLibrary = networkLibrary,
        headers = headers,
        channelLogin = channelLogin,
    )
    val displayedSubscriptionBadge = displayBadges.firstOrNull { it.isSubscriptionSlotBadge() }
    val subscriptionBadge = displayedSubscriptionBadge
        ?: earned.firstOrNull { it.isSubscriptionSlotBadge() }

    return ChatIdentityData(
        displayName = currentUser.stringOrNull("displayName")
            ?: currentUser.stringOrNull("login").orEmpty(),
        nameColor = currentUser.stringOrNull("chatColor"),
        displayBadges = displayBadges,
        availableGlobalBadges = availableGlobalBadges,
        selectedGlobalBadge = selectedGlobalBadge?.takeUnless { it.isSubscriptionSlotBadge() },
        subscriberBadge = subscriptionBadge,
        channelBadges = (availableChannelBadges + listOfNotNull(selectedChannelBadge) + earned)
            .filterNot { it.isSubscriptionSlotBadge() }
            .distinctBy { it.key },
        selectedChannelBadge = selectedChannelBadge?.takeUnless { it.isSubscriptionSlotBadge() },
        isSubscribed = subscription?.isSubscribed == true || displayedSubscriptionBadge != null,
        canUseCustomNameColor = currentUser.booleanOrNull("hasPrime") == true ||
            currentUser.objectOrNull("turboStatus")?.booleanOrNull("hasActiveTurbo") == true,
        subscriptionMonths = subscription?.months,
    )
}

private data class SubscriptionStatus(
    val isSubscribed: Boolean,
    val months: Int?,
)

private suspend fun GraphQLRepository.loadSubscriptionStatus(
    networkLibrary: String?,
    headers: Map<String, String>,
    channelLogin: String,
): SubscriptionStatus? {
    val result = try {
        executeRawOperation(
            networkLibrary = networkLibrary,
            headers = headers,
            operationName = "Chat_EarnedBadges_InitialSubStatus",
            query = CHAT_EARNED_BADGES_INITIAL_SUB_STATUS_QUERY,
            variables = buildJsonObject { put("channelLogin", channelLogin) },
        ).dataOrThrow("Chat_EarnedBadges_InitialSubStatus")
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // subscriptionTenure is optional. Retry the stable subscription-only query when Twitch
        // removes or changes that field, so a tenure schema change cannot hide Chat Identity.
        try {
            executeRawOperation(
                networkLibrary = networkLibrary,
                headers = headers,
                operationName = "Chat_EarnedBadges_InitialSubStatus",
                query = CHAT_EARNED_BADGES_INITIAL_SUB_STATUS_LEGACY_QUERY,
                variables = buildJsonObject { put("channelLogin", channelLogin) },
            ).dataOrThrow("Chat_EarnedBadges_InitialSubStatus")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
    }
    val self = result.objectOrNull("user")?.objectOrNull("self")
    return SubscriptionStatus(
        isSubscribed = self?.objectOrNull("subscriptionBenefit") != null,
        months = self?.objectOrNull("subscriptionTenure")
            ?.intOrNull("months")
            ?.takeIf { it > 0 },
    )
}

suspend fun GraphQLRepository.setGlobalChatBadge(
    networkLibrary: String?,
    headers: Map<String, String>,
    badge: ChatIdentityBadgeKey?,
) {
    if (badge == null) {
        executeRawOperation(
            networkLibrary = networkLibrary,
            headers = headers,
            operationName = "DeselectGlobalBadge",
            query = CHAT_DESELECT_GLOBAL_BADGE_MUTATION,
        ).dataOrThrow("DeselectGlobalBadge")
            .requireMutationPayload("DeselectGlobalBadge", "deselectGlobalBadge")
    } else {
        executeRawOperation(
            networkLibrary = networkLibrary,
            headers = headers,
            operationName = "SelectGlobalBadge",
            query = CHAT_SELECT_GLOBAL_BADGE_MUTATION,
            variables = buildJsonObject {
                putJsonObject("input") {
                    put("badgeSetID", badge.setId)
                    put("badgeSetVersion", badge.version)
                }
            },
        ).dataOrThrow("SelectGlobalBadge")
            .requireMutationPayload("SelectGlobalBadge", "selectGlobalBadge")
    }
}

suspend fun GraphQLRepository.setChannelChatBadge(
    networkLibrary: String?,
    headers: Map<String, String>,
    channelId: String,
    badge: ChatIdentityBadgeKey?,
) {
    if (badge == null) {
        executeRawOperation(
            networkLibrary = networkLibrary,
            headers = headers,
            operationName = "DeselectChannelBadge",
            query = CHAT_DESELECT_CHANNEL_BADGE_MUTATION,
            variables = buildJsonObject {
                putJsonObject("input") { put("channelID", channelId) }
            },
        ).dataOrThrow("DeselectChannelBadge")
            .requireMutationPayload("DeselectChannelBadge", "deselectChannelBadge")
    } else {
        executeRawOperation(
            networkLibrary = networkLibrary,
            headers = headers,
            operationName = "SelectChannelBadge",
            query = CHAT_SELECT_CHANNEL_BADGE_MUTATION,
            variables = buildJsonObject {
                putJsonObject("input") {
                    put("badgeSetID", badge.setId)
                    put("badgeSetVersion", badge.version)
                    put("channelID", channelId)
                }
            },
        ).dataOrThrow("SelectChannelBadge")
            .requireMutationPayload("SelectChannelBadge", "selectChannelBadge")
    }
}

suspend fun GraphQLRepository.setChatNameColor(
    networkLibrary: String?,
    headers: Map<String, String>,
    color: String,
) {
    val response = executeRawOperation(
        networkLibrary = networkLibrary,
        headers = headers,
        operationName = "Chat_UpdateChatColorV2",
        query = CHAT_UPDATE_COLOR_V2_MUTATION,
        variables = buildJsonObject {
            putJsonObject("input") {
                put("color", color)
            }
        },
    ).dataOrThrow("Chat_UpdateChatColorV2")

    val mutation = response.objectOrNull("updateChatColorV2")
        ?: throw ChatIdentityGraphQlException("Chat color update returned no result")
    mutation.objectOrNull("error")?.stringOrNull("code")?.let { code ->
        throw ChatIdentityGraphQlException("Twitch rejected the chat color update: $code")
    }
    if (mutation.objectOrNull("user")?.stringOrNull("chatColor").isNullOrBlank()) {
        throw ChatIdentityGraphQlException("Chat color update returned no color")
    }
}

private fun JsonObject.dataOrThrow(operationName: String): JsonObject {
    val errors = arrayOrEmpty("errors")
    if (errors.isNotEmpty()) {
        val message = errors.mapNotNull { it.jsonObjectOrNull()?.stringOrNull("message") }
            .joinToString("; ")
            .ifBlank { "Twitch returned a GraphQL error" }
        throw ChatIdentityGraphQlException("$operationName failed: $message")
    }
    return objectOrNull("data")
        ?: throw ChatIdentityGraphQlException("$operationName returned no data")
}

private fun JsonObject.requireMutationPayload(operationName: String, payloadName: String) {
    val payload = objectOrNull(payloadName)
        ?: throw ChatIdentityGraphQlException(
            "$operationName returned no result",
        )
    val isSuccessful = payload["isSuccessful"]?.jsonPrimitive?.booleanOrNull
    if (isSuccessful == false || (isSuccessful == null && payload.objectOrNull("user") == null)) {
        throw ChatIdentityGraphQlException("$operationName was not accepted")
    }
}

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull

private fun parseChatIdentityBadge(element: JsonElement): ChatIdentityBadge? {
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

private fun JsonObject.objectOrNull(name: String): JsonObject? =
    this[name]?.jsonObjectOrNull()

private fun JsonObject.arrayOrEmpty(name: String): JsonArray =
    this[name]?.jsonArrayOrNull() ?: JsonArray(emptyList())

private fun JsonElement.jsonObjectOrNull(): JsonObject? =
    takeUnless { it is JsonNull }?.let { runCatching { it.jsonObject }.getOrNull() }

private fun JsonElement.jsonArrayOrNull(): JsonArray? =
    takeUnless { it is JsonNull }?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.takeUnless { it is JsonNull }?.let {
        runCatching { it.jsonPrimitive.contentOrNull }.getOrNull()
    }

private fun JsonObject.intOrNull(name: String): Int? =
    this[name]?.takeUnless { it is JsonNull }?.let {
        runCatching { it.jsonPrimitive.intOrNull }.getOrNull()
    }
