package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceSwitchQualityStateTest {

    @Test
    fun reverseTransitionKeepsSelectionWhenClearedSourceHasNoQuality() {
        val state = SourceSwitchQualityState()

        state.capture(VideoQuality("1080p60"))
        state.capture(null)

        assertEquals(SourceSwitchQualityIdentity("1080p60", null, null), state.consume())
        assertNull(state.consume())
    }

    @Test
    fun newerExplicitSelectionReplacesOlderPendingSelection() {
        val state = SourceSwitchQualityState()

        state.capture(VideoQuality("1080p60"))
        state.capture(VideoQuality("720p60"))

        assertEquals(SourceSwitchQualityIdentity("720p60", null, null), state.consume())
    }

    @Test
    fun cancellationCleanupDoesNotLeakSelection() {
        val state = SourceSwitchQualityState()

        state.capture(VideoQuality("1080p60"))
        state.clear()

        assertNull(state.consume())
    }

    @Test
    fun failedTransitionCanRestoreTheCapturedSelectionBeforeClearingIt() {
        val state = SourceSwitchQualityState()

        state.capture(VideoQuality("1080p60"))
        val selectionForRollback = state.consume()

        assertEquals(SourceSwitchQualityIdentity("1080p60", null, null), selectionForRollback)
        assertNull(state.consume())
    }

    @Test
    fun autoAndAudioOnlySelectionsArePreservedLikeNamedQualities() {
        val state = SourceSwitchQualityState()

        state.capture(VideoQuality("auto"))
        assertEquals(SourceSwitchQualityIdentity("auto", null, null), state.consume())

        state.capture(VideoQuality("audio_only"))
        assertEquals(SourceSwitchQualityIdentity("audio_only", null, null), state.consume())
    }

    @Test
    fun refreshedDuplicateNameRestoresMatchingCodecAndBitrateInsteadOfFirstNameMatch() {
        val state = SourceSwitchQualityState()
        val selectedMain = VideoQuality(
            name = "720p60",
            codecs = "avc1.4D401F,mp4a.40.2",
            bitrate = 3_422_999,
            url = "https://example.invalid/old-main.m3u8",
        )
        val refreshedHigh = VideoQuality(
            name = "720p60",
            codecs = "avc1.640020,mp4a.40.2",
            bitrate = 6_015_145,
            url = "https://example.invalid/new-high.m3u8",
        )
        val refreshedMain = VideoQuality(
            name = "720p60",
            codecs = "avc1.4D401F,mp4a.40.2",
            bitrate = 3_422_999,
            url = "https://example.invalid/new-main.m3u8",
        )

        state.capture(selectedMain)
        val identity = state.consume()!!
        val refreshedQualities = listOf(refreshedHigh, refreshedMain)
        val restored = identity.resolve(refreshedQualities) { name ->
            refreshedQualities.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }

        assertEquals(refreshedMain.codecs, restored?.codecs)
        assertEquals(refreshedMain.bitrate, restored?.bitrate)
        assertEquals(refreshedMain.url, restored?.url)
    }
}
