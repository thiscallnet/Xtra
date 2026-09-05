package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Badge
import org.junit.Assert.assertSame
import org.junit.Test

class PinnedChatBadgeRolesTest {

    @Test
    fun `broadcaster role wins over moderator and other badges`() {
        val broadcaster = Badge("broadcaster", "1", imageUrl = "https://twitch.tv/broadcaster")
        val moderator = Badge("moderator", "1", imageUrl = "https://twitch.tv/moderator")
        val subscriber = Badge("subscriber", "12", imageUrl = "https://twitch.tv/subscriber")

        assertSame(
            broadcaster,
            highestPinnedChatRoleBadge(listOf(subscriber, moderator, broadcaster)),
        )
    }
}
