package com.github.andreyasadchy.xtra.ui.player.hud

import kotlin.math.max

data class ResolvedHudElement(
    val id: HudElementId,
    val visualRect: HudRect,
    val hitRect: HudRect,
    val effectiveScale: Float,
)

class HudLayoutEngine(
    private val density: Float = 1f,
    private val rtl: Boolean = false,
    private val television: Boolean = false,
    private val televisionEdgePadding: Float = HudDefaultLayout.TV_EDGE_PADDING * density,
) {
    fun metadataWidthBudget(
        safeRect: HudRect,
        orientation: HudOrientation,
        profile: HudProfile,
        availability: Set<HudElementId>,
    ): Float {
        val maxWidth = 420f * density
        if (profile.mode != HudProfileMode.DEFAULT) return maxWidth

        val compact = safeRect.height < 260f * density
        val edge = if (television) televisionEdgePadding else HudDefaultLayout.NORMAL_EDGE_PADDING * density
        val gap = HudDefaultLayout.SPACING * density
        val topEnd = HudDefaultLayout.topEndElements(
            orientation,
            safeRect.width / density.coerceAtLeast(0.001f),
        ).filter { id ->
            id in availability && HudDefaultLayout.enabledByDefault(
                id,
                orientation,
                safeRect.width / density.coerceAtLeast(0.001f),
                compact,
            )
        }
        val topEndWidth = topEnd.sumOf { id ->
            val spec = HudElementRegistry.get(id)
            val visual = spec.visualSize(compact)
            val scale = profile.globalScale.takeIf(Float::isFinite)?.coerceIn(0.85f, 1.30f) ?: 1f
            maxOf(visual.width * density * scale, spec.minimumHitSize.width * density)
                .toDouble()
        }.toFloat() + (topEnd.size.coerceAtLeast(1) - 1) * gap
        return maxWidth.coerceAtMost(
            (safeRect.width - 2f * edge - topEndWidth - gap).coerceAtLeast(0f),
        )
    }

    fun resolve(
        safeRect: HudRect,
        orientation: HudOrientation,
        profile: HudProfile,
        measuredVisualSizes: Map<HudElementId, HudSize>,
        minimumHitSizes: Map<HudElementId, HudSize> = emptyMap(),
        availability: Set<HudElementId> = HudElementId.entries.toSet(),
    ): List<ResolvedHudElement> {
        val safe = safeRect
        val compact = safe.height < 260f * density
        val measuredSizes = measuredVisualSizes.withMetadataFit(
            safeRect = safe,
            orientation = orientation,
            profile = profile,
            availability = availability,
        )
        val defaultPlacements = HudDefaultLayout.resolve(
            safeRect = safe,
            orientation = orientation,
            profile = profile.copy(mode = HudProfileMode.DEFAULT),
            measuredVisualSizes = measuredSizes,
            availability = availability,
            minimumHitSizes = minimumHitSizes,
            density = density,
            rtl = rtl,
            television = television,
            televisionEdgePadding = televisionEdgePadding,
        )
        // A custom profile is a sparse override layer over the same responsive
        // default. An element is only independent after its placement is
        // explicitly written by the editor.
        val resolvedPlacements = if (profile.mode == HudProfileMode.DEFAULT) {
            defaultPlacements
        } else {
            defaultPlacements + profile.placements
        }
        val ordered = HudElementId.entries
        return ordered.mapNotNull { id ->
            val spec = HudElementRegistry.get(id)
            val placement = resolvedPlacements[id] ?: return@mapNotNull null
            if (!placement.enabled || id !in availability) return@mapNotNull null
            val elementScale = spec.clampScale(placement.scale)
            val globalScale = profile.globalScale.takeIf(Float::isFinite)?.coerceIn(0.85f, 1.30f) ?: 1f
            val measuredSize = if (id == HudElementId.STREAM_INFO || id == HudElementId.TIMELINE) {
                measuredSizes[id] ?: spec.visualSize(compact).let { HudSize(it.width * density, it.height * density) }
            } else {
                spec.visualSize(compact).let { HudSize(it.width * density, it.height * density) }
            }
            val baseWidth = if (id == HudElementId.TIMELINE && measuredSize.width <= 0f) safe.width else measuredSize.width
            val baseSize = HudSize(baseWidth, measuredSize.height)
            val rawScale = globalScale * elementScale
            val scaledBaseSize = HudSize(
                baseSize.width * rawScale,
                baseSize.height * rawScale,
            )
            val viewportFitScale = listOf(
                if (scaledBaseSize.width > 0f) safe.width / scaledBaseSize.width else 1f,
                if (scaledBaseSize.height > 0f) safe.height / scaledBaseSize.height else 1f,
                1f,
            ).minOrNull()?.coerceAtMost(1f) ?: 1f
            val effectiveScale = rawScale * viewportFitScale
            val visualSize = HudSize(
                baseSize.width * effectiveScale,
                baseSize.height * effectiveScale,
            )
            val minimum = minimumHitSizes[id]
                ?: spec.minimumHitSize.let { HudSize(it.width * density, it.height * density) }
            val hitSize = HudSize(
                max(visualSize.width, minimum.width),
                max(visualSize.height, minimum.height),
            )
            val rawVisualRect = visualRect(safe, placement, spec.pivot, visualSize)
            val rawHitRect = rawVisualRect.expandedTo(
                hitSize.width.coerceAtMost(safe.width),
                hitSize.height.coerceAtMost(safe.height),
            )
            // Clamp the visual and hit rectangles independently. Expanding a
            // top-row hit target must not move its visual sibling down by a
            // different amount, which was the source of the misaligned top
            // controls. The semantic pivot remains stable while hit padding
            // is absorbed inside the safe rectangle.
            val visual = rawVisualRect.clampInside(safe)
            val hit = rawHitRect.clampInside(safe)
            ResolvedHudElement(id, visual, hit, effectiveScale)
        }
    }

    private fun Map<HudElementId, HudSize>.withMetadataFit(
        safeRect: HudRect,
        orientation: HudOrientation,
        profile: HudProfile,
        availability: Set<HudElementId>,
    ): Map<HudElementId, HudSize> {
        if (HudElementId.STREAM_INFO !in availability) return this
        val metadata = this[HudElementId.STREAM_INFO] ?: return this
        val width = metadata.width.coerceAtMost(
            metadataWidthBudget(safeRect, orientation, profile, availability),
        )
        val height = if (safeRect.height < 260f * density) {
            metadata.height.coerceAtMost(46f * density)
        } else {
            metadata.height
        }
        return if (width == metadata.width && height == metadata.height) {
            this
        } else {
            this + (HudElementId.STREAM_INFO to metadata.copy(width = width, height = height))
        }
    }

    private fun visualRect(
        safeRect: HudRect,
        placement: HudPlacement,
        pivot: HudPivot,
        size: HudSize,
    ): HudRect {
        val x = safeRect.left + safeRect.width * placement.x.coerceIn(0f, 1f)
        val y = safeRect.top + safeRect.height * placement.y.coerceIn(0f, 1f)
        return when (pivot) {
            HudPivot.CENTER -> HudRect(
                x - size.width / 2f,
                y - size.height / 2f,
                x + size.width / 2f,
                y + size.height / 2f,
            )
            HudPivot.TOP_START -> if (rtl) {
                HudRect(x - size.width, y, x, y + size.height)
            } else {
                HudRect(x, y, x + size.width, y + size.height)
            }
            HudPivot.BOTTOM_CENTER -> HudRect(
                x - size.width / 2f,
                y - size.height,
                x + size.width / 2f,
                y,
            )
        }
    }
}
