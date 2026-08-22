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
        nowMs: Long,
        staleAfterMs: Long,
    ): MediaPreloadPlan {
        val desired = candidates.associateBy { it.channelLogin.trim().lowercase() }
        val retained = mutableListOf<MediaPreloadPlanEntry>()
        val removed = mutableListOf<MediaPreloadPlanEntry>()
        val added = mutableListOf<MediaPreloadPlanEntry>()
        existing.forEach { current ->
            val next = desired[current.channelLogin.trim().lowercase()]
            val stale = current.rank == 0 && current.samplesLoadedAtMs != null &&
                nowMs - current.samplesLoadedAtMs >= staleAfterMs
            if (next == null || next.url != current.url || next.rank != current.rank || stale) {
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
    fun isUsable(
        entry: MediaPreloadPlanEntry?,
        requestedChannelLogin: String,
        requestedUrl: String,
        configurationMatches: Boolean,
        nowMs: Long,
        staleAfterMs: Long,
    ): Boolean {
        if (!configurationMatches || entry == null) return false
        if (!entry.channelLogin.equals(requestedChannelLogin, ignoreCase = true)) return false
        if (entry.url != requestedUrl) return false
        val age = nowMs - (entry.samplesLoadedAtMs ?: entry.addedAtMs)
        return entry.rank != 0 || age < staleAfterMs
    }
}
