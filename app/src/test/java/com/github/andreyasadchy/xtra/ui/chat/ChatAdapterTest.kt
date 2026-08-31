package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.model.chat.ChatMessage
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

    @Test
    fun trimConsumesDividerAndTheRequestedRealRows() {
        val divider = ChatMessage(type = ChatMessage.NEW_MESSAGE_DIVIDER)
        val rows = listOf(divider, message("m3"), message("m4"), message("m5"))

        assertEquals(2, adapterRowsToRemoveForTrim(rows, trimCount = 1))
    }

    @Test
    fun trimDoesNotConsumeDividerAfterRealRows() {
        val rows = listOf(message("m1"), message("m2"), ChatMessage(type = ChatMessage.NEW_MESSAGE_DIVIDER), message("m3"))

        assertEquals(2, adapterRowsToRemoveForTrim(rows, trimCount = 2))
    }

    @Test
    fun consumedBoundaryIsNotReconstructedFromSnapshot() {
        assertEquals(false, shouldReconstructNewMessageDivider(dividerPosition = null, consumed = true))
        assertEquals(false, shouldReconstructNewMessageDivider(dividerPosition = 0, consumed = true))
        assertEquals(true, shouldReconstructNewMessageDivider(dividerPosition = 0, consumed = false))
    }

    private fun message(text: String) = ChatMessage(
        type = ChatMessage.USER_MESSAGE,
        message = text,
    )
}
