package com.github.andreyasadchy.xtra.ui.chat.v2.presentation

import com.github.andreyasadchy.xtra.ui.chat.v2.catalog.ChatCatalogSnapshot
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatMessage
import java.util.LinkedHashMap
import android.os.Trace
import com.github.andreyasadchy.xtra.BuildConfig

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
        val message: ChatMessage,
        val catalog: CatalogIdentity,
        val compilerGeneration: Long,
        val presentationRevision: Long,
    )

    // Frozen per-message catalogs retain their identity across unrelated provider updates.
    // Identity also distinguishes two provisional catalogs with the same public revision.
    private class CatalogIdentity(val value: ChatCatalogSnapshot) {
        override fun equals(other: Any?): Boolean = other is CatalogIdentity && value === other.value
        override fun hashCode(): Int = System.identityHashCode(value)
    }

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
            message = message,
            catalog = CatalogIdentity(catalog),
            compilerGeneration = snapshotGeneration,
            presentationRevision = presentationRevision,
        )
        cache[key]?.let { return it }
        if (BuildConfig.PERF_DIAGNOSTICS) Trace.beginSection("Xtra.ChatV2.compileRow")
        return try {
            snapshotCompiler.compile(message, catalog).also { cache[key] = it }
        } finally {
            if (BuildConfig.PERF_DIAGNOSTICS) Trace.endSection()
        }
    }
}
