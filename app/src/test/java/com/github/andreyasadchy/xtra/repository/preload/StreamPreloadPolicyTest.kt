package com.github.andreyasadchy.xtra.repository.preload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreloadPolicyTest {
    @Test
    fun rankPrefersVisibleCardsNearTheCenter() {
        val centered = candidate("center", visible = 0.8f, proximity = 1f)
        val edge = candidate("edge", visible = 1f, proximity = 0f)

        assertEquals(listOf("center", "edge"), StreamPreloadPolicy.rank(listOf(edge, centered)).map { it.streamKey })
    }

    @Test
    fun rankDeduplicatesAChannelAcrossShelves() {
        val weaker = candidate("live-copy", login = "Creator", visible = 0.4f, proximity = 0.4f)
        val stronger = candidate("recommended-copy", login = "creator", visible = 0.9f, proximity = 0.9f)

        val ranked = StreamPreloadPolicy.rank(listOf(weaker, stronger))

        assertEquals(1, ranked.size)
        assertEquals("recommended-copy", ranked.single().streamKey)
    }

    @Test
    fun scoreIsBoundedForInvalidViewportValues() {
        val score = StreamPreloadPolicy.score(candidate("invalid", visible = 2f, proximity = -1f))

        assertTrue(score in 0f..1f)
    }

    @Test
    fun browsingUrlWarmupIsBoundedToTwoCandidates() {
        assertEquals(2, StreamPreloadPolicy.MAX_URL_CANDIDATES)
    }

    @Test
    fun customStreamProxyDisablesTwitchUrlPreload() {
        assertTrue(!StreamPreloadPolicy.allowsTwitchUrlPreload(true, "https://proxy/\$channel"))
        assertTrue(StreamPreloadPolicy.allowsTwitchUrlPreload(true, ""))
        assertTrue(StreamPreloadPolicy.allowsTwitchUrlPreload(false, "https://proxy/\$channel"))
    }

    private fun candidate(
        key: String,
        login: String = key,
        visible: Float,
        proximity: Float,
    ) = StreamPreloadCandidate(key, login, visible, proximity)
}
