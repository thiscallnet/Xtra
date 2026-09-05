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

fun interface ChatImageHandle { fun newDrawable(): Drawable }

class ChatAssetLoadException(val statusCode: Int? = null, cause: Throwable? = null) : Exception(cause)

fun interface ChatAssetLoader { suspend fun load(key: ChatAssetKey): ChatImageHandle? }
