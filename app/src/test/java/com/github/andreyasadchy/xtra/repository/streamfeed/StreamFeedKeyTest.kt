package com.github.andreyasadchy.xtra.repository.streamfeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StreamFeedKeyTest {

    @Test
    fun equivalentFilterOrderingProducesOneKey() {
        assertEquals(
            StreamFeedKey.top("VIEWER_COUNT", listOf("FPS", "ENGLISH", "fps"), listOf("SK", "EN")),
            StreamFeedKey.top("viewer_count", listOf("english", "fps"), listOf("en", "sk")),
        )
    }

    @Test
    fun accountScopesNeverShareFollowedKey() {
        assertNotEquals(StreamFeedKey.followed("100"), StreamFeedKey.followed("200"))
        assertNotEquals(StreamFeedKey.followed("100"), StreamFeedKey.followed(null))
    }

    @Test
    fun credentialsAreNotPartOfKey() {
        assertEquals(StreamFeedKey.followed("100"), StreamFeedKey.followed(" 100 "))
        assertEquals("followed:account:100", StreamFeedKey.followed("100").value)
    }

    @Test
    fun gameIdentityAndFiltersCannotCollide() {
        val first = StreamFeedKey.game(
            gameId = "1",
            gameSlug = "same-slug",
            gameName = "Same name",
            sort = "VIEWER_COUNT",
            tags = listOf("fps"),
            languages = listOf("en"),
        )
        val second = StreamFeedKey.game(
            gameId = "2",
            gameSlug = "same-slug",
            gameName = "Same name",
            sort = "VIEWER_COUNT",
            tags = listOf("fps"),
            languages = listOf("en"),
        )
        val differentFilters = StreamFeedKey.game(
            gameId = "1",
            gameSlug = "same-slug",
            gameName = "Same name",
            sort = "VIEWER_COUNT",
            tags = listOf("rpg"),
            languages = listOf("en"),
        )

        assertNotEquals(first, second)
        assertNotEquals(first, differentFilters)
    }
}
