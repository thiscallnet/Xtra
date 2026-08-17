package com.github.andreyasadchy.xtra.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class EmotePickerSizingTest {

    @Test
    fun largeHostKeepsTheMaximumHeight() {
        assertEquals(
            300,
            calculateEmotePickerPagerHeight(
                hostHeight = 800,
                fixedContentHeight = 48,
                tabHeight = 48,
                pickerMargins = 0,
                maxHeight = 300,
            ),
        )
    }

    @Test
    fun constrainedHostUsesAllAvailableSpace() {
        assertEquals(
            215,
            calculateEmotePickerPagerHeight(
                hostHeight = 311,
                fixedContentHeight = 48,
                tabHeight = 48,
                pickerMargins = 0,
                maxHeight = 300,
            ),
        )
    }

    @Test
    fun fixedComposerAndReplySpaceReducesThePager() {
        assertEquals(
            167,
            calculateEmotePickerPagerHeight(
                hostHeight = 311,
                fixedContentHeight = 96,
                tabHeight = 48,
                pickerMargins = 0,
                maxHeight = 300,
            ),
        )
    }

    @Test
    fun resultNeverBecomesNegative() {
        assertEquals(
            0,
            calculateEmotePickerPagerHeight(
                hostHeight = 100,
                fixedContentHeight = 96,
                tabHeight = 48,
                pickerMargins = 12,
                maxHeight = 300,
            ),
        )
    }
}
