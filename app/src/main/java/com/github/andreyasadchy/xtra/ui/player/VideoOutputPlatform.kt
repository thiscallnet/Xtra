package com.github.andreyasadchy.xtra.ui.player

import android.view.MotionEvent
import android.view.View

/**
 * Forward a SurfaceView gesture to the existing player layer so HUD controls
 * and drag gestures keep working when the surface receives the touch sequence.
 */
internal fun forwardVideoSurfaceTouch(
    source: View,
    target: View,
    event: MotionEvent,
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
        target.dispatchTouchEvent(forwarded)
    } finally {
        forwarded.recycle()
    }
}
