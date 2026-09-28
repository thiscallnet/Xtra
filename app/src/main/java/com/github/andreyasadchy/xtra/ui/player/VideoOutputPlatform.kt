package com.github.andreyasadchy.xtra.ui.player

import android.os.Build
import android.view.MotionEvent
import android.view.View
import java.util.Locale

/** TextureView keeps the output attached to the app window across emulator activity transitions. */
internal fun shouldUseTextureVideoOutput(): Boolean = isAndroidEmulator()

/**
 * SurfaceView can become the touch target for its video bounds on emulators.
 * Forward that gesture to the existing player layer so HUD controls and drag
 * gestures keep the same behavior as they have with TextureView.
 */
internal fun forwardVideoSurfaceTouch(
    source: View,
    target: View,
    event: MotionEvent,
): Boolean = forwardVideoTouch(source, target, event) { forwarded ->
    target.dispatchTouchEvent(forwarded)
}

/** Forward a SurfaceView gesture to a clickable ancestor without redispatching through the source. */
internal fun forwardVideoSurfaceTouchToView(
    source: View,
    target: View,
    event: MotionEvent,
): Boolean = forwardVideoTouch(source, target, event) { forwarded ->
    target.onTouchEvent(forwarded)
}

private inline fun forwardVideoTouch(
    source: View,
    target: View,
    event: MotionEvent,
    dispatch: (MotionEvent) -> Boolean,
): Boolean {
    val sourceLocation = IntArray(2)
    val targetLocation = IntArray(2)
    source.getLocationOnScreen(sourceLocation)
    target.getLocationOnScreen(targetLocation)
    val forwarded = MotionEvent.obtain(event)
    return try {
        forwarded.offsetLocation(
            (sourceLocation[0] - targetLocation[0]).toFloat(),
            (sourceLocation[1] - targetLocation[1]).toFloat(),
        )
        dispatch(forwarded)
    } finally {
        forwarded.recycle()
    }
}

internal fun isAndroidEmulator(): Boolean {
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
