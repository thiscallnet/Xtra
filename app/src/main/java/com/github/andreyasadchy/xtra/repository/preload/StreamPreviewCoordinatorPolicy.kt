package com.github.andreyasadchy.xtra.repository.preload

/** Pure ownership decisions shared by the preview coordinator and its tests. */
object StreamPreviewCoordinatorPolicy {
    fun displacedActiveIdentities(
        bestCandidateVisibility: Map<String, Float>,
        activeIdentities: Set<String>,
        selectedIdentities: Set<String>,
        handoffIdentity: String? = null,
    ): Set<String> {
        val active = activeIdentities.mapTo(mutableSetOf(), StreamPreviewSelectionPolicy::normalizeIdentity)
        val selected = selectedIdentities.mapTo(mutableSetOf(), StreamPreviewSelectionPolicy::normalizeIdentity)
        val handoff = handoffIdentity?.let(StreamPreviewSelectionPolicy::normalizeIdentity)

        return bestCandidateVisibility.asSequence()
            .map { (identity, visibleFraction) ->
                StreamPreviewSelectionPolicy.normalizeIdentity(identity) to visibleFraction
            }
            .filter { (identity, visibleFraction) ->
                identity in active &&
                    identity !in selected &&
                    identity != handoff &&
                    visibleFraction >= StreamPreviewSelectionPolicy.STOP_VISIBLE_FRACTION
            }
            .mapTo(mutableSetOf()) { (identity, _) -> identity }
    }

    fun shouldCancelPendingStart(
        identity: String,
        selectedIdentities: Set<String>,
        activeIdentities: Set<String>,
    ): Boolean {
        val normalized = StreamPreviewSelectionPolicy.normalizeIdentity(identity)
        return normalized !in selectedIdentities.mapTo(mutableSetOf(), StreamPreviewSelectionPolicy::normalizeIdentity) ||
            normalized in activeIdentities.mapTo(mutableSetOf(), StreamPreviewSelectionPolicy::normalizeIdentity)
    }
}
