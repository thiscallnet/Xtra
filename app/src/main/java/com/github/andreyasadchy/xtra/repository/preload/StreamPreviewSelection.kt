package com.github.andreyasadchy.xtra.repository.preload

data class StreamPreviewSelectionCandidate(
    val identity: String,
    val visibleFraction: Float,
    val centerProximity: Float,
    val order: Int,
)

/** Deterministic, bounded selection for muted in-feed previews. */
object StreamPreviewSelectionPolicy {
    const val MAX_ACTIVE_PREVIEWS = 2
    const val START_VISIBLE_FRACTION = 0.33f
    const val STOP_VISIBLE_FRACTION = 0.12f
    const val ACTIVE_VISIBILITY_BIAS = 0.08f

    fun select(
        candidates: Collection<StreamPreviewSelectionCandidate>,
        activeIdentities: Set<String>,
        maxActivePreviews: Int = MAX_ACTIVE_PREVIEWS,
    ): List<String> {
        val active = activeIdentities.mapTo(mutableSetOf(), ::normalizeIdentity)
        val ordered = candidates
            .map { it.copy(identity = normalizeIdentity(it.identity)) }
            .filter { it.identity.isNotEmpty() }
            .groupBy { it.identity }
            .values
            .map { sameIdentity -> sameIdentity.minWithOrNull(candidateComparator)!! }
            .sortedWith(candidateComparator(active))
        val eligible = ordered.filter { candidate ->
            candidate.visibleFraction >= START_VISIBLE_FRACTION ||
                (candidate.identity in active && candidate.visibleFraction >= STOP_VISIBLE_FRACTION)
        }

        return eligible
            .take(maxActivePreviews.coerceAtLeast(1))
            .map { it.identity }
    }

    fun normalizeIdentity(identity: String): String = identity.trim().lowercase()

    private val candidateComparator = compareByDescending<StreamPreviewSelectionCandidate> {
        it.visibleFraction.coerceIn(0f, 1f)
    }.thenByDescending {
        it.centerProximity.coerceIn(0f, 1f)
    }.thenBy {
        it.order
    }

    private fun candidateComparator(active: Set<String>) =
        compareByDescending<StreamPreviewSelectionCandidate> {
            it.visibleFraction.coerceIn(0f, 1f) +
                if (it.identity in active) ACTIVE_VISIBILITY_BIAS else 0f
        }.thenByDescending {
            it.visibleFraction.coerceIn(0f, 1f)
        }.thenByDescending {
            it.centerProximity.coerceIn(0f, 1f)
        }.thenBy {
            it.order
        }
}
