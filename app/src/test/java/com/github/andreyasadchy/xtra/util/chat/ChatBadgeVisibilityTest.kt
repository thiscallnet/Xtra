package com.github.andreyasadchy.xtra.util.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBadgeVisibilityTest {

    @Test
    fun `hiding badges removes both badge kinds but keeps stv metadata for paints and personal emotes`() {
        listOf(
            true to false,
            false to true,
        ).forEach { (showNamePaints, showPersonalEmotes) ->
            val visibility = chatBadgeVisibility(
                showBadges = false,
                showStvBadges = true,
                showNamePaints = showNamePaints,
                showPersonalEmotes = showPersonalEmotes,
            )

            assertFalse(visibility.showTwitchBadges)
            assertFalse(visibility.showStvBadges)
            assertTrue(visibility.loadStvUser)
        }
    }
}
