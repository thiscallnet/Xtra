package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.Tag
import com.github.andreyasadchy.xtra.model.ui.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMetadataFallbacksTest {

    @Test
    fun `failed stream request preserves cached live stream and allows user refresh`() {
        val cached = ChannelPageCacheSnapshot(
            user = User(id = "42", login = "channel", name = "Old name"),
            stream = Stream(id = "stream", channelId = "42", title = "Still live"),
        )

        val resolution = resolveChannelFallback(
            cached = cached,
            streamResult = Result.failure(IllegalStateException("temporary failure")),
            userResult = Result.success(User(id = "42", login = "channel", name = "New name")),
        )

        assertEquals("New name", resolution.snapshot?.user?.name)
        assertEquals("stream", resolution.snapshot?.stream?.id)
        assertTrue(resolution.shouldPersist)
    }

    @Test
    fun `successful empty stream response confirms offline and can be persisted`() {
        val resolution = resolveChannelFallback(
            cached = ChannelPageCacheSnapshot(
                user = User(id = "42", login = "channel", name = "Channel"),
                stream = Stream(id = "stream", channelId = "42"),
            ),
            streamResult = Result.success(null),
            userResult = Result.success(null),
        )

        assertNotNull(resolution.snapshot)
        assertNull(resolution.snapshot?.stream)
        assertTrue(resolution.shouldPersist)
    }

    @Test
    fun `failed stream request without cache does not persist an offline snapshot`() {
        val resolution = resolveChannelFallback(
            cached = null,
            streamResult = Result.failure(IllegalStateException("temporary failure")),
            userResult = Result.success(User(id = "42", login = "channel", name = "Channel")),
        )

        assertNotNull(resolution.snapshot)
        assertNull(resolution.snapshot?.stream)
        assertFalse(resolution.shouldPersist)
    }

    @Test
    fun `Helix game fallback retains rich cached metadata`() {
        val cached = Game(
            id = "7",
            slug = "rich-game",
            name = "Rich Game",
            boxArtURL = "cached-box-art",
            viewerCount = 120,
            broadcasterCount = 8,
            followerCount = 900,
            tags = listOf(Tag(id = "tag", name = "Tag")),
        )
        val helixGame = Game(id = "7", name = "Updated Game", boxArtURL = "fresh-box-art")

        val merged = mergeGameFallback(cached, helixGame)

        assertEquals("7", merged.id)
        assertEquals("rich-game", merged.slug)
        assertEquals("Updated Game", merged.name)
        assertEquals("fresh-box-art", merged.boxArtURL)
        assertEquals(120, merged.viewerCount)
        assertEquals(8, merged.broadcasterCount)
        assertEquals(900, merged.followerCount)
        assertEquals("Tag", merged.tags?.single()?.name)
    }
}
