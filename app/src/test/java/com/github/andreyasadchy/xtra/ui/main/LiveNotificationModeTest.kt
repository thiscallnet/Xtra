package com.github.andreyasadchy.xtra.ui.main

import com.github.andreyasadchy.xtra.util.C
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveNotificationModeTest {

    @Test
    fun migratesLegacyRealtimeToFast() {
        assertEquals(
            C.LIVE_NOTIFICATIONS_MODE_FAST,
            LiveNotificationScheduler.normalizeMode(C.LIVE_NOTIFICATIONS_MODE_REALTIME),
        )
    }

    @Test
    fun defaultsUnknownValuesToBatterySaving() {
        assertEquals(
            C.LIVE_NOTIFICATIONS_MODE_BATTERY,
            LiveNotificationScheduler.normalizeMode("unknown"),
        )
        assertEquals(
            C.LIVE_NOTIFICATIONS_MODE_BATTERY,
            LiveNotificationScheduler.normalizeMode(null),
        )
    }

    @Test
    fun preservesFastAndPersistentModes() {
        assertEquals(
            C.LIVE_NOTIFICATIONS_MODE_FAST,
            LiveNotificationScheduler.normalizeMode(C.LIVE_NOTIFICATIONS_MODE_FAST),
        )
        assertEquals(
            C.LIVE_NOTIFICATIONS_MODE_PERSISTENT,
            LiveNotificationScheduler.normalizeMode(C.LIVE_NOTIFICATIONS_MODE_PERSISTENT),
        )
    }
}
