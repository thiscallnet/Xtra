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

        assertEquals(25, config.safeWidthPercent)
        assertEquals(80, config.safeHeightPercent)
        assertEquals(40, config.safeOpacityPercent)
    }

    @Test
    fun presetsHaveExpectedTvDefaults() {
        assertEquals(TvChatOverlayAnchor.TOP_RIGHT, tvChatPresetConfig(TvChatOverlayPreset.STANDARD).anchor)
        assertEquals(27, tvChatPresetConfig(TvChatOverlayPreset.COMPACT).widthPercent)
        assertEquals(60, tvChatPresetConfig(TvChatOverlayPreset.LARGE).heightPercent)
        assertEquals(TvChatOverlayAnchor.CENTER_RIGHT, tvChatPresetConfig(TvChatOverlayPreset.FULL_HEIGHT).anchor)
        assertEquals(TvChatOverlayPreset.CUSTOM, tvChatPresetConfig(TvChatOverlayPreset.CUSTOM).preset)
    }
}
