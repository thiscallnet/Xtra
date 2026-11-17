package com.github.andreyasadchy.xtra.util

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLivePlaybackSpeedControlTest {

    @Test
    fun fixedSpeedConfigurationKeepsTheConfiguredLiveTargetAvailableToLoadControl() {
        val control = AdaptiveLivePlaybackSpeedControl(DefaultLivePlaybackSpeedControl.Builder().build())
        control.setAdaptiveTargetLiveOffsetUs(2_000_000L)
        control.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(2_000L)
                .setMinOffsetMs(2_000L)
                .setMaxOffsetMs(2_000L)
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1f)
                .build(),
        )

        assertEquals(2_000_000L, control.getTargetLiveOffsetUs())
        assertEquals(1f, control.getAdjustedPlaybackSpeed(8_000_000L, 1_000_000L), 0f)
    }

    @Test
    fun configuredMaximumAllowsCatchUpSpeedWhileKeepingTheSelectedTarget() {
        val control = AdaptiveLivePlaybackSpeedControl(DefaultLivePlaybackSpeedControl.Builder().build())
        control.setAdaptiveTargetLiveOffsetUs(2_000_000L)
        control.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(2_000L)
                .setMinOffsetMs(2_000L)
                .setMaxOffsetMs(2_000L)
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1.2f)
                .build(),
        )

        assertEquals(2_000_000L, control.getTargetLiveOffsetUs())
        val speed = control.getAdjustedPlaybackSpeed(8_000_000L, 1_000_000L)
        assertTrue("Expected catch-up speed above 1.0x, got $speed", speed > 1f)
        assertTrue("Speed exceeded the configured maximum: $speed", speed <= 1.2f)
    }

    @Test
    fun media3SeekOverrideTakesPriorityAndThenRestoresTheSelectedTarget() {
        val control = AdaptiveLivePlaybackSpeedControl(DefaultLivePlaybackSpeedControl.Builder().build())
        control.setAdaptiveTargetLiveOffsetUs(2_000_000L)
        control.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(2_000L)
                .setMinOffsetMs(2_000L)
                .setMaxOffsetMs(2_000L)
                .setMinPlaybackSpeed(1f)
                .setMaxPlaybackSpeed(1f)
                .build(),
        )

        control.setTargetLiveOffsetOverrideUs(3_000_000L)
        assertEquals(3_000_000L, control.getTargetLiveOffsetUs())

        control.setTargetLiveOffsetOverrideUs(C.TIME_UNSET)
        assertEquals(2_000_000L, control.getTargetLiveOffsetUs())
    }
}
