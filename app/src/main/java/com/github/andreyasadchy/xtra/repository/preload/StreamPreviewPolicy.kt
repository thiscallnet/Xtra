package com.github.andreyasadchy.xtra.repository.preload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

enum class StreamPreviewMode(val preferenceValue: String) {
    OFF("off"),
    WIFI_ONLY("wifi"),
    WIFI_AND_MOBILE("all"),
    ;

    companion object {
        fun fromPreference(value: String?): StreamPreviewMode =
            entries.firstOrNull { it.preferenceValue == value } ?: if (value == null) WIFI_AND_MOBILE else OFF
    }
}

enum class StreamPreviewQuality(val preferenceValue: String) {
    P360("360"),
    P480("480"),
    AUTO("auto"),
    ;

    companion object {
        fun fromPreference(value: String?): StreamPreviewQuality =
            entries.firstOrNull { it.preferenceValue == value } ?: P360
    }
}

enum class StreamPreviewDelay(val preferenceValue: String, val delayMs: Long) {
    IMMEDIATE("instant", 0L),
    FAST("fast", 750L),
    NORMAL("normal", 1_250L),
    CONSERVATIVE("conservative", 2_000L),
    ;

    companion object {
        fun fromPreference(value: String?): StreamPreviewDelay =
            entries.firstOrNull { it.preferenceValue == value } ?: if (value == null) IMMEDIATE else NORMAL
    }
}

object StreamPreviewPolicy {
    fun canStartPreview(
        isPlayerFullscreen: Boolean,
        networkAllowed: Boolean,
        handoffPending: Boolean,
    ): Boolean = !isPlayerFullscreen && networkAllowed && !handoffPending

    fun mode(context: Context): StreamPreviewMode {
        val preferences = context.prefs()
        return if (preferences.contains(C.STREAM_PREVIEW_MODE)) {
            StreamPreviewMode.fromPreference(preferences.getString(C.STREAM_PREVIEW_MODE, null))
        } else {
            StreamPreviewMode.WIFI_AND_MOBILE
        }
    }

    fun allowsMultiplePreviews(context: Context): Boolean =
        context.prefs().getBoolean(C.STREAM_PREVIEW_MULTIPLE, true)

    fun quality(context: Context): StreamPreviewQuality =
        StreamPreviewQuality.fromPreference(context.prefs().getString(C.STREAM_PREVIEW_QUALITY, StreamPreviewQuality.P360.preferenceValue))

    fun delay(context: Context): StreamPreviewDelay =
        StreamPreviewDelay.fromPreference(context.prefs().getString(C.STREAM_PREVIEW_DELAY, null))

    fun allowsNetwork(context: Context): Boolean {
        val mode = mode(context)
        if (mode == StreamPreviewMode.OFF) return false
        val powerManager = context.getSystemService(PowerManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && powerManager?.isPowerSaveMode == true) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return when (mode) {
            StreamPreviewMode.OFF -> false
            StreamPreviewMode.WIFI_ONLY -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            StreamPreviewMode.WIFI_AND_MOBILE -> capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }
}
