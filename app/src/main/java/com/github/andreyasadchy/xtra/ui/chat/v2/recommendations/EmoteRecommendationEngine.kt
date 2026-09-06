package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import com.github.andreyasadchy.xtra.model.chat.EmoteUsage
import com.github.andreyasadchy.xtra.repository.EmoteUsageIncrement
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatAssetProvider
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogEmote
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatEmoteScope
import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.viewerSendableValues
import java.util.Locale

data class EmoteRecommendationCatalog(
    val emotes: List<ChatCatalogEmote>,
)

data class EmoteRecommendationState(
    val viewerId: String,
    val catalog: EmoteRecommendationCatalog,
    val usage: List<EmoteUsage>,
)

data class EmoteRecommendation(
    val emote: ChatCatalogEmote,
    val match: FuzzyMatch,
    val useCount: Long,
    val lastUsedAt: Long,
)

object EmoteUsageKeys {
    const val ANONYMOUS_VIEWER_ID = "anonymous"

    fun normalizeViewerId(viewerId: String?): String =
        viewerId?.trim()?.takeIf(String::isNotEmpty) ?: ANONYMOUS_VIEWER_ID

    fun forEmote(
        emote: ChatCatalogEmote,
        channelId: String,
        viewerId: String,
    ): String {
        val usageChannel = channelIdFor(emote.scope, channelId)
        return listOf(normalizeViewerId(viewerId), emote.provider.name, emote.scope.name, usageChannel.orEmpty(), emote.id)
            .joinToString("|")
    }

    fun channelIdFor(scope: ChatEmoteScope, channelId: String): String? = when (scope) {
        ChatEmoteScope.CHANNEL, ChatEmoteScope.LEGACY_COMBINED -> channelId
        ChatEmoteScope.GLOBAL, ChatEmoteScope.PERSONAL -> null
    }
}

class EmoteRecommendationEngine(
    private val matcher: FuzzySubsequenceMatcher = FuzzySubsequenceMatcher(),
    private val maxResults: Int = 10,
) {
    fun catalog(snapshot: ChatCatalogSnapshot): EmoteRecommendationCatalog =
        EmoteRecommendationCatalog(
            emotes = sendableEmotes(snapshot),
        )

    fun recommend(
        query: String,
        channelId: String,
        catalog: EmoteRecommendationCatalog,
        usage: List<EmoteUsage>,
        viewerId: String,
    ): List<EmoteRecommendation> {
        if (query.isBlank()) return emptyList()
        val usageByKey = usage.associateBy(EmoteUsage::usageKey)
        return catalog.emotes.mapNotNull { emote ->
            val match = matcher.match(emote.name, query) ?: return@mapNotNull null
            val record = usageByKey[EmoteUsageKeys.forEmote(emote, channelId, viewerId)]
            EmoteRecommendation(
                emote = emote,
                match = match,
                useCount = record?.useCount ?: 0L,
                lastUsedAt = record?.lastUsedAt ?: 0L,
            )
        }.sortedWith(
            compareByDescending<EmoteRecommendation> { it.useCount }
                .thenByDescending { it.match.score }
                .thenByDescending { it.lastUsedAt }
                .thenBy { it.emote.name.lowercase(Locale.ROOT) }
                .thenBy { it.emote.provider.name }
                .thenBy { it.emote.id },
        ).take(maxResults)
    }

    /** Resolves exactly what a whitespace-delimited sent token can refer to in this catalog. */
    fun resolveSentToken(snapshot: ChatCatalogSnapshot, token: String): ChatCatalogEmote? {
        if (token.isEmpty()) return null
        return sendableEmotes(snapshot).firstOrNull { it.name == token }
    }

    fun usageInMessage(
        snapshot: ChatCatalogSnapshot,
        message: CharSequence,
        channelId: String,
        usedAt: Long,
        viewerId: String,
    ): List<EmoteUsageIncrement> {
        val increments = LinkedHashMap<String, EmoteUsageIncrement>()
        message.toString().trim().split(WHITESPACE).filter(String::isNotEmpty).forEach { token ->
            val emote = resolveSentToken(snapshot, token) ?: return@forEach
            val key = EmoteUsageKeys.forEmote(emote, channelId, viewerId)
            val previous = increments[key]
            increments[key] = if (previous == null) {
                EmoteUsageIncrement(
                    viewerId = EmoteUsageKeys.normalizeViewerId(viewerId),
                    usageKey = key,
                    provider = emote.provider.name,
                    emoteId = emote.id,
                    scope = emote.scope.name,
                    channelId = EmoteUsageKeys.channelIdFor(emote.scope, channelId),
                    count = 1,
                    lastUsedAt = usedAt,
                )
            } else {
                previous.copy(count = previous.count + 1, lastUsedAt = usedAt)
            }
        }
        return increments.values.toList()
    }

    private fun sendableEmotes(snapshot: ChatCatalogSnapshot): List<ChatCatalogEmote> =
        snapshot.viewerSendableValues()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
