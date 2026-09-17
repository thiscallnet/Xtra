package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.rawPrefs
import org.json.JSONObject

class HudConfigStore(private val context: Context) {
    fun load(): PlayerHudConfig {
        val raw = context.rawPrefs()
            .getString(KEY, null)
            ?: return PlayerHudDefaults.config()
        val decoded = HudConfigJson.decode(raw) ?: return PlayerHudDefaults.config()
        val migrated = HudConfigMigration.apply(decoded)
        if (migrated != decoded) save(migrated)
        return migrated
    }

    fun save(config: PlayerHudConfig) {
        context.rawPrefs().edit {
            putString(KEY, HudConfigJson.encode(config))
        }
    }

    fun resetPortrait() = save(load().copy(portrait = PlayerHudDefaults.config().portrait))

    fun resetLandscape() = save(load().copy(landscape = PlayerHudDefaults.config().landscape))

    fun resetBoth() = save(PlayerHudDefaults.config())

    fun loadTimelineTimePosition(): HudTimelineTimePosition =
        parseHudTimelineTimePosition(
            context.rawPrefs().getString(C.PLAYER_LIVE_REWIND_TIME_SIDE, null),
        )

    companion object {
        const val KEY = C.PLAYER_HUD_LAYOUT_V2
    }
}

internal object HudConfigJson {
    private const val CURRENT_VERSION = 2

    fun decode(raw: String): PlayerHudConfig? = runCatching {
        val json = JSONObject(raw)
        if (json.optInt("version", -1) != CURRENT_VERSION) return@runCatching null
        PlayerHudConfig(
            version = CURRENT_VERSION,
            portrait = readProfile(json.optJSONObject("portrait"), HudOrientation.PORTRAIT),
            landscape = readProfile(json.optJSONObject("landscape"), HudOrientation.LANDSCAPE),
            migrationVersion = json.optInt("migrationVersion", HudConfigMigration.INITIAL)
                .coerceAtLeast(HudConfigMigration.INITIAL),
        )
    }.getOrNull()

    fun encode(config: PlayerHudConfig): String = encodeConfig(config).toString()

    private fun readProfile(json: JSONObject?, orientation: HudOrientation): HudProfile {
        if (json == null) return HudProfile(
            HudProfileMode.DEFAULT,
            1f,
            emptyMap(),
            HudDefaultPolicy.CURRENT,
        )
        val mode = runCatching { HudProfileMode.valueOf(json.optString("mode")) }
            .getOrDefault(HudProfileMode.DEFAULT)
        val globalScale = json.optDouble("globalScale", 1.0).toFloat()
            .takeIf(Float::isFinite)?.let(HudScale::clampGlobal) ?: 1f
        val policyFallback = if (mode == HudProfileMode.DEFAULT) {
            HudDefaultPolicy.CURRENT
        } else {
            // v2 CUSTOM profiles did not record which responsive defaults they
            // inherited. Pin them to the old policy so a product-default
            // change cannot silently rearrange an existing sparse profile.
            HudDefaultPolicy.LEGACY_V2
        }
        val defaultPolicyVersion = HudDefaultPolicy.sanitize(
            json.optInt("defaultPolicyVersion", policyFallback),
            policyFallback,
        )
        if (mode == HudProfileMode.DEFAULT) {
            // DEFAULT has no user-authored placements, so it should always
            // receive the latest shipped responsive policy. A CUSTOM profile
            // keeps its explicit policy below for backwards compatibility.
            return HudProfile(mode, globalScale, emptyMap(), HudDefaultPolicy.CURRENT)
        }
        val elements = json.optJSONObject("elements")
        val placements = buildMap {
            if (elements != null) {
                for (key in elements.keys()) {
                    val id = runCatching { HudElementId.valueOf(key) }.getOrNull() ?: continue
                    // TIMELINE was a legacy serialized element. The purple
                    // progress line is now fixed chrome and has no profile
                    // geometry; old setups must remain importable.
                    if (id == HudElementId.TIMELINE) continue
                    val value = elements.optJSONObject(key) ?: continue
                    val spec = HudElementRegistry.get(id)
                    val x = value.optDouble("x", 0.5).toFloat().takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f
                    val y = value.optDouble("y", 0.5).toFloat().takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f
                    val scale = value.optDouble("scale", 1.0).toFloat()
                        .takeIf(Float::isFinite)?.let(spec::clampScale) ?: 1f
                    put(id, HudPlacement(value.optBoolean("enabled", false), x, y, scale))
                }
            }
        }
        // CUSTOM profiles contain only explicit overrides. Missing elements
        // inherit the responsive shared default in HudLayoutEngine, which
        // keeps portrait and landscape coupled until the user edits one.
        return HudProfile(mode, globalScale, placements, defaultPolicyVersion)
    }

    private fun encodeConfig(config: PlayerHudConfig): JSONObject = JSONObject().apply {
        put("version", CURRENT_VERSION)
        put("migrationVersion", config.migrationVersion.coerceAtLeast(HudConfigMigration.INITIAL))
        put("portrait", encodeProfile(config.portrait))
        put("landscape", encodeProfile(config.landscape))
    }

    private fun encodeProfile(profile: HudProfile): JSONObject = JSONObject().apply {
        put("mode", profile.mode.name)
        put("globalScale", HudScale.clampGlobal(profile.globalScale))
        val policyFallback = if (profile.mode == HudProfileMode.DEFAULT) {
            HudDefaultPolicy.CURRENT
        } else {
            HudDefaultPolicy.LEGACY_V2
        }
        put(
            "defaultPolicyVersion",
            HudDefaultPolicy.sanitize(profile.defaultPolicyVersion, policyFallback),
        )
        put("elements", JSONObject().apply {
            profile.placements.forEach { (id, placement) ->
                if (id == HudElementId.TIMELINE) return@forEach
                val spec = HudElementRegistry.get(id)
                put(id.name, JSONObject().apply {
                    put("enabled", placement.enabled)
                    put("x", placement.x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f)
                    put("y", placement.y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f)
                    put("scale", placement.scale.takeIf(Float::isFinite)?.let(spec::clampScale) ?: 1f)
                })
            }
        })
    }

}
