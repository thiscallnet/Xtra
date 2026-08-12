package com.github.andreyasadchy.xtra.ui.multiview.ui

enum class MultiviewLayoutMode {
    AUTO,
    GRID,
    FOCUS,
}

data class TilePlacement(
    val identity: String,
    val row: Int,
    val column: Int,
    val rowSpan: Int = 1,
    val columnSpan: Int = 1,
)

data class MultiviewLayoutPlan(
    val rowCount: Int,
    val columnCount: Int,
    val placements: List<TilePlacement>,
)

object MultiviewLayoutManager {
    fun plan(
        identities: List<String>,
        activeIdentity: String?,
        mode: MultiviewLayoutMode,
        focusedIdentity: String?,
        landscape: Boolean,
    ): MultiviewLayoutPlan {
        val ids = identities.distinct()
        if (ids.isEmpty()) return MultiviewLayoutPlan(0, 0, emptyList())
        return when (mode) {
            MultiviewLayoutMode.GRID -> grid(ids, landscape)
            MultiviewLayoutMode.FOCUS -> focus(ids, focusedIdentity ?: activeIdentity ?: ids.first(), landscape)
            MultiviewLayoutMode.AUTO -> auto(ids, activeIdentity ?: ids.first(), landscape)
        }
    }

    private fun auto(ids: List<String>, active: String, landscape: Boolean): MultiviewLayoutPlan {
        val primary = active.takeIf(ids::contains) ?: ids.first()
        val rest = ids.filterNot { it == primary }
        return when (ids.size) {
            1 -> MultiviewLayoutPlan(1, 1, listOf(TilePlacement(primary, 0, 0)))
            2 -> grid(ids, landscape)
            3 -> if (landscape) {
                MultiviewLayoutPlan(
                    rowCount = 2,
                    columnCount = 2,
                    placements = listOf(
                        TilePlacement(primary, 0, 0, rowSpan = 2),
                        TilePlacement(rest[0], 0, 1),
                        TilePlacement(rest[1], 1, 1),
                    ),
                )
            } else {
                MultiviewLayoutPlan(
                    rowCount = 2,
                    columnCount = 2,
                    placements = listOf(
                        TilePlacement(primary, 0, 0, columnSpan = 2),
                        TilePlacement(rest[0], 1, 0),
                        TilePlacement(rest[1], 1, 1),
                    ),
                )
            }
            else -> grid(listOf(primary) + rest, landscape)
        }
    }

    private fun grid(ids: List<String>, landscape: Boolean): MultiviewLayoutPlan {
        val columns = when {
            ids.size == 1 -> 1
            landscape -> 2
            ids.size == 2 -> 1
            else -> 2
        }
        val rows = (ids.size + columns - 1) / columns
        return MultiviewLayoutPlan(
            rowCount = rows,
            columnCount = columns,
            placements = ids.mapIndexed { index, identity ->
                TilePlacement(identity, index / columns, index % columns)
            },
        )
    }

    private fun focus(ids: List<String>, focused: String, landscape: Boolean): MultiviewLayoutPlan {
        val primary = focused.takeIf(ids::contains) ?: ids.first()
        val rest = ids.filterNot { it == primary }
        if (rest.isEmpty()) return MultiviewLayoutPlan(1, 1, listOf(TilePlacement(primary, 0, 0)))
        // Keep a two-stream focused view edge-to-edge. With a single
        // thumbnail, GridLayout otherwise sizes its one weighted column to
        // the child's measured width and leaves large black side gutters.
        val columns = rest.size.coerceAtLeast(2).coerceAtMost(3)
        val thumbnailSpan = if (rest.size == 1) columns else 1
        return MultiviewLayoutPlan(
            rowCount = 2,
            columnCount = columns,
            placements = listOf(TilePlacement(primary, 0, 0, columnSpan = columns)) + rest.mapIndexed {
                    index,
                    identity,
                -> TilePlacement(identity, 1, index, columnSpan = thumbnailSpan) },
        )
    }
}
