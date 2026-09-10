package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec

/** Fixed Twemoji asset URLs used for a consistent emoji appearance across Android fonts. */
internal object Twemoji {
    private const val ASSET_BASE = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@17.0.1/assets/72x72"
    private val ASSET_NAME_OVERRIDES = mapOf(
        // Twemoji's canonical filename omits the optional selectors for this
        // compatibility sequence even though the catalog value includes them.
        "👁️‍🗨️" to "1f441-200d-1f5e8",
    )

    fun url(value: String): String = "$ASSET_BASE/${assetName(value)}.png"

    fun asset(value: String): ChatAssetSpec = ChatAssetSpec(
        key = ChatAssetKey(url(value)),
        sourceWidth = 72,
        sourceHeight = 72,
        targetHeight = 1,
    )

    private fun assetName(value: String): String {
        ASSET_NAME_OVERRIDES[value]?.let { return it }
        val codePoints = StringBuilder()
        val containsJoiner = value.indexOf('\u200D') >= 0
        var index = 0
        while (index < value.length) {
            val codePoint = value.codePointAt(index)
            val nextIndex = index + Character.charCount(codePoint)
            if (codePoint != 0xFE0E && (codePoint != 0xFE0F || containsJoiner)) {
                if (codePoints.isNotEmpty()) codePoints.append('-')
                codePoints.append(codePoint.toString(16))
            }
            index = nextIndex
        }
        return codePoints.toString()
    }
}
