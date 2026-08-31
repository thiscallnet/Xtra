package com.github.andreyasadchy.xtra.ui.player

import android.os.Build
import java.util.Locale

internal fun shouldUseTextureViewForVideoOutput(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
    val model = Build.MODEL.lowercase(Locale.ROOT)
    val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
    val brand = Build.BRAND.lowercase(Locale.ROOT)
    val device = Build.DEVICE.lowercase(Locale.ROOT)
    val product = Build.PRODUCT.lowercase(Locale.ROOT)
    val hardware = Build.HARDWARE.lowercase(Locale.ROOT)

    return fingerprint.startsWith("generic") ||
        fingerprint.contains("emulator") ||
        model.contains("google_sdk") ||
        model.contains("emulator") ||
        model.contains("android sdk built for") ||
        manufacturer.contains("genymotion") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu") ||
        product.contains("sdk_gphone") ||
        product.contains("google_sdk") ||
        product.contains("emulator") ||
        (brand.startsWith("generic") && device.startsWith("generic"))
}
