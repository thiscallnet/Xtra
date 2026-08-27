package com.github.andreyasadchy.xtra.model.chat

enum class ImageKind {
    EMOTE,
    BADGE,
    INLINE_ICON,
}

class Image(
    val localData: ByteArray? = null,
    val localDataUrl: String? = null,
    val localDataRange: Pair<Long, Int>? = null,
    val url1x: String? = null,
    val url2x: String? = null,
    val url3x: String? = null,
    val url4x: String? = null,
    val format: String? = null,
    val isAnimated: Boolean = false,
    val kind: ImageKind = ImageKind.INLINE_ICON,
    val thirdParty: Boolean = false,
    var overlayEmote: Image? = null,
    var start: Int,
    var end: Int,
) {
    fun withLocalData(bytes: ByteArray) = Image(bytes, localDataUrl, localDataRange, url1x, url2x, url3x, url4x, format, isAnimated, kind, thirdParty, overlayEmote, start, end)
}
