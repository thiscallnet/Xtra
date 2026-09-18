package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import org.junit.Assert.assertEquals
import org.junit.Test

class UsernameRecommendationEngineTest {
    private val engine = UsernameRecommendationEngine(maxResults = 8)

    @Test
    fun `blank mention favors active users and query matches login or display name`() {
        val users = listOf(
            user("quiet_viewer", "QuietViewer", count = 2, lastSeen = 40),
            user("gamer_42", "Gamer", count = 12, lastSeen = 20),
            user("gareth", "Gareth", count = 4, lastSeen = 50),
        )

        assertEquals(
            listOf("gamer_42", "gareth", "quiet_viewer"),
            engine.recommend("@", users).map { it.user.login },
        )
        assertEquals(
            listOf("gareth"),
            engine.recommend("@gare", users).map { it.user.login },
        )
    }

    @Test
    fun `exact and prefix matches outrank a more active fuzzy match`() {
        val users = listOf(
            user("very_active_gamer", "VeryActiveGamer", count = 100),
            user("gamer", "Gamer", count = 1),
        )

        assertEquals(
            listOf("gamer", "very_active_gamer"),
            engine.recommend("@gamer", users).map { it.user.login },
        )
    }

    private fun user(login: String, displayName: String, count: Int, lastSeen: Long = 0L) =
        ChatUserSuggestion(
            userId = login,
            login = login,
            displayName = displayName,
            messageCount = count,
            lastSeenAt = lastSeen,
        )
}
