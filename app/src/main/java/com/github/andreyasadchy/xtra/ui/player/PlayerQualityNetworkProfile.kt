package com.github.andreyasadchy.xtra.ui.player

import android.content.SharedPreferences
import android.net.NetworkCapabilities
import com.github.andreyasadchy.xtra.util.C

internal enum class PlayerQualityNetworkProfile {
    WIFI,
    METERED_WIFI,
    CELLULAR;

    fun qualityPreference(preferences: SharedPreferences): String? = when (this) {
        WIFI -> preferences.getString(C.PLAYER_DEFAULT_QUALITY, "saved")
        CELLULAR -> preferences.getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved")
        METERED_WIFI -> {
            val meteredWifiQuality = preferences.getString(
                C.PLAYER_DEFAULT_METERED_WIFI_QUALITY,
                USE_MOBILE_DATA_QUALITY,
            )
            if (meteredWifiQuality == USE_MOBILE_DATA_QUALITY) {
                preferences.getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved")
            } else {
                meteredWifiQuality
            }
        }
    }

    companion object {
        const val USE_MOBILE_DATA_QUALITY = "mobile_data"

        fun from(networkCapabilities: NetworkCapabilities?): PlayerQualityNetworkProfile = when {
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> CELLULAR
            networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> METERED_WIFI
            else -> WIFI
        }
    }
}
