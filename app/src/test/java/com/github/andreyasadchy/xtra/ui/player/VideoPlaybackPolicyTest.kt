package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlaybackPolicyTest {

    @Test
    fun activeNormalPlaybackDisablesVideoWhenBackgrounded() {
        assertTrue(policy())
    }

    @Test
    fun pipKeepsVideoEnabled() {
        assertFalse(policy(isInPictureInPicture = true))
    }

    @Test
    fun pausedEndedOrIdlePlaybackDoesNotDisableVideo() {
        assertFalse(policy(playWhenReady = false))
        assertFalse(policy(playbackState = Player.STATE_ENDED))
        assertFalse(policy(playbackState = Player.STATE_IDLE))
        assertFalse(policy(hasMediaItem = false))
    }

    @Test
    fun intentionalVideoSuppressionKeepsOwnershipWithItsExistingState() {
        assertFalse(policy(audioOnly = true))
        assertFalse(policy(chatOnly = true))
        assertFalse(policy(videoAlreadySuppressed = true))
        assertFalse(policy(backgroundPlaybackEnabled = false))
    }

    @Test
    fun backgroundOwnedVideoDisableIsRestoredOnlyForNormalForegroundPlayback() {
        assertTrue(restorePolicy(backgroundOwnedVideoDisable = true))
        assertFalse(restorePolicy(backgroundOwnedVideoDisable = false))
        assertFalse(restorePolicy(backgroundOwnedVideoDisable = true, audioOnly = true))
        assertFalse(restorePolicy(backgroundOwnedVideoDisable = true, chatOnly = true))
        assertFalse(restorePolicy(backgroundOwnedVideoDisable = true, videoAlreadySuppressed = true))
    }

    private fun policy(
        backgroundPlaybackEnabled: Boolean = true,
        isInPictureInPicture: Boolean = false,
        playWhenReady: Boolean = true,
        playbackState: Int = Player.STATE_READY,
        hasMediaItem: Boolean = true,
        audioOnly: Boolean = false,
        chatOnly: Boolean = false,
        videoAlreadySuppressed: Boolean = false,
    ) = shouldDisableVideoForBackground(
        backgroundPlaybackEnabled = backgroundPlaybackEnabled,
        isInPictureInPicture = isInPictureInPicture,
        playWhenReady = playWhenReady,
        playbackState = playbackState,
        hasMediaItem = hasMediaItem,
        audioOnly = audioOnly,
        chatOnly = chatOnly,
        videoAlreadySuppressed = videoAlreadySuppressed,
    )

    private fun restorePolicy(
        backgroundOwnedVideoDisable: Boolean,
        audioOnly: Boolean = false,
        chatOnly: Boolean = false,
        videoAlreadySuppressed: Boolean = false,
    ) = shouldRestoreVideoAfterBackground(
        backgroundOwnedVideoDisable = backgroundOwnedVideoDisable,
        audioOnly = audioOnly,
        chatOnly = chatOnly,
        videoAlreadySuppressed = videoAlreadySuppressed,
    )
}
