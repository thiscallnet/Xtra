package com.github.andreyasadchy.xtra.ui.player.captions

/** Converts the process-wide drop counter into a per-engine-session metric. */
internal class EngineDropBaseline(initialTotal: Int = 0) {
    private var baseline = initialTotal

    fun reset(total: Int) {
        baseline = total
    }

    fun delta(total: Int): Int = (total - baseline).coerceAtLeast(0)
}
