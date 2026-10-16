package com.github.andreyasadchy.xtra.util

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.core.graphics.ColorUtils
import kotlin.math.atan2
import kotlin.math.hypot

internal data class DeviceColorPalette(
    val source: String,
    val contrast: Float? = null,
    val mismatchedFamilies: Int = 0,
    val systemPrimary: Int? = null,
    val palettePrimary: Int? = null,
) {
    val useLegacyPalette: Boolean get() = source == "wallpaper-palette"
}

internal object DeviceColorDiagnostics {
    @Volatile
    var lastApplied: String = "not_applied"
}

internal fun Context.deviceColorPalette(light: Boolean): DeviceColorPalette {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        return DeviceColorPalette("system-palette")
    }
    val contrast = getSystemService(UiModeManager::class.java)?.contrast
    val primary = getColor(if (light) android.R.color.system_primary_light else android.R.color.system_primary_dark)
    val palettePrimary = getColor(if (light) android.R.color.system_accent1_600 else android.R.color.system_accent1_200)
    // Legacy tonal mappings cannot honor customized contrast. Keep that choice intact.
    if (contrast != 0f) {
        return DeviceColorPalette("system-contrast", contrast, systemPrimary = primary, palettePrimary = palettePrimary)
    }
    val secondary = getColor(if (light) android.R.color.system_secondary_light else android.R.color.system_secondary_dark)
    val paletteSecondary = getColor(if (light) android.R.color.system_accent2_600 else android.R.color.system_accent2_200)
    val tertiary = getColor(if (light) android.R.color.system_tertiary_light else android.R.color.system_tertiary_dark)
    val paletteTertiary = getColor(if (light) android.R.color.system_accent3_600 else android.R.color.system_accent3_200)
    val mismatches = (if (paletteHueDiffers(primary, palettePrimary)) 1 else 0) +
        (if (paletteHueDiffers(secondary, paletteSecondary)) 1 else 0) +
        (if (paletteHueDiffers(tertiary, paletteTertiary)) 1 else 0)
    // A stale OEM role overlay can remain blue after the wallpaper palettes change.
    // Inspect the colors instead of relying on a vendor version or hidden system property.
    return DeviceColorPalette(
        if (mismatches >= 2) "wallpaper-palette" else "system-roles",
        contrast, mismatches, primary, palettePrimary,
    )
}

private fun paletteHueDiffers(role: Int, palette: Int): Boolean {
    if (role == palette) return false
    val expected = DoubleArray(3)
    ColorUtils.colorToLAB(palette, expected)
    // Hue is unstable near gray. Do not mistake neutral/monochrome palettes for stale roles.
    if (hypot(expected[1], expected[2]) < 8.0) return false
    val actual = DoubleArray(3)
    ColorUtils.colorToLAB(role, actual)
    if (hypot(actual[1], actual[2]) < 8.0) return true
    // Compare the CIE Lab hue vectors, independently of lightness.
    val cross = actual[1] * expected[2] - actual[2] * expected[1]
    val dot = actual[1] * expected[1] + actual[2] * expected[2]
    val difference = Math.toDegrees(atan2(kotlin.math.abs(cross), dot))
    // Allow tone/gamut rounding and small OEM adjustments; require two disagreeing families.
    return difference > 20.0
}

internal fun Int.deviceColorHex(): String = "#" + (this and 0xffffff).toString(16).padStart(6, '0')
