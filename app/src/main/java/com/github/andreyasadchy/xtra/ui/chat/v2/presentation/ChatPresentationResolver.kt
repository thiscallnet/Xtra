package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage

/** Recompiles presentation from immutable message data and the current catalog revision. */
class ChatPresentationResolver(
    private var compiler: ChatRowCompiler = ChatRowCompiler(),
) {
    class Snapshot internal constructor(
        private val compiler: ChatRowCompiler,
        val generation: Long,
    ) {
        fun resolve(message: ChatMessage, catalog: ChatCatalogSnapshot): ChatRowUiModel =
            compiler.compile(message, catalog)
    }

    private var generation = 0L

    @Synchronized fun replaceCompiler(value: ChatRowCompiler) {
        compiler = value
        generation++
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(compiler, generation)

    @Synchronized
    fun isCurrent(snapshot: Snapshot): Boolean = generation == snapshot.generation

    @Synchronized
    fun resolve(message: ChatMessage, catalog: ChatCatalogSnapshot): ChatRowUiModel =
        compiler.compile(message, catalog)
}
