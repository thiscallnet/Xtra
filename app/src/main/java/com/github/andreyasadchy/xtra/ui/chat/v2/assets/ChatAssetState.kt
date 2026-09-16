package com.github.andreyasadchy.xtra.ui.chat.v2.assets

import android.graphics.drawable.Drawable
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetDimensions

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
 * not retain a mutable Drawable themselves. A drawable is created per consumer where the image
 * format provides an independent drawable factory. Handles may retain the decoded image when the
 * upstream image cache is not a sufficient lifetime guarantee for a Ready entry.
 */
fun interface ChatImageHandle {
    fun newDrawable(): Drawable?

    /** Stable decoded dimensions, independent from the current animation frame. */
    fun intrinsicDimensions(): ChatAssetDimensions? = null

    /** True when this handle retains a decoded image that should be released with its last observer. */
    fun holdsDecodedImage(): Boolean = false
}

class ChatAssetLoadException(val statusCode: Int? = null, cause: Throwable? = null) : Exception(cause)

fun interface ChatAssetLoader { suspend fun load(key: ChatAssetKey): ChatImageHandle? }
