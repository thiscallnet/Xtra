package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.Badge
import org.junit.Assert.assertNull
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

    @Test
    fun `lead moderator role wins over moderator and non-role badges`() {
        val leadModerator = Badge("lead_moderator", "1", imageUrl = "https://twitch.tv/lead-moderator")
        val moderator = Badge("moderator", "1", imageUrl = "https://twitch.tv/moderator")
        val vip = Badge("vip", "1", imageUrl = "https://twitch.tv/vip")

        assertSame(
            leadModerator,
            highestPinnedChatRoleBadge(listOf(vip, moderator, leadModerator)),
        )
    }

    @Test
    fun `non-pinner badges are not treated as role badges`() {
        assertNull(
            highestPinnedChatRoleBadge(
                listOf(
                    Badge("vip", "1"),
                    Badge("subscriber", "12"),
                    Badge("partner", "1"),
                ),
            ),
        )
    }
}
