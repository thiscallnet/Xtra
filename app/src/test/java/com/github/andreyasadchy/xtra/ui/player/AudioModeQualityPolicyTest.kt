package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioModeQualityPolicyTest {

    @Test
    fun audioOnlyStartupWithoutPreviousQualityRestoresAutoVideoQuality() {
        val restoredQuality = resolveAudioModeRestoreQuality(
            previousQuality = null,
            qualities = listOf(
                VideoQuality(PlaybackContract.AUDIO_ONLY_QUALITY),
                VideoQuality(PlaybackContract.AUTO_QUALITY),
                VideoQuality("720p60"),
            ),
        )

        assertEquals(PlaybackContract.AUTO_QUALITY, restoredQuality?.name)
    }
}
