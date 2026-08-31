package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.content.res.Configuration

internal fun isTelevisionUiMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

fun Context.isTelevision(): Boolean = isTelevisionUiMode(resources.configuration.uiMode)
