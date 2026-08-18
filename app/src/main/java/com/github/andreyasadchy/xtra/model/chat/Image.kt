package com.github.andreyasadchy.xtra.model.chat

enum class ImageKind {
    EMOTE,
    BADGE,
    INLINE_ICON,
}

class Image(
    val localData: ByteArray? = null,
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
)
