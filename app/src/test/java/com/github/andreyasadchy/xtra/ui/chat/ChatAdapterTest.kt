package com.github.andreyasadchy.xtra.ui.chat

import android.text.SpannableStringBuilder
import com.github.andreyasadchy.xtra.util.chat.ChatAdapterUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAdapterTest {

    @Test
    fun renderCacheMissUsesFallbackInsteadOfThrowing() {
        var fallbackUsed = false
        val fallback = ChatAdapterUtils.MessageResult(
            builder = SpannableStringBuilder(),
            images = arrayListOf(),
            imagePaint = null,
            userName = null,
            userNameStartIndex = null,
            translated = false,
            backgroundResource = 0,
        )

        val result = selectRenderResultForBind(null) {
            fallbackUsed = true
            fallback
        }

        assertTrue(fallbackUsed)
        assertEquals(fallback.backgroundResource, result.backgroundResource)
    }
}
