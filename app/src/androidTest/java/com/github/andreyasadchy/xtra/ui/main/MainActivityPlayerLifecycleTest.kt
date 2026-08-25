package com.github.andreyasadchy.xtra.ui.main

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.XtraApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityPlayerLifecycleTest {
    @Test
    fun finishingAfterMinimizingPlaybackClearsActivePlayerState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as XtraApp
        val activity = instrumentation.startActivitySync(
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val coordinator = (activity.application as XtraApp).xtraModule.streamFeedRefreshCoordinator
        try {
            coordinator.playbackEntered()
            coordinator.playbackReturned(playerStillOpen = true)
            assertTrue(coordinator.isPlayerActive)

            instrumentation.runOnMainSync { activity.finish() }
            assertTrue("Activity did not enter finishing state", activity.isFinishing)
            val deadline = SystemClock.uptimeMillis() + 5_000L
            while (!activity.isDestroyed && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(50L)
            }
            assertTrue("Activity was not destroyed", activity.isDestroyed)
            assertFalse(coordinator.isPlayerActive)
        } finally {
            if (!activity.isFinishing) {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }
}
