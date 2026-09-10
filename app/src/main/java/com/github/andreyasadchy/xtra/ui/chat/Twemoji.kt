package com.github.andreyasadchy.xtra.ui.chat

/** Fixed Twemoji asset URLs used for a consistent emoji appearance across Android fonts. */
internal object Twemoji {
    private const val ASSET_BASE = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@17.0.1/assets/72x72"

    fun url(value: String): String = "$ASSET_BASE/${assetName(value)}.png"

    private fun assetName(value: String): String {
        val codePoints = StringBuilder()
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            if (codePoint != 0xFE0E && codePoint != 0xFE0F) {
                if (codePoints.isNotEmpty()) codePoints.append('-')
                codePoints.append(codePoint.toString(16))
            }
            index += Character.charCount(codePoint)
        }
        return codePoints.toString()
    }
}
