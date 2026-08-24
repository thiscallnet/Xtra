package com.github.andreyasadchy.xtra.repository.preload

data class MediaPreloadPlanEntry(
    val channelLogin: String,
    val url: String,
    val rank: Int,
    val samplesLoadedAtMs: Long? = null,
    val addedAtMs: Long = 0L,
)

data class MediaPreloadPlan(
    val retained: List<MediaPreloadPlanEntry>,
    val removed: List<MediaPreloadPlanEntry>,
    val added: List<MediaPreloadPlanEntry>,
)

object StreamMediaPreloadPlan {
    fun reconcile(
        existing: Collection<MediaPreloadPlanEntry>,
        candidates: Collection<MediaPreloadPlanEntry>,
    ): MediaPreloadPlan {
        val desired = candidates.associateBy { it.channelLogin.trim().lowercase() }
        val retained = mutableListOf<MediaPreloadPlanEntry>()
        val removed = mutableListOf<MediaPreloadPlanEntry>()
        val added = mutableListOf<MediaPreloadPlanEntry>()
        existing.forEach { current ->
            val next = desired[current.channelLogin.trim().lowercase()]
            // A live HLS preload remains useful while its candidate, URL, and
            // playback configuration are unchanged. Refreshing it on a timer
            // creates network and Media3 churn while the user is idle.
            if (next == null || next.url != current.url || next.rank != current.rank) {
                removed += current
            } else {
                retained += current
            }
        }
        desired.values.forEach { next ->
            if (retained.none { it.channelLogin.equals(next.channelLogin, true) }) added += next
        }
        return MediaPreloadPlan(retained, removed, added)
    }
}

object StreamMediaPreloadHandoff {
    const val MAX_PRELOADED_HANDOFF_AGE_MS = 5_000L

    fun isUsable(
        entry: MediaPreloadPlanEntry?,
        requestedChannelLogin: String,
        requestedUrl: String,
        configurationMatches: Boolean,
        nowMs: Long,
    ): Boolean {
        if (!configurationMatches || entry == null) return false
        if (!entry.channelLogin.equals(requestedChannelLogin, ignoreCase = true)) return false
        if (entry.url != requestedUrl) return false
        if (entry.rank == 0) {
            val loadedAtMs = entry.samplesLoadedAtMs ?: entry.addedAtMs
            val ageMs = (nowMs - loadedAtMs).coerceAtLeast(0L)
            if (ageMs > MAX_PRELOADED_HANDOFF_AGE_MS) return false
        }
        return true
    }
}
