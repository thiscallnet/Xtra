package com.github.andreyasadchy.xtra.ui.chat.v2.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAssetSpecTest {
    @Test
    fun `native twitch dimensions are shared and upgraded after decode`() {
        ChatAssetDimensionsResolver.clearForTests()
        try {
            val fallback = twitchEmoteAssetSpec("native")
            assertEquals(56, fallback.sourceWidth)
            assertEquals(56, fallback.sourceHeight)
            assertEquals(false, fallback.dimensionsAreAuthoritative)

            ChatAssetDimensionsResolver.recordDecoded(twitchEmoteAssetKey("native"), 112, 56)
            val resolved = twitchEmoteAssetSpec("native")
            assertEquals(112, resolved.sourceWidth)
            assertEquals(56, resolved.sourceHeight)
            assertEquals(56, resolved.computedWidth)
        } finally {
            ChatAssetDimensionsResolver.clearForTests()
        }
    }

    @Test
    fun `normal aspect ratio is preserved`() {
        val spec = ChatAssetSpec(ChatAssetKey("normal"), sourceWidth = 200, sourceHeight = 100, targetHeight = 28)

        assertEquals(56, spec.computedWidth)
    }

    @Test
    fun `extreme provider aspect ratio is capped for inline layout`() {
        val spec = ChatAssetSpec(ChatAssetKey("malformed"), sourceWidth = 9_000, sourceHeight = 1, targetHeight = 28)

        assertEquals(112, spec.computedWidth)
    }
}
