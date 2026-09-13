package com.github.andreyasadchy.xtra.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorPickerTest {

    @Test
    fun rgbOnlySettingsRejectAlphaValues() {
        assertEquals(0xFF123456.toInt(), parsePickerColor("#123456", allowAlpha = false))
        assertEquals(0xFF123456.toInt(), parsePickerColor("123456", allowAlpha = false))
        assertNull(parsePickerColor("#80123456", allowAlpha = false))
    }

    @Test
    fun alphaEnabledSettingsAcceptAndFormatOpacity() {
        val color = parsePickerColor("#80123456", allowAlpha = true)

        assertEquals(0x80123456.toInt(), color)
        assertEquals("#80123456", formatColor(color!!, allowAlpha = true))
        assertEquals("#123456", formatColor(0xFF123456.toInt(), allowAlpha = true))
    }

    @Test
    fun invalidPickerValuesAreRejected() {
        listOf(null, "", "#12345", "#1234567", "#12345Z", "#12345678").forEach { value ->
            assertNull(parsePickerColor(value, allowAlpha = false))
        }
        assertNull(parsePickerColor("#1234567", allowAlpha = true))
    }
}
