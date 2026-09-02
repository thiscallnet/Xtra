package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage

/** Recompiles presentation from immutable message data and the current catalog revision. */
class ChatPresentationResolver(
    private var compiler: ChatRowCompiler = ChatRowCompiler(),
) {
    @Synchronized fun replaceCompiler(value: ChatRowCompiler) {
        compiler = value
    }

    @Synchronized
    fun resolve(message: ChatMessage, catalog: ChatCatalogSnapshot): ChatRowUiModel =
        compiler.compile(message, catalog)
}
