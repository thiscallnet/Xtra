package com.github.andreyasadchy.xtra.ui.multiview.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiviewQualityPolicyTest {
    @Test
    fun smartOneStreamKeepsNormalQualityBehavior() {
        val target = MultiviewQualityPolicy.target(input(streamCount = 1, active = true))

        assertNull(target.maxHeightPx)
        assertTrue(!target.isConstrained)
    }

    @Test
    fun smartTwoStreamsCapsBothAt720BeforeTileSizing() {
        val active = MultiviewQualityPolicy.target(input(streamCount = 2, active = true, tileHeight = 900))
        val secondary = MultiviewQualityPolicy.target(input(streamCount = 2, active = false, tileHeight = 900))

        assertEquals(720, active.maxHeightPx)
        assertEquals(720, secondary.maxHeightPx)
    }

    @Test
    fun smartThreeAndFourStreamsPrioritizeActiveTile() {
        val threeActive = MultiviewQualityPolicy.target(input(streamCount = 3, active = true))
        val threeSecondary = MultiviewQualityPolicy.target(input(streamCount = 3, active = false))
        val fourActive = MultiviewQualityPolicy.target(input(streamCount = 4, active = true))
        val fourSecondary = MultiviewQualityPolicy.target(input(streamCount = 4, active = false))

        assertEquals(720, threeActive.maxHeightPx)
        assertEquals(480, threeSecondary.maxHeightPx)
        assertEquals(720, fourActive.maxHeightPx)
        assertEquals(480, fourSecondary.maxHeightPx)
        assertEquals(60, fourSecondary.maxFrameRate)
    }

    @Test
    fun focusRaisesFocusedStreamAndLimitsBackground() {
        val focused = MultiviewQualityPolicy.target(input(streamCount = 4, active = false, focused = true))
        val background = MultiviewQualityPolicy.target(input(streamCount = 4, active = false, focused = false))

        assertNull(focused.maxHeightPx)
        assertEquals(480, background.maxHeightPx)
    }

    @Test
    fun manualOverrideWinsOverSmartPolicy() {
        val target = MultiviewQualityPolicy.target(
            input(streamCount = 4, active = false, override = "720p60"),
        )

        assertEquals(720, target.maxHeightPx)
        assertEquals("720p60", target.label)
    }

    @Test
    fun manualOverrideWinsOverHighQualityMode() {
        val target = MultiviewQualityPolicy.target(
            input(streamCount = 2, active = false, override = "480p", mode = MultiviewQualityMode.HIGH_QUALITY),
        )

        assertEquals(480, target.maxHeightPx)
    }

    @Test
    fun bufferingDowngradeUsesResolutionBeforeFrameRate() {
        val target = MultiviewQualityPolicy.target(
            input(streamCount = 4, active = false, bufferingLevel = 1),
        )

        assertEquals(360, target.maxHeightPx)
        assertEquals(60, target.maxFrameRate)
    }

    @Test
    fun focusedAdaptiveStreamGetsRecoveryCeilingAfterRebuffers() {
        val target = MultiviewQualityPolicy.target(
            input(streamCount = 2, active = true, focused = true, bufferingLevel = 1),
        )

        assertEquals(480, target.maxHeightPx)
        assertEquals(60, target.maxFrameRate)
    }

    @Test
    fun resourcePressureConstrainsOtherwiseAdaptiveSingleStream() {
        val target = MultiviewQualityPolicy.target(
            input(streamCount = 1, active = true, resourcePressure = true),
        )

        assertEquals(480, target.maxHeightPx)
    }

    @Test
    fun availableQualitiesOnlyContainsFormatsFromManifest() {
        val labels = MultiviewQualityPolicy.availableManualLabels(
            listOf(
                MultiviewQualityPolicy.AvailableFormat(720, 60f),
                MultiviewQualityPolicy.AvailableFormat(480, 30f),
                MultiviewQualityPolicy.AvailableFormat(1080, 60f, isSource = true),
            ),
        )

        assertEquals(listOf("Source", "1080p60", "720p60", "480p30"), labels)
    }

    private fun input(
        streamCount: Int,
        active: Boolean,
        focused: Boolean = false,
        tileHeight: Int = 0,
        override: String? = null,
        bufferingLevel: Int = 0,
        mode: MultiviewQualityMode = MultiviewQualityMode.SMART,
        resourcePressure: Boolean = false,
    ) = MultiviewQualityInput(
        streamCount = streamCount,
        isActive = active,
        isFocused = focused,
        tileHeightPx = tileHeight,
        manualOverride = override,
        bufferingDowngradeLevel = bufferingLevel,
        mode = mode,
        resourcePressure = resourcePressure,
    )
}
