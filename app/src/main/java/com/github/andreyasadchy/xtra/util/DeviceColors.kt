package com.github.andreyasadchy.xtra.util

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Context
import android.os.Build

/** One UI 6.0/6.1 leaves Android 14's color roles blue while updating its tonal palettes.
 * https://github.com/material-components/material-components-android/issues/3924
 */
internal val needsSamsungPaletteCompatibility: Boolean by lazy {
    if (Build.VERSION.SDK_INT != Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        !Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    ) {
        false
    } else {
        samsungOneUiVersion() in 60000L..60100L
    }
}

internal fun Context.useSamsungPaletteCompatibility(): Boolean {
    if (!needsSamsungPaletteCompatibility) return false
    // Legacy tonal mappings cannot honor Android 14's contrast adjustments.
    // Recheck on each theme application because users can change contrast at runtime.
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        getSystemService(UiModeManager::class.java)?.contrast == 0f
}

@SuppressLint("PrivateApi")
private fun samsungOneUiVersion(): Long = runCatching {
    // Material's DynamicColors uses this same property to check Samsung support.
    Build::class.java.getDeclaredMethod("getLong", String::class.java).run {
        isAccessible = true
        invoke(null, "ro.build.version.oneui") as Long
    }
}.getOrDefault(-1L)
