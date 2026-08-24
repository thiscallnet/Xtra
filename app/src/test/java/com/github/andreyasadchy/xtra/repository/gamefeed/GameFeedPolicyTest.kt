package com.github.andreyasadchy.xtra.repository.gamefeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFeedPolicyTest {

    @Test
    fun keyNormalizesTagOrderAndCaseWithoutCredentials() {
        assertEquals(
            GameFeedKey("games:tags=english,rpg"),
            GameFeedKey.top(listOf("RPG", "English", "rpg")),
        )
    }

    @Test
    fun freshCategoryFeedSkipsAutomaticRefresh() {
        val now = 10_000L
        assertEquals(
            GameRefreshDecision.SKIP_FRESH,
            gameRefreshDecision(
                nowMs = now,
                lastSuccessAt = now - GameFeedFreshnessPolicy.SOFT_TTL_MS + 1,
                lastAttemptAt = now - 30_000L,
                failureBackoffUntil = null,
                rateLimitUntil = null,
                force = false,
            ),
        )
    }

    @Test
    fun manualRefreshBypassesFreshnessButNotRateLimit() {
        val now = 10_000L
        assertEquals(
            GameRefreshDecision.REFRESH,
            gameRefreshDecision(now, now, now, null, null, force = true),
        )
        assertEquals(
            GameRefreshDecision.SKIP_BACKOFF,
            gameRefreshDecision(now, now, now, null, now + 1_000L, force = true),
        )
    }

    @Test
    fun foregroundThresholdMatchesCategoryCadence() {
        assertFalse(GameFeedFreshnessPolicy.meaningfulForeground(GameFeedFreshnessPolicy.FOREGROUND_THRESHOLD_MS - 1))
        assertTrue(GameFeedFreshnessPolicy.meaningfulForeground(GameFeedFreshnessPolicy.FOREGROUND_THRESHOLD_MS))
    }
}
