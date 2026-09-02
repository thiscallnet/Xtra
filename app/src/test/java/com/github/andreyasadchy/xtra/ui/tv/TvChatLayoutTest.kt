package com.github.andreyasadchy.xtra.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvChatLayoutTest {

    @Test
    fun invalidValuesUseSafeDefaults() {
        assertEquals(TvChatMode.SIDE_PANEL, tvChatMode("unknown"))
        assertEquals(TvChatOverlayAnchor.TOP_RIGHT, tvChatAnchor("unknown"))
        assertEquals(TvChatOverlayPreset.AUTO, tvChatPreset("unknown"))
    }

    @Test
    fun valuesAreClampedToSupportedRanges() {
        val config = TvChatOverlayConfig(widthPercent = 1, heightPercent = 101, opacityPercent = 0)

        assertEquals(15, config.safeWidthPercent)
        assertEquals(90, config.safeHeightPercent)
        assertEquals(40, config.safeOpacityPercent)
    }

    @Test fun sidePanelWidthUsesConfiguredPercentAndMinimum() {
        assertEquals(192, tvSidePanelWidth(1280, 15, 160))
        assertEquals(320, tvSidePanelWidth(1280, 25, 160))
        assertEquals(640, tvSidePanelWidth(1280, 80, 160))
        assertEquals(200, tvSidePanelWidth(200, 15, 240))
    }

    @Test fun overlayAcceptsCompactPercentagesAndClampsInvalidValues() {
        assertEquals(15, TvChatOverlayConfig(widthPercent = -1).safeWidthPercent)
        assertEquals(15, TvChatOverlayConfig(heightPercent = 0).safeHeightPercent)
        assertEquals(70, TvChatOverlayConfig(widthPercent = 100).safeWidthPercent)
        assertEquals(90, TvChatOverlayConfig(heightPercent = 100).safeHeightPercent)
    }

    @Test
    fun presetsHaveExpectedTvDefaults() {
        assertEquals(TvChatOverlayAnchor.TOP_RIGHT, tvChatPresetConfig(TvChatOverlayPreset.STANDARD).anchor)
        assertEquals(21, tvChatPresetConfig(TvChatOverlayPreset.COMPACT).widthPercent)
        assertEquals(60, tvChatPresetConfig(TvChatOverlayPreset.LARGE).heightPercent)
        assertEquals(TvChatOverlayAnchor.CENTER_RIGHT, tvChatPresetConfig(TvChatOverlayPreset.FULL_HEIGHT).anchor)
        assertEquals(TvChatOverlayPreset.CUSTOM, tvChatPresetConfig(TvChatOverlayPreset.CUSTOM).preset)
    }
}
