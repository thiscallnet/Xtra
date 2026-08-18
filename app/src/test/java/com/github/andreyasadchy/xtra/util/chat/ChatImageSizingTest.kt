package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.ImageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatImageSizingTest {

    @Test
    fun `each inline image kind uses its own size`() {
        assertEquals(30, imageSizeForKind(ImageKind.EMOTE, emoteSize = 30, badgeSize = 40, inlineIconSize = 18))
        assertEquals(40, imageSizeForKind(ImageKind.BADGE, emoteSize = 30, badgeSize = 40, inlineIconSize = 18))
        assertEquals(18, imageSizeForKind(ImageKind.INLINE_ICON, emoteSize = 30, badgeSize = 40, inlineIconSize = 18))
    }
}
