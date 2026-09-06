package com.github.andreyasadchy.xtra.ui.chat.v2.recommendations

import java.util.Locale

data class FuzzyMatch(
    val score: Int,
    val matchedIndexes: List<Int>,
    val startIndex: Int,
    val span: Int,
    val gapCount: Int,
    val consecutivePairs: Int,
)

/** Matches every query character in order, while allowing gaps in the candidate. */
class FuzzySubsequenceMatcher {
    fun match(candidate: String, query: String): FuzzyMatch? {
        val normalizedCandidate = candidate.lowercase(Locale.ROOT)
        val normalizedQuery = query.lowercase(Locale.ROOT)
        if (normalizedCandidate.isEmpty() || normalizedQuery.isEmpty()) return null

        val indexes = ArrayList<Int>(normalizedQuery.length)
        var candidateIndex = 0
        normalizedQuery.forEach { queryChar ->
            val matchIndex = normalizedCandidate.indexOf(queryChar, candidateIndex)
            if (matchIndex < 0) return null
            indexes += matchIndex
            candidateIndex = matchIndex + 1
        }

        val start = indexes.first()
        val end = indexes.last()
        val span = end - start + 1
        val gapCount = span - indexes.size
        val consecutivePairs = indexes.zipWithNext().count { (left, right) -> right == left + 1 }
        val score = when {
            normalizedCandidate == normalizedQuery -> 1_000_000
            normalizedCandidate.startsWith(normalizedQuery) -> 800_000
            normalizedCandidate.contains(normalizedQuery) -> 700_000
            else -> 400_000
        } + consecutivePairs * 5_000 - gapCount * 750 - start * 1_000 - span

        return FuzzyMatch(
            score = score,
            matchedIndexes = indexes,
            startIndex = start,
            span = span,
            gapCount = gapCount,
            consecutivePairs = consecutivePairs,
        )
    }
}
