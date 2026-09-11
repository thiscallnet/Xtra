package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.graphql.fragment.UserMessageClickedUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageClickedFollowedAtTest {
    @Test
    fun `uses clicked chatter to channel date rather than viewer to chatter date`() {
        assertEquals(
            "2025-02-03T04:05:06Z",
            messageClickedFollowedAt(
                user(
                    clickedUserToChannelAt = "2025-02-03T04:05:06Z",
                    viewerToClickedUserAt = "2024-01-02T03:04:05Z",
                ),
            ),
        )
    }

    @Test
    fun `does not invent a channel follow date from the viewer relation`() {
        assertNull(
            messageClickedFollowedAt(
                user(
                    clickedUserToChannelAt = null,
                    viewerToClickedUserAt = "2024-01-02T03:04:05Z",
                ),
            ),
        )
    }

    @Test
    fun `viewer follow relation remains separate from chatter follow date`() {
        val user = user(
            clickedUserToChannelAt = "2020-01-02T03:04:05Z",
            viewerToClickedUserAt = "2025-02-03T04:05:06Z",
        )

        assertEquals("2020-01-02T03:04:05Z", messageClickedFollowedAt(user))
        assertTrue(user.self?.follower != null)
    }

    @Test
    fun `cache key prefers channel id and falls back to normalized login`() {
        assertEquals("id:123", messageClickedChannelCacheKey(" 123 ", "ChannelName"))
        assertEquals("login:channelname", messageClickedChannelCacheKey(null, " ChannelName "))
        assertNull(messageClickedChannelCacheKey(null, "  "))
    }

    private fun user(
        clickedUserToChannelAt: String?,
        viewerToClickedUserAt: String?,
    ) = UserMessageClickedUser(
        bannerImageURL = null,
        createdAt = null,
        displayName = "Chatter",
        follow = clickedUserToChannelAt?.let(UserMessageClickedUser::Follow),
        lastBroadcast = null,
        displayBadges = null,
        relationship = null,
        roles = null,
        self = UserMessageClickedUser.Self(
            canFollow = true,
            follower = viewerToClickedUserAt?.let(UserMessageClickedUser::Follower),
        ),
        id = "chatter-id",
        login = "chatter",
        profileImageURL = null,
    )
}
