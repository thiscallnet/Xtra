package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage

/** Recompiles presentation from immutable message data and the current catalog revision. */
class ChatPresentationResolver(
    private val compiler: ChatRowCompiler = ChatRowCompiler(),
) {
    fun resolve(message: ChatMessage, catalog: ChatCatalogSnapshot): ChatRowUiModel =
        compiler.compile(message, catalog)
}
