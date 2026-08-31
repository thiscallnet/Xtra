package com.github.andreyasadchy.xtra.ui.tv

import android.view.KeyEvent

internal enum class TvPlayerCommand { ShowControls, SeekBack, SeekForward }

internal fun tvPlayerCommand(keyCode: Int, controlsVisible: Boolean): TvPlayerCommand? {
    if (controlsVisible) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> TvPlayerCommand.SeekBack
        KeyEvent.KEYCODE_DPAD_RIGHT -> TvPlayerCommand.SeekForward
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN -> TvPlayerCommand.ShowControls
        else -> null
    }
}
