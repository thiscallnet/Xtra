package com.github.andreyasadchy.xtra.ui.statistics

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.navigation.NavBackStackEntry
import androidx.navigation.fragment.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.viewingstats.ViewingStatsRange
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

internal const val STATISTICS_RANGE_ARGUMENT = "statistics_range"

internal fun Bundle.statisticsRange(): ViewingStatsRange {
    return parseStatisticsRange(getString(STATISTICS_RANGE_ARGUMENT)) ?: ViewingStatsRange.LAST_7_DAYS
}

private fun parseStatisticsRange(name: String?): ViewingStatsRange? =
    ViewingStatsRange.entries.firstOrNull { it.name == name }

private fun Fragment.statisticsEntry(destinationId: Int): NavBackStackEntry? {
    // A synchronous tab restoration creates the fragment before pushing its navigation entry.
    val entry = try {
        findNavController().getBackStackEntry(destinationId)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return entry.takeIf { it.id == tag }
}

internal fun Fragment.initialStatisticsRange(destinationId: Int): ViewingStatsRange =
    parseStatisticsRange(statisticsEntry(destinationId)?.savedStateHandle?.get(STATISTICS_RANGE_ARGUMENT))
        ?: requireArguments().statisticsRange()

private val rangeChips = mapOf(
    R.id.range7 to ViewingStatsRange.LAST_7_DAYS,
    R.id.range30 to ViewingStatsRange.LAST_30_DAYS,
    R.id.range90 to ViewingStatsRange.LAST_90_DAYS,
    R.id.rangeYear to ViewingStatsRange.LAST_YEAR,
    R.id.rangeAll to ViewingStatsRange.ALL_TIME,
)

internal fun Fragment.bindStatisticsRangeSelection(
    group: ChipGroup,
    destinationId: Int,
    currentRange: () -> ViewingStatsRange,
    selectRange: (ViewingStatsRange) -> Unit,
) {
    group.check(rangeChips.entries.first { it.value == currentRange() }.key)
    rangeChips.forEach { (id, range) ->
        group.findViewById<Chip>(id).setOnClickListener {
            statisticsEntry(destinationId)?.savedStateHandle?.set(STATISTICS_RANGE_ARGUMENT, range.name)
            if (range != currentRange()) selectRange(range)
        }
    }
    val pageView = requireView()
    pageView.post {
        if (view !== pageView) return@post
        val range = parseStatisticsRange(
            statisticsEntry(destinationId)?.savedStateHandle?.get(STATISTICS_RANGE_ARGUMENT),
        ) ?: currentRange()
        // Programmatic checks and hierarchy restoration must not select a different period.
        group.check(rangeChips.entries.first { it.value == range }.key)
        if (range != currentRange()) selectRange(range)
    }
}
