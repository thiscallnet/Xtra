package com.github.andreyasadchy.xtra.ui.notifications

import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchNotification
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchNotificationsViewModelTest {

    @Test
    fun backgroundRefreshDoesNotPretendReadBeforeServerConfirmsIt() {
        val item = notification(isUnread = true)

        val refreshed = applyLocalNotificationChanges(
            items = listOf(item),
            locallyDismissedIds = emptySet(),
        )

        assertTrue(refreshed.single().isUnread)
    }

    @Test
    fun backgroundRefreshPreservesLocalDismissal() {
        val item = notification(isUnread = true)

        val refreshed = applyLocalNotificationChanges(
            items = listOf(item),
            locallyDismissedIds = setOf(item.id),
        )

        assertTrue(refreshed.isEmpty())
    }

    private fun notification(isUnread: Boolean) = TwitchNotification(
        id = "notification-1",
        type = "SUBSCRIPTION",
        title = "A sub",
        body = "Someone subscribed",
        createdAt = null,
        imageUrl = null,
        isUnread = isUnread,
        canDismiss = true,
        action = null,
    )
}
