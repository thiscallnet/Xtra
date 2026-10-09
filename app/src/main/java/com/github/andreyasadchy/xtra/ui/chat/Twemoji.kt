package com.github.andreyasadchy.xtra.ui.chat

import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetKey
import com.github.andreyasadchy.xtra.ui.chat.v2.domain.ChatAssetSpec

/**
 * Fixed Twemoji artwork used for a consistent emoji appearance across Android fonts. Every picker
 * emoji ships in the APK as lossless WebP (assets/twemoji), so rows render without any network
 * request. The CDN URL is only a fallback for a name missing from the bundle.
 */
internal object Twemoji {
    private const val CDN_BASE = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@17.0.1/assets/72x72"
    private const val BUNDLED_BASE = "file:///android_asset/twemoji"
    private val ASSET_NAME_OVERRIDES = mapOf(
        // Twemoji's canonical filename omits the optional selectors for this
        // compatibility sequence even though the catalog value includes them.
        "👁️‍🗨️" to "1f441-200d-1f5e8",
    )

    fun url(value: String): String = "$BUNDLED_BASE/${assetName(value)}.webp"

    /** Network URL for a bundled asset URL, or null when [url] is not a bundled Twemoji. */
    fun cdnFallbackUrl(url: String): String? =
        if (url.startsWith("$BUNDLED_BASE/") && url.endsWith(".webp")) {
            "$CDN_BASE/${url.removePrefix("$BUNDLED_BASE/").removeSuffix(".webp")}.png"
        } else null

    fun isAsset(key: ChatAssetKey): Boolean = key.value.startsWith(BUNDLED_BASE) || key.value.startsWith(CDN_BASE)

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
