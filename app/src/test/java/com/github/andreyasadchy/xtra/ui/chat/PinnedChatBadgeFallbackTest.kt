package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedChatBadgeFallbackTest {

    @Test
    fun `only Twitch authority badges have local fallbacks`() {
        assertEquals(R.drawable.ic_moderator_badge, pinnedChatBadgeFallbackResource("moderator"))
        assertEquals(R.drawable.ic_broadcaster_badge, pinnedChatBadgeFallbackResource("broadcaster"))
        assertNull(pinnedChatBadgeFallbackResource("subscriber"))
        assertNull(pinnedChatBadgeFallbackResource("unknown"))
    }
}
