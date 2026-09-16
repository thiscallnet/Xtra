package com.github.andreyasadchy.xtra.ui.player.hud

data class HudElementSpec(
    val id: HudElementId,
    val pivot: HudPivot = HudPivot.CENTER,
    val normalVisualSize: HudSize,
    val compactVisualSize: HudSize = normalVisualSize,
    val minimumHitSize: HudSize,
    val minimumScale: Float = HudScale.ELEMENT_MIN,
    val maximumScale: Float = HudScale.ELEMENT_MAX,
    val isInteractive: Boolean = true,
) {
    fun visualSize(compactHeight: Boolean): HudSize =
        if (compactHeight) compactVisualSize else normalVisualSize

    fun clampScale(scale: Float): Float =
        scale.takeIf { it.isFinite() }?.coerceIn(minimumScale, maximumScale)
            ?: 1f
}

object HudElementRegistry {
    private val icon = HudSize(40f, 40f)
    private val compactIcon = HudSize(36f, 36f)
    private val iconHit = HudSize(48f, 48f)

    val all: List<HudElementSpec> = listOf(
        HudElementSpec(HudElementId.STREAM_INFO, HudPivot.TOP_START, HudSize(280f, 76f), HudSize(240f, 60f), HudSize(0f, 0f), isInteractive = false),
        HudElementSpec(HudElementId.TIMELINE, HudPivot.BOTTOM_CENTER, HudSize(0f, 48f), HudSize(0f, 48f), HudSize(0f, 24f)),
        HudElementSpec(HudElementId.SEEK_BACK, normalVisualSize = HudSize(48f, 48f), compactVisualSize = HudSize(44f, 44f), minimumHitSize = HudSize(56f, 56f)),
        HudElementSpec(HudElementId.PLAY_PAUSE, normalVisualSize = HudSize(60f, 60f), compactVisualSize = HudSize(56f, 56f), minimumHitSize = HudSize(72f, 72f)),
        HudElementSpec(HudElementId.SEEK_FORWARD, normalVisualSize = HudSize(48f, 48f), compactVisualSize = HudSize(44f, 44f), minimumHitSize = HudSize(56f, 56f)),
        HudElementSpec(HudElementId.FOLLOW, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        // Keep the pill at the V2 contract size. Its presentation reserves
        // enough room for the gear and the compact resolution/fps label; the
        // full quality name remains available through contentDescription.
        HudElementSpec(HudElementId.QUALITY, normalVisualSize = HudSize(72f, 36f), compactVisualSize = HudSize(72f, 36f), minimumHitSize = HudSize(76f, 48f)),
        HudElementSpec(HudElementId.ASPECT_RATIO, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.VOLUME, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.CLIP, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.CAPTIONS, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.CHAT, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.FULLSCREEN, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.MORE, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.INTERACTION_LOCK, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.MINIMIZE, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.DOWNLOAD, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.SPEED, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.CHAPTERS, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.RESTART, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.GO_LIVE, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.AUDIO_MODE, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.AUDIO_COMPRESSOR, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.CHAT_INPUT, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
        HudElementSpec(HudElementId.SLEEP_TIMER, normalVisualSize = icon, compactVisualSize = compactIcon, minimumHitSize = iconHit),
    )

    private val byId = all.associateBy(HudElementSpec::id)

    fun get(id: HudElementId): HudElementSpec = byId.getValue(id)
}
