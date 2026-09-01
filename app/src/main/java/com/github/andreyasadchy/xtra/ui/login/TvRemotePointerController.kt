package com.github.andreyasadchy.xtra.ui.login

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

internal class TvRemotePointerController(
    private val root: ViewGroup,
    private val target: View,
    private val cursor: View,
) {
    private var x = 0f
    private var y = 0f
    private var initialized = false

    fun initialize() {
        root.post {
            if (root.width == 0 || root.height == 0) return@post
            x = (root.width - cursor.width) / 2f
            y = (root.height - cursor.height) / 2f
            applyPosition()
            initialized = true
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in setOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
            )) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (!initialized) {
            initialize()
            return true
        }
        val step = root.resources.displayMetrics.density * when {
            event.repeatCount >= 8 -> 52f
            event.repeatCount >= 3 -> 38f
            else -> 28f
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(step, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> move(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0f, step)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> tap()
        }
        return true
    }

    private fun move(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(0f, (root.width - cursor.width).coerceAtLeast(0).toFloat())
        y = (y + dy).coerceIn(0f, (root.height - cursor.height).coerceAtLeast(0).toFloat())
        applyPosition()
    }

    private fun applyPosition() {
        cursor.translationX = x
        cursor.translationY = y
    }

    private fun tap() {
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val targetLocation = IntArray(2)
        target.getLocationOnScreen(targetLocation)
        val tapX = location[0] + x + cursor.width / 2f - targetLocation[0]
        val tapY = location[1] + y + cursor.height / 2f - targetLocation[1]
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, tapX, tapY, 0)
        val up = MotionEvent.obtain(downTime, downTime + 16L, MotionEvent.ACTION_UP, tapX, tapY, 0)
        try {
            target.dispatchTouchEvent(down)
            target.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }
}
