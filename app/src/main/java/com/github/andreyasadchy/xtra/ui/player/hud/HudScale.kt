package com.github.andreyasadchy.xtra.ui.player.hud

/**
 * Visual scale limits for the HUD. Hit targets are expanded separately by
 * HudLayoutEngine and HudElementFrame, so a small icon remains usable.
 */
object HudScale {
    const val GLOBAL_MIN = 0.70f
    const val GLOBAL_MAX = 1.50f
    const val ELEMENT_MIN = 0.50f
    const val ELEMENT_MAX = 2.25f
    const val EFFECTIVE_MIN = 0.50f
    const val EFFECTIVE_MAX = 2.50f

    fun clampGlobal(scale: Float): Float =
        scale.takeIf(Float::isFinite)?.coerceIn(GLOBAL_MIN, GLOBAL_MAX) ?: 1f

    fun effective(globalScale: Float, elementScale: Float): Float =
        (clampGlobal(globalScale) * elementScale.safeValue())
            .takeIf(Float::isFinite)
            ?.coerceIn(EFFECTIVE_MIN, EFFECTIVE_MAX)
            ?: 1f

    private fun Float.safeValue(): Float = takeIf { isFinite() && it > 0f } ?: 1f
}
