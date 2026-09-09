package com.github.andreyasadchy.xtra.ui.chat.v2.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAssetSpecTest {
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
