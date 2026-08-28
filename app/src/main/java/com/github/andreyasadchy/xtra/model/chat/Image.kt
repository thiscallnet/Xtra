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
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    var overlayEmote: Image? = null,
    var start: Int,
    var end: Int,
) {
    fun withLocalData(bytes: ByteArray) = Image(
        localData = bytes,
        localDataUrl = localDataUrl,
        localDataRange = localDataRange,
        url1x = url1x,
        url2x = url2x,
        url3x = url3x,
        url4x = url4x,
        format = format,
        isAnimated = isAnimated,
        kind = kind,
        thirdParty = thirdParty,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        overlayEmote = overlayEmote,
        start = start,
        end = end,
    )
}
