package com.github.andreyasadchy.xtra.ui.common

import java.util.WeakHashMap

/** Invalidates async preview binds when their RecyclerView target is recycled. */
internal class PreviewTargetGeneration<T : Any> {
    private val generations = WeakHashMap<T, Long>()

    fun capture(target: T): Long = generations[target] ?: 0L

    fun invalidate(target: T) {
        generations[target] = capture(target) + 1L
    }

    fun isCurrent(target: T, generation: Long): Boolean = capture(target) == generation
}
