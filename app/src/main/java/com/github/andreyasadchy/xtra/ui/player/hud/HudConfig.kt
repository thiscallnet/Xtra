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
    if (value.equals("right", ignoreCase = true)) {
        HudTimelineTimePosition.RIGHT
    } else {
        // The compact player puts the live status beside the left-hand time
        // label. Keep an explicit "right" preference working, but make the
        // new-player default match that layout even before preferences have
        // materialized their XML default.
        HudTimelineTimePosition.LEFT
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
    const val RESPONSIVE_COMPLETE_V4 = 4
    const val FIXED_PLAYER_CHROME_V5 = 5
    const val CURRENT = FIXED_PLAYER_CHROME_V5

    fun sanitize(value: Int, fallback: Int): Int = when (value) {
        LEGACY_V2, COMPACT_PHONE_V3, RESPONSIVE_COMPLETE_V4, FIXED_PLAYER_CHROME_V5 -> value
        else -> fallback
    }
}

/**
 * A separate marker records whether the one-time repair for pre-V5 layouts
 * has run. The default policy can keep evolving without re-writing a user's
 * CUSTOM profile after this migration has completed.
 */
object HudConfigMigration {
    const val INITIAL = 0
    const val FIXED_PLAYER_CHROME_V5 = 1
    const val CURRENT = FIXED_PLAYER_CHROME_V5

    fun apply(config: PlayerHudConfig): PlayerHudConfig {
        if (config.migrationVersion >= CURRENT) return config
        return config.copy(
            portrait = migrateProfile(config.portrait),
            landscape = migrateProfile(config.landscape),
            migrationVersion = CURRENT,
        )
    }

    private fun migrateProfile(profile: HudProfile): HudProfile = when (profile.mode) {
        HudProfileMode.DEFAULT -> profile.copy(
            placements = emptyMap(),
            defaultPolicyVersion = HudDefaultPolicy.CURRENT,
        )
        HudProfileMode.CUSTOM -> profile.copy(
            // Keep every explicit placement, including old timeline data. The
            // layout engine now treats TIMELINE as fixed chrome, so stale
            // coordinates cannot move it, while preserving the data keeps the
            // migration lossless for shared setups and future recovery.
            defaultPolicyVersion = HudDefaultPolicy.CURRENT,
        )
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
    val migrationVersion: Int = HudConfigMigration.CURRENT,
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
