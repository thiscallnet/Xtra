package com.github.andreyasadchy.xtra.ui.main

import com.github.andreyasadchy.xtra.util.C
import org.junit.Assert.assertEquals
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

    @Test
    fun onlyAnActiveRunnerWithAWorkingNetworkWakeSourceIsHealthy() {
        assertTrue(liveNotificationOwnerIsHealthy(runnerRunning = true, networkWakeAvailable = true))
        assertFalse(liveNotificationOwnerIsHealthy(runnerRunning = false, networkWakeAvailable = true))
        assertFalse(liveNotificationOwnerIsHealthy(runnerRunning = true, networkWakeAvailable = false))
    }

    @Test
    fun fastOwnerWakeAvoidsFallback() {
        var processWakeCalls = 0
        var fallbackCalls = 0

        LiveNotificationScheduler.routeImmediateReconciliation(
            mode = C.LIVE_NOTIFICATIONS_MODE_FAST,
            wakePersistentOwner = { error("Fast mode must not wake the persistent owner") },
            wakeProcessOwner = {
                processWakeCalls++
                true
            },
            enqueueFallback = { fallbackCalls++ },
        )

        assertEquals(1, processWakeCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun persistentOwnerWakeDoesNotStartACompetingProcessRunner() {
        var processWakeCalls = 0
        var fallbackCalls = 0

        LiveNotificationScheduler.routeImmediateReconciliation(
            mode = C.LIVE_NOTIFICATIONS_MODE_PERSISTENT,
            wakePersistentOwner = { true },
            wakeProcessOwner = {
                processWakeCalls++
                true
            },
            enqueueFallback = { fallbackCalls++ },
        )

        assertEquals(0, processWakeCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun stalePersistentAndFastOwnersUseWorkManagerFallback() {
        var persistentWakeCalls = 0
        var processWakeCalls = 0
        var fallbackCalls = 0

        LiveNotificationScheduler.routeImmediateReconciliation(
            mode = C.LIVE_NOTIFICATIONS_MODE_PERSISTENT,
            wakePersistentOwner = {
                persistentWakeCalls++
                false
            },
            wakeProcessOwner = {
                processWakeCalls++
                false
            },
            enqueueFallback = { fallbackCalls++ },
        )
        LiveNotificationScheduler.routeImmediateReconciliation(
            mode = C.LIVE_NOTIFICATIONS_MODE_FAST,
            wakePersistentOwner = { error("Fast mode must not wake the persistent owner") },
            wakeProcessOwner = { false },
            enqueueFallback = { fallbackCalls++ },
        )

        assertEquals(1, persistentWakeCalls)
        assertEquals(1, processWakeCalls)
        assertEquals(2, fallbackCalls)
    }

    @Test
    fun batteryModeUsesFallbackWithoutAnOwnerWake() {
        var ownerWakeCalls = 0
        var fallbackCalls = 0

        LiveNotificationScheduler.routeImmediateReconciliation(
            mode = C.LIVE_NOTIFICATIONS_MODE_BATTERY,
            wakePersistentOwner = {
                ownerWakeCalls++
                true
            },
            wakeProcessOwner = {
                ownerWakeCalls++
                true
            },
            enqueueFallback = { fallbackCalls++ },
        )

        assertEquals(0, ownerWakeCalls)
        assertEquals(1, fallbackCalls)
    }
}
