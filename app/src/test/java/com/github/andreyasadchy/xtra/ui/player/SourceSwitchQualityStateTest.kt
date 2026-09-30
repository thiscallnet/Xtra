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

    @Test
    fun vaftAvoidanceKeepsOriginalRungWhenAlternateMustUseLowerQuality() {
        val state = VaftQualityState()
        val primary720 = VideoQuality("720p60", bitrate = 3_000_000, url = "primary-720")
        val alternate360 = VideoQuality("360p", bitrate = 800_000, url = "alternate-360")
        val alternate160 = VideoQuality("160p", bitrate = 300_000, url = "alternate-160")

        state.begin(primary720)
        val alternateSelection = SourceSwitchQualityIdentity("720p60", null, 3_000_000)
            .resolve(listOf(alternate360, alternate160)) { name ->
                when (name) {
                    "720p60" -> alternate360
                    else -> listOf(alternate360, alternate160).firstOrNull { it.name == name }
                }
            }
        assertEquals("360p", alternateSelection?.name)

        val returnedPrimary = state.identityForPrimaryReturn?.resolve(listOf(primary720)) { null }

        assertEquals("720p60", returnedPrimary?.name)
        assertEquals("primary-720", returnedPrimary?.url)
        assertEquals("720p60", state.identityForPrimaryReturn?.name)
    }

    @Test
    fun explicitSameAlternateRungReplacesOriginalReturnIntent() {
        val state = VaftQualityState()
        val alternate360 = VideoQuality("360p", bitrate = 800_000, url = "alternate-360")
        val primary360 = VideoQuality("360p", bitrate = 1_000_000, url = "primary-360")

        state.begin(VideoQuality("720p60"))
        state.rememberExplicitSelection(alternate360)

        val returnedPrimary = state.identityForPrimaryReturn?.resolve(listOf(primary360)) { name ->
            listOf(primary360).firstOrNull { it.name == name }
        }

        assertEquals("360p", returnedPrimary?.name)
        assertEquals("primary-360", returnedPrimary?.url)
    }

    @Test
    fun autoAndNoInitialSelectionKeepTheirExistingReturnSemantics() {
        val state = VaftQualityState()
        state.begin(VideoQuality("auto"))

        assertEquals("auto", state.identityForPrimaryReturn?.name)

        state.clear()
        state.begin(null)

        assertEquals(true, state.isActive)
        assertNull(state.identityForPrimaryReturn)
    }

    @Test
    fun sourceSelectionFollowsSourceAcrossDifferentBitrateAndCodec() {
        val source = VideoQuality("Source", "h264", 3_000_000, "source")
        val refreshedSource = VideoQuality("Source", "h265", 6_000_000, "refreshed-source")

        val restored = SourceSwitchQualityIdentity("Source", source.codecs, source.bitrate)
            .resolve(listOf(refreshedSource)) { null }

        assertEquals("refreshed-source", restored?.url)
    }

    @Test
    fun vaftAvoidanceWaitsForTheExpectedPrimarySourceBeforeRestoringQuality() {
        val state = VaftQualityState()
        state.begin(VideoQuality("720p60", bitrate = 3_000_000))
        state.expectPrimaryReturn("https://primary.example/master.m3u8")

        assertEquals(true, state.isAwaitingPrimaryReturn)
        assertEquals(false, state.matchesPrimaryReturn("https://alternate.example/master.m3u8"))
        assertEquals(SourceSwitchQualityIdentity("720p60", null, 3_000_000), state.identityForPrimaryReturn)
        assertEquals(true, state.matchesPrimaryReturn("https://primary.example/master.m3u8"))

        state.clear()

        assertEquals(false, state.isAwaitingPrimaryReturn)
        assertEquals(false, state.matchesPrimaryReturn("https://primary.example/master.m3u8"))
    }
}
