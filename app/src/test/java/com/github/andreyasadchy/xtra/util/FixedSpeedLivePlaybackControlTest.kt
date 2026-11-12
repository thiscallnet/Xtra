package com.github.andreyasadchy.xtra.util

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import org.junit.Assert.assertEquals
import org.junit.Test

class FixedSpeedLivePlaybackControlTest {

    @Test
    fun fixedSpeedKeepsTheConfiguredLiveTargetAvailableToLoadControl() {
        val control = FixedSpeedLivePlaybackControl(DefaultLivePlaybackSpeedControl.Builder().build())
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
    fun media3SeekOverrideTakesPriorityAndThenRestoresTheSelectedTarget() {
        val control = FixedSpeedLivePlaybackControl(DefaultLivePlaybackSpeedControl.Builder().build())
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
