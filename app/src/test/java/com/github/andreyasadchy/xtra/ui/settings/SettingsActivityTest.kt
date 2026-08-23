package com.github.andreyasadchy.xtra.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsActivityTest {

    @Test
    fun healthyUpdateNotificationsNeedNoUserAction() {
        assertFalse(
            needsUpdateNotificationUserAction(
                permissionMissing = false,
                notificationsBlocked = false,
                updatesChannelBlocked = false,
            ),
        )
    }

    @Test
    fun eachBlockedUpdateNotificationPrerequisiteNeedsUserAction() {
        assertTrue(needsUpdateNotificationUserAction(true, false, false))
        assertTrue(needsUpdateNotificationUserAction(false, true, false))
        assertTrue(needsUpdateNotificationUserAction(false, false, true))
    }
}
