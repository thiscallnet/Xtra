package com.github.andreyasadchy.xtra.ui.player.hud

enum class HudElementId {
    STREAM_INFO,
    TIMELINE,

    SEEK_BACK,
    PLAY_PAUSE,
    SEEK_FORWARD,

    FOLLOW,
    QUALITY,
    ASPECT_RATIO,

    VOLUME,
    CLIP,
    CAPTIONS,
    CHAT,
    FULLSCREEN,
    MORE,

    INTERACTION_LOCK,
    MINIMIZE,
    DOWNLOAD,
    SPEED,
    CHAPTERS,
    RESTART,
    GO_LIVE,
    AUDIO_MODE,
    AUDIO_COMPRESSOR,
    CHAT_INPUT,
    SLEEP_TIMER,
}

enum class HudPivot {
    CENTER,
    TOP_START,
    BOTTOM_CENTER,
}

enum class HudProfileMode {
    DEFAULT,
    CUSTOM,
}

enum class HudOrientation {
    PORTRAIT,
    LANDSCAPE,
}

enum class HudTimelineTimePosition {
    LEFT,
    RIGHT,
}

fun parseHudTimelineTimePosition(value: String?): HudTimelineTimePosition =
    if (value.equals("left", ignoreCase = true)) {
        HudTimelineTimePosition.LEFT
    } else {
        HudTimelineTimePosition.RIGHT
    }

data class HudPlacement(
    val enabled: Boolean,
    val x: Float,
    val y: Float,
    val scale: Float,
)

/**
 * The policy used for controls that are not explicitly present in a sparse
 * CUSTOM profile. This is separate from the config schema version so shipped
 * defaults can evolve without changing an existing user's HUD.
 */
object HudDefaultPolicy {
    const val LEGACY_V2 = 2
    const val COMPACT_PHONE_V3 = 3
    const val CURRENT = COMPACT_PHONE_V3

    fun sanitize(value: Int, fallback: Int): Int = when (value) {
        LEGACY_V2, COMPACT_PHONE_V3 -> value
        else -> fallback
    }
}

data class HudProfile(
    val mode: HudProfileMode,
    val globalScale: Float,
    val placements: Map<HudElementId, HudPlacement>,
    val defaultPolicyVersion: Int = HudDefaultPolicy.CURRENT,
)

data class PlayerHudConfig(
    val version: Int = 2,
    val portrait: HudProfile,
    val landscape: HudProfile,
)

fun PlayerHudConfig.profile(orientation: HudOrientation): HudProfile = when (orientation) {
    HudOrientation.PORTRAIT -> portrait
    HudOrientation.LANDSCAPE -> landscape
}

object PlayerHudDefaults {
    fun config(): PlayerHudConfig = PlayerHudConfig(
        portrait = HudProfile(HudProfileMode.DEFAULT, 1f, emptyMap()),
        landscape = HudProfile(HudProfileMode.DEFAULT, 1f, emptyMap()),
    )
}
