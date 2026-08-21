package com.github.andreyasadchy.xtra.repository.preload

import kotlin.math.abs

enum class StreamPreloadMode(val preferenceValue: String) {
    OFF("off"),
    WIFI_ONLY("wifi"),
    WIFI_AND_MOBILE("all"),
    ;

    companion object {
        fun fromPreference(value: String?): StreamPreloadMode =
            entries.firstOrNull { it.preferenceValue == value } ?: WIFI_ONLY
    }
}

data class StreamPreloadCandidate(
    val streamKey: String,
    val channelLogin: String,
    val visibleFraction: Float,
    val centerProximity: Float,
)

object StreamPreloadPolicy {
    const val MAX_URL_CANDIDATES = 3
    const val MAX_RESOLVER_CONCURRENCY = 2
    const val URL_DWELL_MS = 350L
    const val URL_TTL_MS = 30_000L
    const val EVICTION_GRACE_MS = 1_500L
    const val FAILURE_BACKOFF_MS = 5_000L
    const val SCORE_VISIBILITY_WEIGHT = 0.65f
    const val SCORE_CENTER_WEIGHT = 0.35f

    fun score(candidate: StreamPreloadCandidate): Float {
        return SCORE_VISIBILITY_WEIGHT * candidate.visibleFraction.coerceIn(0f, 1f) +
            SCORE_CENTER_WEIGHT * candidate.centerProximity.coerceIn(0f, 1f)
    }

    fun allowsTwitchUrlPreload(customStreamProxyEnabled: Boolean, customStreamProxyUrl: String?): Boolean =
        !customStreamProxyEnabled || customStreamProxyUrl.isNullOrBlank()

    fun rank(candidates: Collection<StreamPreloadCandidate>): List<StreamPreloadCandidate> {
        return candidates
            .filter { it.channelLogin.isNotBlank() }
            .groupBy { it.channelLogin.trim().lowercase() }
            .values
            .map { sameChannel -> sameChannel.maxBy { score(it) } }
            .sortedWith(
                compareByDescending<StreamPreloadCandidate> { score(it) }
                    .thenBy { abs(1f - it.centerProximity) },
            )
    }
}
