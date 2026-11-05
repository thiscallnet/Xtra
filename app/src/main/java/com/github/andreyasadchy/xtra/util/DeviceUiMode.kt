package com.github.andreyasadchy.xtra.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

internal fun isTelevisionUiMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

internal fun isTelevisionDevice(uiMode: Int, supportsTelevisionFeature: Boolean): Boolean =
    isTelevisionUiMode(uiMode) || supportsTelevisionFeature

private object TelevisionFeatureCache {
    @Volatile
    private var supported: Boolean? = null

    @Suppress("DEPRECATION")
    fun isSupported(packageManager: PackageManager): Boolean =
        supported ?: synchronized(this) {
            supported ?: (
                packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY) ||
                    packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
                ).also { supported = it }
        }
}

fun Context.isTelevision(): Boolean {
    val uiMode = resources.configuration.uiMode
    return isTelevisionDevice(
        uiMode = uiMode,
        supportsTelevisionFeature = TelevisionFeatureCache.isSupported(packageManager),
    )
}
