package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessageId
import java.util.LinkedHashMap

/** Recompiles presentation from immutable message data and the current catalog revision. */
class ChatPresentationResolver(
    private var compiler: ChatRowCompiler = ChatRowCompiler(),
    private val maxCachedRows: Int = 800,
) {
    class Snapshot internal constructor(
        private val owner: ChatPresentationResolver,
        private val compiler: ChatRowCompiler,
        val generation: Long,
    ) {
        fun resolve(
            message: ChatMessage,
            catalog: ChatCatalogSnapshot,
            presentationRevision: Long = 0L,
        ): ChatRowUiModel = owner.resolveSnapshot(
            compiler,
            generation,
            message,
            catalog,
            presentationRevision,
        )
    }

    private data class CacheKey(
        val id: ChatMessageId,
        val messageHash: Int,
        val catalogRevision: Long,
        val rewardsRevision: Int,
        val compilerGeneration: Long,
        val presentationRevision: Long,
    )

    private var generation = 0L
    private val cache = object : LinkedHashMap<CacheKey, ChatRowUiModel>(maxCachedRows, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ChatRowUiModel>?): Boolean =
            size > maxCachedRows
    }

    @Synchronized fun replaceCompiler(value: ChatRowCompiler) {
        compiler = value
        generation++
        cache.clear()
    }

    /** Drops presentation-only results, such as translations, without replaying the timeline. */
    @Synchronized fun invalidate() {
        cache.clear()
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(this, compiler, generation)

    @Synchronized
    fun isCurrent(snapshot: Snapshot): Boolean = generation == snapshot.generation

    @Synchronized
    fun resolve(
        message: ChatMessage,
        catalog: ChatCatalogSnapshot,
        presentationRevision: Long = 0L,
    ): ChatRowUiModel = resolveSnapshot(compiler, generation, message, catalog, presentationRevision)

    @Synchronized
    private fun resolveSnapshot(
        snapshotCompiler: ChatRowCompiler,
        snapshotGeneration: Long,
        message: ChatMessage,
        catalog: ChatCatalogSnapshot,
        presentationRevision: Long,
    ): ChatRowUiModel {
        val key = CacheKey(
            id = message.id,
            messageHash = message.hashCode(),
            catalogRevision = catalog.revision,
            rewardsRevision = catalog.channelPointRewardsRevision,
            compilerGeneration = snapshotGeneration,
            presentationRevision = presentationRevision,
        )
        return cache[key] ?: snapshotCompiler.compile(message, catalog).also { cache[key] = it }
    }
}
