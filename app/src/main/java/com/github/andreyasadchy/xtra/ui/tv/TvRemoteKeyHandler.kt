package com.github.andreyasadchy.xtra.ui.tv

import android.view.KeyEvent

interface TvRemoteKeyHandler {
    fun handleTvKeyEvent(event: KeyEvent): Boolean
}
