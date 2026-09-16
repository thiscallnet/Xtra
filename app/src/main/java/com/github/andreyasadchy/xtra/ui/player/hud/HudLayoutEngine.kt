package com.github.andreyasadchy.xtra.ui.player.hud

import kotlin.math.max

data class ResolvedHudElement(
    val id: HudElementId,
    val visualRect: HudRect,
    val hitRect: HudRect,
    val effectiveScale: Float,
)

enum class HudEditorGuideAxis {
    VERTICAL,
    HORIZONTAL,
}

enum class HudEditorGuideKind {
    SAFE_EDGE,
    SAFE_CENTER,
    SIBLING_CENTER,
    CONTROL_BASELINE,
}

data class HudEditorGuide(
    val axis: HudEditorGuideAxis,
    val coordinate: Float,
    val kind: HudEditorGuideKind,
    val source: HudElementId? = null,
)

data class HudEditorSnapPreview(
    val raw: HudPlacement,
    val snapped: HudPlacement,
    val guides: List<HudEditorGuide>,
)

enum class HudEditorDropKind {
    RAW,
    SNAPPED,
    NUDGED,
    REJECTED,
}

data class HudEditorDropResult(
    val profile: HudProfile?,
    val selectedPlacement: HudPlacement?,
    val movedElements: Set<HudElementId>,
    val kind: HudEditorDropKind,
    val guides: List<HudEditorGuide>,
    val blockers: Set<HudElementId>,
    val explanation: String?,
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
        val maxWidth = (if (orientation == HudOrientation.PORTRAIT) 280f else 420f) * density
        // A sparse CUSTOM profile still inherits the current responsive
        // placement for metadata. Only a profile that explicitly moved or
        // resized metadata may opt out of the top-end reservation; otherwise
        // migrated users could get the old wide title block overlapping the
        // packed Quality/Captions/More row.
        val metadataExplicitlyCustomized = profile.mode == HudProfileMode.CUSTOM &&
            HudElementId.STREAM_INFO in profile.placements
        if (metadataExplicitlyCustomized) return maxWidth.coerceAtMost(safeRect.width)

        val compact = safeRect.height < 260f * density
        val edge = if (television) {
            televisionEdgePadding
        } else if (compact) {
            HudDefaultLayout.COMPACT_HORIZONTAL_PADDING * density
        } else {
            HudDefaultLayout.NORMAL_EDGE_PADDING * density
        }
        val gap = HudDefaultLayout.SPACING * density
        val topEnd = HudDefaultLayout.topEndElements(
            orientation,
            safeRect.width / density.coerceAtLeast(0.001f),
            profile.defaultPolicyVersion,
            compact,
        ).filter { id ->
            id in availability && HudDefaultLayout.enabledByDefault(
                id,
                orientation,
                safeRect.width / density.coerceAtLeast(0.001f),
                compact,
                profile.defaultPolicyVersion,
            )
        }
        val topEndWidth = topEnd.sumOf { id ->
            val spec = HudElementRegistry.get(id)
            val visual = spec.visualSize(compact)
            val scale = HudScale.effective(
                profile.globalScale,
                spec.clampScale(profile.placements[id]?.scale ?: 1f),
            )
            // The metadata column shares the visible top row. Hit targets are
            // deliberately larger than the icons and may overlap; reserving
            // them here would recreate the old sparse, truncated layout.
            visual.width * density * scale
                .toDouble()
        }.toFloat() + (topEnd.size.coerceAtLeast(1) - 1) * gap
        // Keep the metadata's visible rectangle one visual gap away from the
        // nearest visible top-end control. The enlarged hit rectangles are
        // allowed to overlap this gap and are resolved by child order.
        val widthBudget = (safeRect.width - 2f * edge - topEndWidth - gap)
            .coerceAtLeast(0f)
        return maxWidth.coerceAtMost(widthBudget).coerceAtMost(safeRect.width)
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
        val globalScale = HudScale.clampGlobal(profile.globalScale)
        val compactTransportVisualHeight = if (compact) {
            listOf(
                HudElementId.SEEK_BACK,
                HudElementId.PLAY_PAUSE,
                HudElementId.SEEK_FORWARD,
            ).filter { it in availability }
                .maxOfOrNull { id ->
                    val spec = HudElementRegistry.get(id)
                    spec.visualSize(true).height * density * HudScale.effective(
                        globalScale,
                        spec.clampScale(profile.placements[id]?.scale ?: 1f),
                    )
                }
                ?: 0f
        } else {
            0f
        }
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
            defaultPlacements + profile.placements.filterKeys { HudElementRegistry.get(it).isMovable }
        }
        val ordered = HudElementId.entries
        return ordered.mapNotNull { id ->
            val spec = HudElementRegistry.get(id)
            val placement = resolvedPlacements[id] ?: return@mapNotNull null
            if (!placement.enabled || id !in availability) return@mapNotNull null
            val elementScale = if (spec.isMovable) spec.clampScale(placement.scale) else 1f
            val measuredSize = if (id == HudElementId.STREAM_INFO || id == HudElementId.TIMELINE) {
                measuredSizes[id] ?: spec.visualSize(compact).let { HudSize(it.width * density, it.height * density) }
            } else {
                spec.visualSize(compact).let { HudSize(it.width * density, it.height * density) }
            }
            val baseWidth = if (id == HudElementId.TIMELINE && measuredSize.width <= 0f) safe.width else measuredSize.width
            val baseSize = HudSize(baseWidth, measuredSize.height)
            val rawScale = HudScale.effective(globalScale, elementScale)
            val scaledBaseSize = HudSize(
                baseSize.width * rawScale,
                baseSize.height * rawScale,
            )
            val viewportFitScale = listOf(
                if (scaledBaseSize.width > 0f) safe.width / scaledBaseSize.width else 1f,
                if (scaledBaseSize.height > 0f) safe.height / scaledBaseSize.height else 1f,
                1f,
            ).minOrNull()?.coerceAtMost(1f) ?: 1f
            val compactMetadataFitScale = if (compact && id == HudElementId.STREAM_INFO) {
                minOf(
                    1f,
                    ((safe.centerY - compactTransportVisualHeight / 2f -
                        HudDefaultLayout.SPACING * density -
                        (safe.top + HudDefaultLayout.COMPACT_VERTICAL_PADDING * density))
                        .coerceAtLeast(0f) /
                        (measuredSize.height * rawScale).coerceAtLeast(1f)),
                )
            } else {
                1f
            }
            val effectiveScale = rawScale * minOf(viewportFitScale, compactMetadataFitScale)
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
        // A two-line title plus the details row is taller than the compact
        // avatar. Never force the composition into a smaller frame: doing so
        // clips the details row (including the viewer count) inside the XML
        // hierarchy before the frame can position it.
        val height = metadata.height
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
