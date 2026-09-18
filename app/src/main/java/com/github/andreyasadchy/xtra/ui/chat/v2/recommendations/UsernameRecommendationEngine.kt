package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import java.util.Locale

data class ChatUserSuggestion(
    val userId: String?,
    val login: String,
    val displayName: String,
    val profileImageUrl: String? = null,
    val messageCount: Int = 0,
    val lastSeenAt: Long = 0L,
)

data class UsernameRecommendation(
    val user: ChatUserSuggestion,
    val match: FuzzyMatch?,
)

/** Ranks users who have appeared in the active chat using the same fuzzy matching as emotes. */
class UsernameRecommendationEngine(
    private val matcher: FuzzySubsequenceMatcher = FuzzySubsequenceMatcher(),
    private val maxResults: Int = 8,
) {
    fun recommend(query: String, users: List<ChatUserSuggestion>): List<UsernameRecommendation> {
        val normalizedQuery = query.trim().removePrefix("@").lowercase(Locale.ROOT)
        return users.asSequence()
            .mapNotNull { user ->
                val match = if (normalizedQuery.isBlank()) {
                    null
                } else {
                    sequenceOf(user.login, user.displayName)
                        .distinct()
                        .mapNotNull { matcher.match(it, normalizedQuery) }
                        .maxByOrNull(FuzzyMatch::score)
                        ?: return@mapNotNull null
                }
                UsernameRecommendation(user, match)
            }
            .sortedWith(
                compareBy<UsernameRecommendation> { recommendationGroup(it.match) }
                    .thenByDescending { it.user.messageCount }
                    .thenByDescending { it.match?.score ?: 0 }
                    .thenByDescending { it.user.lastSeenAt }
                    .thenBy { it.user.login.lowercase(Locale.ROOT) },
            )
            .take(maxResults)
            .toList()
    }

    private fun recommendationGroup(match: FuzzyMatch?): Int = when {
        match == null -> 1
        match.score >= 800_000 -> 0
        else -> 2
    }
}
