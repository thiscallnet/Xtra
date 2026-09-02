package com.github.andreyasadchy.xtra.ui.chat.v2

import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.andreyasadchy.xtra.ui.chat.v2.presentation.ChatColorResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatColorResolverTest {
    @Test
    fun midToneBackgroundUsesBlackDirectionWhenWhiteCannotMeetContrast() {
        val background = Color.rgb(0xA0, 0xA0, 0xA0)
        val original = Color.rgb(0x00, 0x80, 0x80)

        val resolved = ChatColorResolver(readable = true).resolve("#008080", "mid-tone", background)

        assertNotEquals(original, resolved)
        assertTrue(Color.red(resolved) <= 8)
        assertTrue(Color.green(resolved) < Color.green(original))
        assertTrue(Color.blue(resolved) < Color.blue(original))
        assertTrue(ColorUtils.calculateContrast(resolved, background) >= 3.0)
    }

    @Test
    fun whiteBackgroundDarkensAnUnreadableColor() {
        val background = Color.WHITE
        val original = Color.rgb(0xA0, 0xA0, 0xA0)

        val resolved = ChatColorResolver(readable = true).resolve("#A0A0A0", "white", background)

        assertNotEquals(original, resolved)
        assertTrue(ColorUtils.calculateContrast(resolved, background) >= 3.0)
    }

    @Test
    fun blackBackgroundKeepsAnAlreadyReadableColor() {
        val original = Color.rgb(0xFF, 0x00, 0x00)

        val resolved = ChatColorResolver(readable = true).resolve("#FF0000", "red", Color.BLACK)

        assertEquals(original, resolved)
    }
}
