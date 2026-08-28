package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.chat.Image
import kotlin.math.roundToInt

data class InlineImageGeometry(
    val widthPx: Int,
    val heightPx: Int,
)

/** Computes the immutable text-layout slot for an inline chat image. */
fun imageGeometry(image: Image, targetHeightPx: Int): InlineImageGeometry {
    val height = targetHeightPx.coerceAtLeast(1)
    val ratio = if (
        image.sourceWidth != null &&
        image.sourceHeight != null &&
        image.sourceWidth > 0 &&
        image.sourceHeight > 0
    ) {
        image.sourceWidth.toFloat() / image.sourceHeight.toFloat()
    } else {
        1f
    }
    val width = (height * ratio).roundToInt().coerceIn(
        (height * 0.5f).roundToInt().coerceAtLeast(1),
        height * 4,
    )
    return InlineImageGeometry(width, height)
}
