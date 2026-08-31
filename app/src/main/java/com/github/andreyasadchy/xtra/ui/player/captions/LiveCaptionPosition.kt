package com.github.andreyasadchy.xtra.ui.player.captions

internal object LiveCaptionPosition {
    fun translationForCenter(
        normalizedCenter: Float,
        parentSize: Int,
        viewStart: Int,
        viewSize: Int,
    ): Float = normalizedCenter.coerceIn(0f, 1f) * parentSize -
        (viewStart + viewSize / 2f)

    fun normalizedCenterForTranslation(
        translation: Float,
        parentSize: Int,
        viewStart: Int,
        viewSize: Int,
    ): Float = ((viewStart + viewSize / 2f + translation) / parentSize)
        .coerceIn(0f, 1f)
}
