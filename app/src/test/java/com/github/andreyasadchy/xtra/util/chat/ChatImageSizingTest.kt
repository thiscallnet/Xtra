package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.ImageKind
import com.github.andreyasadchy.xtra.model.chat.Image
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImageSizingTest {

    @Test
    fun `each inline image kind uses its own size`() {
        assertEquals(30, imageSizeForKind(ImageKind.EMOTE, emoteSize = 30, badgeSize = 40, inlineIconSize = 18))
        assertEquals(40, imageSizeForKind(ImageKind.BADGE, emoteSize = 30, badgeSize = 40, inlineIconSize = 18))
        assertEquals(18, imageSizeForKind(ImageKind.INLINE_ICON, emoteSize = 30, badgeSize = 40, inlineIconSize = 18))
    }

    @Test
    fun `known emote aspect ratio is reserved before decoding`() {
        val image = Image(
            sourceWidth = 200,
            sourceHeight = 100,
            kind = ImageKind.EMOTE,
            start = 0,
            end = 1,
        )

        assertEquals(64, imageGeometry(image, 32).widthPx)
        assertEquals(32, imageGeometry(image, 32).heightPx)
    }

    @Test
    fun `unknown image dimensions use deterministic square fallback`() {
        val image = Image(kind = ImageKind.EMOTE, start = 0, end = 1)

        assertEquals(32, imageGeometry(image, 32).widthPx)
        assertEquals(32, imageGeometry(image, 32).heightPx)
    }
}
