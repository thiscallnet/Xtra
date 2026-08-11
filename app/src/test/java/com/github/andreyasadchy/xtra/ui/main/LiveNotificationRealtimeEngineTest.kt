package com.github.andreyasadchy.xtra.ui.main

import com.github.andreyasadchy.xtra.util.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationRealtimeEngineTest {

    @Test
    fun fastOwnerOnlyRunsInFastMode() {
        assertTrue(
            shouldContinueProcessRunner(
                owner = LiveNotificationProcessOwner.FAST,
                mode = C.LIVE_NOTIFICATIONS_MODE_FAST,
                notificationsEnabled = true,
                persistentServiceRunning = false,
            )
        )
        assertFalse(
            shouldContinueProcessRunner(
                owner = LiveNotificationProcessOwner.FAST,
                mode = C.LIVE_NOTIFICATIONS_MODE_PERSISTENT,
                notificationsEnabled = true,
                persistentServiceRunning = false,
            )
        )
    }

    @Test
    fun persistentFallbackRunsUntilTheServiceOwnsMonitoring() {
        assertTrue(
            shouldContinueProcessRunner(
                owner = LiveNotificationProcessOwner.PERSISTENT_FALLBACK,
                mode = C.LIVE_NOTIFICATIONS_MODE_PERSISTENT,
                notificationsEnabled = true,
                persistentServiceRunning = false,
            )
        )
        assertFalse(
            shouldContinueProcessRunner(
                owner = LiveNotificationProcessOwner.PERSISTENT_FALLBACK,
                mode = C.LIVE_NOTIFICATIONS_MODE_PERSISTENT,
                notificationsEnabled = true,
                persistentServiceRunning = true,
            )
        )
        assertFalse(
            shouldContinueProcessRunner(
                owner = LiveNotificationProcessOwner.PERSISTENT_FALLBACK,
                mode = C.LIVE_NOTIFICATIONS_MODE_FAST,
                notificationsEnabled = true,
                persistentServiceRunning = false,
            )
        )
    }
}
