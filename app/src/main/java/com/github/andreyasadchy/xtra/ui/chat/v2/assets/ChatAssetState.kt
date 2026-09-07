package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.graphics.drawable.Drawable
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey

sealed interface ChatAssetState {
    data object Missing : ChatAssetState
    data object Loading : ChatAssetState
    data class Ready(val image: ChatImageHandle) : ChatAssetState
    data class Failed(val nextRetryAtMs: Long, val attempts: Int, val permanentUntilMs: Long? = null) : ChatAssetState {
        /** The row may expose its failure fallback only after this state is stable enough. */
        val isPresentationTerminal: Boolean
            get() = permanentUntilMs != null || attempts >= 3
    }
}

/**
 * Lightweight reference to an image owned by the image loader. Shareable implementations must
 * not retain the decoded image themselves. A drawable is created per consumer where the image
 * format provides an independent drawable factory; Coil's non-shareable animated fallback is
 * the documented exception because Coil does not cache that decoded value.
 */
fun interface ChatImageHandle {
    fun newDrawable(): Drawable?

    /** True only for Coil formats which cannot be recreated from Coil's decoded memory cache. */
    fun holdsDecodedImage(): Boolean = false
}

class ChatAssetLoadException(val statusCode: Int? = null, cause: Throwable? = null) : Exception(cause)

fun interface ChatAssetLoader { suspend fun load(key: ChatAssetKey): ChatImageHandle? }
