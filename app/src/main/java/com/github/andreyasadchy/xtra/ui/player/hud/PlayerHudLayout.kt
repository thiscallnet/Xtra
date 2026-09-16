package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.isTelevision
import kotlin.math.abs
import kotlin.math.roundToInt

class PlayerHudLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {
    private companion object {
        const val PREVIEW_LIVE_TIME = "LIVE / 4:20:27"
    }

    private val density = resources.displayMetrics.density
    private val store = HudConfigStore(context)
    private var orientation = currentOrientation()
    private var profile = store.load().profile(orientation)
    private var safeInsets = Rect()
    private var safeInsetsOverride: Rect? = null
    private var availability: Set<HudElementId> = emptySet()
    private var resolved = emptyMap<HudElementId, ResolvedHudElement>()
    private val frames = linkedMapOf<HudElementId, HudElementFrame>()
    private var editing = false
    private var editorSelected: HudElementId? = null
    private var editorStartX = 0f
    private var editorStartY = 0f
    private var editorStartPlacement: HudPlacement? = null
    private var editorStartProfile: HudProfile? = null
    private var editorDragging = false
    private var compactMetricsApplied: Boolean? = null
    private var editorOnSelected: ((HudElementId) -> Unit)? = null
    private var editorOnDragStarted: ((HudElementId) -> Unit)? = null
    private var editorOnMoved: ((HudElementId, HudPlacement) -> Unit)? = null
    private var editorOnDropped: ((HudElementId, Boolean, HudPlacement?) -> Unit)? = null

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val system = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val next = Rect(system.left, system.top, system.right, system.bottom)
            if (next != safeInsets) {
                safeInsets = next
                requestLayout()
            }
            insets
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        frames.clear()
        for (index in 0 until childCount) {
            (getChildAt(index) as? HudElementFrame)?.let { frame ->
                val id = (frame.tag as? String)?.let { runCatching { HudElementId.valueOf(it) }.getOrNull() }
                if (id != null) frames[id] = frame
            }
        }
        availability = runtimeAvailability()
    }

    fun setHudOrientation(value: HudOrientation) {
        orientation = value
        profile = store.load().profile(value)
        refreshTimelineSettings()
        requestLayout()
    }

    /** Applies an editor state atomically so orientation changes cannot lay out an old profile first. */
    fun setHudOrientationAndProfile(value: HudOrientation, nextProfile: HudProfile) {
        orientation = value
        profile = nextProfile
        requestLayout()
    }

    fun reloadProfile() {
        profile = store.load().profile(orientation)
        refreshTimelineSettings()
        requestLayout()
    }

    fun setHudProfile(value: HudProfile) {
        profile = value
        requestLayout()
    }

    fun setSafeInsetsOverride(value: Rect?) {
        val next = value?.let(::Rect)
        if (safeInsetsOverride == next) return
        safeInsetsOverride = next
        requestLayout()
    }

    fun hudProfile(): HudProfile = profile

    fun setPreviewMode(preview: Boolean) {
        availability = if (preview) HudElementId.entries.toSet() else runtimeAvailability()
        if (preview) showPreviewContent() else restoreRuntimeContent()
        requestLayout()
    }

    fun refreshAvailability() {
        if (!refreshAvailabilityIfChanged()) requestLayout()
    }

    fun refreshAvailabilityIfChanged(): Boolean {
        val next = if (editing) HudElementId.entries.toSet() else runtimeAvailability()
        if (next == availability) return false
        availability = next
        requestLayout()
        return true
    }

    fun setLiveRewindEnabled(enabled: Boolean) {
        findViewById<HudTimelineContent>(R.id.timelineContent)?.apply {
            setLiveRewindTimePosition(store.loadTimelineTimePosition())
            setLiveRewindEnabled(enabled)
        }
    }

    fun resolvedElements(): List<ResolvedHudElement> = resolved.values.toList()

    fun safeHudRect(): HudRect = safeRect(width.toFloat(), height.toFloat())

    fun setEditing(
        enabled: Boolean,
        onSelected: ((HudElementId) -> Unit)? = null,
        onDragStarted: ((HudElementId) -> Unit)? = null,
        onMoved: ((HudElementId, HudPlacement) -> Unit)? = null,
        onDropped: ((HudElementId, Boolean, HudPlacement?) -> Unit)? = null,
    ) {
        editing = enabled
        editorOnSelected = onSelected
        editorOnDragStarted = onDragStarted
        editorOnMoved = onMoved
        editorOnDropped = onDropped
        availability = if (enabled) HudElementId.entries.toSet() else runtimeAvailability()
        requestLayout()
    }

    fun updateEditorPlacement(id: HudElementId, placement: HudPlacement) {
        if (!editing) return
        val base = if (profile.mode == HudProfileMode.DEFAULT) {
            materializedDefaultProfile()
        } else {
            profile
        }
        profile = base.copy(
            mode = HudProfileMode.CUSTOM,
            placements = base.placements + (id to placement),
        )
        requestLayout()
    }

    fun editorPlacement(id: HudElementId): HudPlacement? = profile.placements[id]

    fun defaultEditorPlacement(id: HudElementId): HudPlacement = defaultPlacement(id)

    fun materializedDefaultProfile(): HudProfile = HudProfile(
        mode = HudProfileMode.CUSTOM,
        globalScale = profile.globalScale,
        // CUSTOM is a sparse override layer. Keeping this empty until the
        // first actual edit preserves the shared default for the other
        // orientation and for every untouched element.
        placements = emptyMap(),
        defaultPolicyVersion = profile.defaultPolicyVersion,
    )

    fun elementFrame(id: HudElementId): HudElementFrame? = frames[id]

    fun isElementActive(id: HudElementId): Boolean = resolved.containsKey(id) && frames[id]?.isActive() == true

    fun isElementActive(view: View): Boolean {
        var current: View? = view
        while (current != null && current !== this) {
            if (current is HudElementFrame) {
                val id = (current.tag as? String)?.let { runCatching { HudElementId.valueOf(it) }.getOrNull() }
                return id != null && isElementActive(id)
            }
            current = current.parent as? View
        }
        return false
    }

    fun snapEditorPlacement(id: HudElementId): HudPlacement = editorSnapPreview(id).snapped

    /**
     * Computes prospective guides without changing the live placement. The
     * editor uses this while the finger is down and only commits [snapped] on
     * release.
     */
    fun editorSnapPreview(id: HudElementId, requested: HudPlacement? = null): HudEditorSnapPreview {
        val safe = safeRect(width.toFloat(), height.toFloat())
        val raw = clampEditorPlacement(id, requested ?: profile.placements[id] ?: defaultPlacement(id))
        val elements = editorElements(editorProfileWith(id, raw))
        val selected = elements.firstOrNull { it.id == id } ?: return HudEditorSnapPreview(raw, raw, emptyList())
        val spec = HudElementRegistry.get(id)
        val currentX = when (spec.pivot) {
            HudPivot.TOP_START -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) selected.visualRect.right else selected.visualRect.left
            else -> selected.visualRect.centerX
        }
        val currentY = when (spec.pivot) {
            HudPivot.TOP_START -> selected.visualRect.top
            HudPivot.BOTTOM_CENTER -> selected.visualRect.bottom
            HudPivot.CENTER -> selected.visualRect.centerY
        }
        val siblingElements = elements.filter { it.id != id }
        val xGuides = buildList {
            add(HudEditorGuide(HudEditorGuideAxis.VERTICAL, safe.left, HudEditorGuideKind.SAFE_EDGE))
            add(HudEditorGuide(HudEditorGuideAxis.VERTICAL, safe.centerX, HudEditorGuideKind.SAFE_CENTER))
            add(HudEditorGuide(HudEditorGuideAxis.VERTICAL, safe.right, HudEditorGuideKind.SAFE_EDGE))
            siblingElements.forEach { sibling ->
                add(HudEditorGuide(HudEditorGuideAxis.VERTICAL, sibling.visualRect.centerX, HudEditorGuideKind.SIBLING_CENTER, sibling.id))
            }
        }
        val yGuides = buildList {
            add(HudEditorGuide(HudEditorGuideAxis.HORIZONTAL, safe.top, HudEditorGuideKind.SAFE_EDGE))
            add(HudEditorGuide(HudEditorGuideAxis.HORIZONTAL, safe.centerY, HudEditorGuideKind.SAFE_CENTER))
            add(HudEditorGuide(HudEditorGuideAxis.HORIZONTAL, safe.bottom, HudEditorGuideKind.SAFE_EDGE))
            add(HudEditorGuide(HudEditorGuideAxis.HORIZONTAL, safe.bottom - 48f * density, HudEditorGuideKind.CONTROL_BASELINE))
            siblingElements.forEach { sibling ->
                add(HudEditorGuide(HudEditorGuideAxis.HORIZONTAL, sibling.visualRect.centerY, HudEditorGuideKind.SIBLING_CENTER, sibling.id))
                if (sibling.visualRect.top <= safe.top + 96f * density) {
                    add(HudEditorGuide(HudEditorGuideAxis.HORIZONTAL, sibling.visualRect.top, HudEditorGuideKind.CONTROL_BASELINE, sibling.id))
                }
                if (sibling.visualRect.bottom >= safe.bottom - 96f * density) {
                    add(HudEditorGuide(HudEditorGuideAxis.HORIZONTAL, sibling.visualRect.bottom, HudEditorGuideKind.CONTROL_BASELINE, sibling.id))
                }
            }
        }
        val guideDistance = 8f * density
        val vertical = nearestGuide(currentX, xGuides, guideDistance)
        val horizontal = nearestGuide(currentY, yGuides, guideDistance)
        val snapped = raw.copy(
            x = ((vertical?.coordinate ?: currentX) - safe.left) / safe.width.coerceAtLeast(1f),
            y = ((horizontal?.coordinate ?: currentY) - safe.top) / safe.height.coerceAtLeast(1f),
        ).let { clampEditorPlacement(id, it) }
        return HudEditorSnapPreview(
            raw = raw,
            snapped = snapped,
            guides = listOfNotNull(vertical, horizontal),
        )
    }

    /**
     * Resolves a release while preserving the user's intended pivot. The
     * selected element is authoritative; only implicit default controls in
     * the collided row may be repacked around it.
     */
    fun resolveEditorDrop(
        id: HudElementId,
        dragStart: HudPlacement,
        requested: HudPlacement,
    ): HudEditorDropResult {
        val raw = clampEditorPlacement(id, requested)
        val rawProfile = editorProfileWith(id, raw)
        val rawBlockers = editorCollisionIds(id, raw, rawProfile)
        val snap = editorSnapPreview(id, raw)
        if (snap.snapped != raw) {
            val snappedProfile = editorProfileWith(id, snap.snapped)
            if (editorProfileIsLegal(snappedProfile, affectedIds = setOf(id))) {
                return HudEditorDropResult(
                    profile = snappedProfile,
                    selectedPlacement = snap.snapped,
                    movedElements = changedElements(profile, snappedProfile),
                    kind = HudEditorDropKind.SNAPPED,
                    guides = snap.guides,
                    blockers = emptySet(),
                    explanation = null,
                )
            }
        }
        if (rawBlockers.isEmpty() && editorProfileIsLegal(rawProfile, affectedIds = setOf(id))) {
            return HudEditorDropResult(
                profile = rawProfile,
                selectedPlacement = raw,
                movedElements = changedElements(profile, rawProfile),
                kind = HudEditorDropKind.RAW,
                guides = emptyList(),
                blockers = emptySet(),
                explanation = null,
            )
        }

        val pushed = tryRelocateBlockers(id, raw, dragStart, rawBlockers)
        if (pushed != null) {
            return HudEditorDropResult(
                profile = pushed.first,
                selectedPlacement = raw,
                movedElements = changedElements(profile, pushed.first),
                kind = HudEditorDropKind.NUDGED,
                guides = emptyList(),
                blockers = rawBlockers,
                explanation = "Placed ${elementLabel(id)}; moved ${pushed.second.joinToString { elementLabel(it) }}",
            )
        }

        return HudEditorDropResult(
            profile = null,
            selectedPlacement = null,
            movedElements = emptySet(),
            kind = HudEditorDropKind.REJECTED,
            guides = emptyList(),
            blockers = rawBlockers,
            explanation = "No legal position without moving ${elementLabel(id)} or a fixed control",
        )
    }

    fun clampEditorPlacement(id: HudElementId, placement: HudPlacement): HudPlacement {
        val safe = safeRect(width.toFloat(), height.toFloat())
        val testProfile = profile.copy(mode = HudProfileMode.CUSTOM, placements = profile.placements + (id to placement))
        val test = resolve(safe, testProfile, HudElementId.entries.toSet()).firstOrNull { it.id == id } ?: return placement
        val spec = HudElementRegistry.get(id)
        val x = when (spec.pivot) {
            HudPivot.TOP_START -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) test.visualRect.right else test.visualRect.left
            else -> test.visualRect.centerX
        }
        val y = when (spec.pivot) {
            HudPivot.TOP_START -> test.visualRect.top
            HudPivot.BOTTOM_CENTER -> test.visualRect.bottom
            HudPivot.CENTER -> test.visualRect.centerY
        }
        return placement.copy(
            x = ((x - safe.left) / safe.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
            y = ((y - safe.top) / safe.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
        )
    }

    private fun editorProfileWith(id: HudElementId, placement: HudPlacement): HudProfile =
        profile.copy(
            mode = HudProfileMode.CUSTOM,
            placements = profile.placements + (id to placement),
        )

    private fun editorElements(candidateProfile: HudProfile): List<ResolvedHudElement> {
        val safe = safeRect(width.toFloat(), height.toFloat())
        return resolve(safe, candidateProfile.copy(mode = HudProfileMode.CUSTOM), HudElementId.entries.toSet())
    }

    private fun editorProfileIsLegal(
        candidateProfile: HudProfile,
        selectedId: HudElementId? = null,
        affectedIds: Set<HudElementId>? = null,
    ): Boolean {
        val safe = safeRect(width.toFloat(), height.toFloat())
        val elements = editorElements(candidateProfile)
        if (selectedId != null) {
            val selected = elements.firstOrNull { it.id == selectedId } ?: return false
            if (!selected.hitRect.isInside(safe)) return false
            return editorCollisionIds(selectedId, selectedPlacement(selectedId, selected, candidateProfile), candidateProfile).isEmpty()
        }
        return elements.all { it.hitRect.isInside(safe) } &&
            elements.withIndex().none { (index, element) ->
                elements.drop(index + 1).any { other ->
                    (affectedIds == null || element.id in affectedIds || other.id in affectedIds) &&
                        element.hitRect.overlaps(other.hitRect)
                }
            }
    }

    private fun selectedPlacement(
        id: HudElementId,
        element: ResolvedHudElement,
        candidateProfile: HudProfile,
    ): HudPlacement {
        val safe = safeRect(width.toFloat(), height.toFloat())
        val spec = HudElementRegistry.get(id)
        val x = when (spec.pivot) {
            HudPivot.TOP_START -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) element.visualRect.right else element.visualRect.left
            else -> element.visualRect.centerX
        }
        val y = when (spec.pivot) {
            HudPivot.TOP_START -> element.visualRect.top
            HudPivot.BOTTOM_CENTER -> element.visualRect.bottom
            HudPivot.CENTER -> element.visualRect.centerY
        }
        return candidateProfile.placements[id] ?: HudPlacement(
            enabled = true,
            x = ((x - safe.left) / safe.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
            y = ((y - safe.top) / safe.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
            scale = 1f,
        )
    }

    fun editorCollisionIds(
        id: HudElementId,
        placement: HudPlacement,
        candidateProfile: HudProfile = editorProfileWith(id, placement),
    ): Set<HudElementId> {
        val elements = editorElements(candidateProfile)
        val candidate = elements.firstOrNull { it.id == id } ?: return emptySet()
        return elements
            .asSequence()
            .filter { it.id != id && it.hitRect.overlaps(candidate.hitRect) }
            .mapTo(linkedSetOf()) { it.id }
    }

    private fun tryRelocateBlockers(
        id: HudElementId,
        selectedPlacement: HudPlacement,
        dragStart: HudPlacement,
        blockers: Set<HudElementId>,
    ): Pair<HudProfile, Set<HudElementId>>? {
        if (blockers.isEmpty()) return null
        val base = editorProfileWith(id, selectedPlacement)
        val baseElements = editorElements(base).associateBy { it.id }
        val selected = baseElements[id] ?: return null
        val eligible = blockers.filter { isMovableDefaultBlocker(it) }
        if (eligible.size != blockers.size) return null
        val safe = safeRect(width.toFloat(), height.toFloat())
        val dx = (selectedPlacement.x - dragStart.x) * safe.width
        val dy = (selectedPlacement.y - dragStart.y) * safe.height
        val blockerSets = eligible
            .groupBy { defaultLane(it) }
            .filterKeys { it != null }
            .toList()
            .sortedBy { it.first }
        for ((lane, blockerSet) in blockerSets) {
            val semanticLane = lane!!
            val horizontal = defaultLaneIsHorizontal(semanticLane)
            val primaryMovement = if (horizontal) dx else dy
            val firstDirection = pushDirection(
                selected = selected,
                blockers = blockerSet,
                elements = baseElements,
                horizontal = horizontal,
                primaryMovement = primaryMovement,
            )
            for (direction in listOf(firstDirection, -firstDirection).distinct()) {
                val candidate = expandBlockerChain(
                    id = id,
                    selected = selected,
                    baseProfile = base,
                    blockers = blockerSet,
                    lane = semanticLane,
                    horizontal = horizontal,
                    direction = direction,
                    safe = safe,
                ) ?: continue
                val affected = changedElements(profile, candidate.first)
                if (editorProfileIsLegal(candidate.first, affectedIds = affected)) return candidate
            }
        }
        return null
    }

    private fun pushDirection(
        selected: ResolvedHudElement,
        blockers: List<HudElementId>,
        elements: Map<HudElementId, ResolvedHudElement>,
        horizontal: Boolean,
        primaryMovement: Float,
    ): Int {
        if (abs(primaryMovement) > 0.5f) return if (primaryMovement > 0f) -1 else 1
        val selectedCenter = if (horizontal) selected.hitRect.centerX else selected.hitRect.centerY
        val blockerCenter = blockers.mapNotNull { elements[it] }
            .map { if (horizontal) it.hitRect.centerX else it.hitRect.centerY }
            .average()
        return if (selectedCenter >= blockerCenter) -1 else 1
    }

    private fun expandBlockerChain(
        id: HudElementId,
        selected: ResolvedHudElement,
        baseProfile: HudProfile,
        blockers: List<HudElementId>,
        lane: Int,
        horizontal: Boolean,
        direction: Int,
        safe: HudRect,
    ): Pair<HudProfile, Set<HudElementId>>? {
        val chain = blockers.toCollection(linkedSetOf())
        repeat(HudElementId.entries.size) {
            val candidate = translateBlockerLane(
                selected = selected,
                baseProfile = baseProfile,
                blockers = chain.toList(),
                horizontal = horizontal,
                direction = direction,
                safe = safe,
            ) ?: return null
            val candidateElements = editorElements(candidate.first).associateBy { it.id }
            val moved = candidate.second
            val next = candidateElements.values
                .filter { element ->
                    element.id != id &&
                        element.id !in chain &&
                        isMovableDefaultBlocker(element.id) &&
                        defaultLane(element.id) == lane &&
                        moved.any { movedId ->
                            candidateElements[movedId]?.hitRect?.overlaps(element.hitRect) == true
                        }
                }
                .map { it.id }
            if (next.isEmpty()) return candidate
            chain += next
        }
        return null
    }

    private fun translateBlockerLane(
        selected: ResolvedHudElement,
        baseProfile: HudProfile,
        blockers: List<HudElementId>,
        horizontal: Boolean,
        direction: Int,
        safe: HudRect,
    ): Pair<HudProfile, Set<HudElementId>>? {
        val baseElements = editorElements(baseProfile).associateBy { it.id }
        val lane = blockers
            .mapNotNull { baseElements[it] }
            .sortedWith(
                if (horizontal) compareBy<ResolvedHudElement> { it.hitRect.left }
                else compareBy<ResolvedHudElement> { it.hitRect.top },
            )
        if (lane.isEmpty()) return null

        val spacing = HudDefaultLayout.SPACING * density
        val translation = if (horizontal) {
            if (direction < 0) {
                selected.hitRect.left - spacing - lane.maxOf { it.hitRect.right }
            } else {
                selected.hitRect.right + spacing - lane.minOf { it.hitRect.left }
            }
        } else if (direction < 0) {
            selected.hitRect.top - spacing - lane.maxOf { it.hitRect.bottom }
        } else {
            selected.hitRect.bottom + spacing - lane.minOf { it.hitRect.top }
        }
        if (abs(translation) < 0.5f) return null

        var nextProfile = baseProfile
        val moved = linkedSetOf<HudElementId>()
        lane.forEach { element ->
            val currentPlacement = nextProfile.placements[element.id]
                ?: placementFromResolved(element.id, element, nextProfile)
            val nextPlacement = if (horizontal) {
                currentPlacement.copy(x = currentPlacement.x + translation / safe.width.coerceAtLeast(1f))
            } else {
                currentPlacement.copy(y = currentPlacement.y + translation / safe.height.coerceAtLeast(1f))
            }
            if ((horizontal && nextPlacement.x !in 0f..1f) ||
                (!horizontal && nextPlacement.y !in 0f..1f)
            ) return null
            nextProfile = nextProfile.copy(placements = nextProfile.placements + (element.id to nextPlacement))
            moved += element.id
        }
        return nextProfile to moved
    }

    private fun placementFromResolved(
        id: HudElementId,
        element: ResolvedHudElement,
        candidateProfile: HudProfile,
    ): HudPlacement {
        val safe = safeRect(width.toFloat(), height.toFloat())
        val spec = HudElementRegistry.get(id)
        val x = when (spec.pivot) {
            HudPivot.TOP_START -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) element.visualRect.right else element.visualRect.left
            else -> element.visualRect.centerX
        }
        val y = when (spec.pivot) {
            HudPivot.TOP_START -> element.visualRect.top
            HudPivot.BOTTOM_CENTER -> element.visualRect.bottom
            HudPivot.CENTER -> element.visualRect.centerY
        }
        return HudPlacement(
            enabled = true,
            x = ((x - safe.left) / safe.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
            y = ((y - safe.top) / safe.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
            scale = candidateProfile.placements[id]?.scale ?: 1f,
        )
    }

    private fun elementLabel(id: HudElementId): String = id.name
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar(Char::uppercase)

    private fun isMovableDefaultBlocker(id: HudElementId): Boolean =
        id != HudElementId.STREAM_INFO &&
            id != HudElementId.TIMELINE &&
            id != HudElementId.SEEK_BACK &&
            id != HudElementId.PLAY_PAUSE &&
            id != HudElementId.SEEK_FORWARD &&
            id !in profile.placements &&
            HudElementRegistry.get(id).pivot == HudPivot.CENTER

    private fun defaultLane(id: HudElementId): Int? {
        val safeWidth = safeRect(width.toFloat(), height.toFloat()).width / density.coerceAtLeast(0.001f)
        return when {
            id == HudElementId.STREAM_INFO || id in HudDefaultLayout.topEndElements(
                orientation,
                safeWidth,
                profile.defaultPolicyVersion,
            ) -> 0
            id == HudElementId.SEEK_BACK ||
                id == HudElementId.PLAY_PAUSE ||
                id == HudElementId.SEEK_FORWARD -> 1
            id == HudElementId.VOLUME ||
                id == HudElementId.CLIP ||
                id == HudElementId.MORE ||
                id == HudElementId.CAPTIONS ||
                id == HudElementId.CHAT ||
                id == HudElementId.FULLSCREEN -> 2
            id == HudElementId.TIMELINE -> 3
            else -> null
        }
    }

    private fun defaultLaneIsHorizontal(lane: Int): Boolean = when (lane) {
        0, 1, 2 -> true
        else -> false
    }

    private fun changedElements(before: HudProfile, after: HudProfile): Set<HudElementId> =
        HudElementId.entries.filterTo(linkedSetOf()) { id ->
            before.placements[id] != after.placements[id]
        }

    private fun nearestGuide(
        coordinate: Float,
        guides: List<HudEditorGuide>,
        threshold: Float,
    ): HudEditorGuide? {
        fun priority(guide: HudEditorGuide): Int = when (guide.kind) {
            HudEditorGuideKind.SAFE_CENTER -> 0
            HudEditorGuideKind.SAFE_EDGE -> 1
            HudEditorGuideKind.SIBLING_CENTER -> 2
            HudEditorGuideKind.CONTROL_BASELINE -> 3
        }
        return guides.minWithOrNull(
            compareBy<HudEditorGuide>(
                { abs(it.coordinate - coordinate) },
                { priority(it) },
                { it.source?.ordinal ?: -1 },
                { it.coordinate },
            ),
        )?.takeIf { abs(it.coordinate - coordinate) <= threshold }
    }

    fun editorDropHasCollision(id: HudElementId, placement: HudPlacement): Boolean {
        return editorCollisionIds(id, placement).isNotEmpty()
    }

    fun editorProfileHasCollision(candidateProfile: HudProfile): Boolean {
        val elements = resolve(
            safeRect(width.toFloat(), height.toFloat()),
            candidateProfile.copy(mode = HudProfileMode.CUSTOM),
            HudElementId.entries.toSet(),
        )
        return elements.withIndex().any { (index, element) ->
            elements.drop(index + 1).any { other ->
                element.hitRect.overlaps(other.hitRect)
            }
        }
    }

    fun collisionFreeEditorPlacement(id: HudElementId, placement: HudPlacement): HudPlacement? {
        val semantic = HudDefaultLayout.semanticFallback(
            id,
            orientation,
            profile.defaultPolicyVersion,
        ).copy(
            enabled = true,
            scale = placement.scale,
        )
        val candidates = buildList {
            add(placement)
            add(semantic)
            listOf(.08f, .24f, .40f, .60f, .76f, .92f).forEach { x ->
                listOf(.88f, .72f, .50f, .20f).forEach { y ->
                    add(placement.copy(enabled = true, x = x, y = y))
                }
            }
        }
        return candidates
            .asSequence()
            .map { clampEditorPlacement(id, it) }
            .firstOrNull { !editorDropHasCollision(id, it) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val safe = safeRect(measuredWidth.toFloat(), measuredHeight.toFloat())
        val compact = safe.height < 260f * density
        frames.values.forEach(HudElementFrame::resetPresentationMetrics)
        if (compactMetricsApplied != compact) {
            applyCompactMetrics(compact)
            compactMetricsApplied = compact
        }
        applyMetadataWidth(safe)
        frames.values.forEach(HudElementFrame::captureCanonicalPresentationMetrics)
        val frameWidthSpec = MeasureSpec.makeMeasureSpec(safe.width.roundToInt().coerceAtLeast(1), MeasureSpec.AT_MOST)
        val frameHeightSpec = MeasureSpec.makeMeasureSpec(safe.height.roundToInt().coerceAtLeast(1), MeasureSpec.AT_MOST)
        frames.values.forEach { it.measureNatural(frameWidthSpec, frameHeightSpec) }
        val measuredSizes = frames.mapValues { (id, frame) ->
            if (id == HudElementId.TIMELINE) HudSize(safe.width, 48f * density) else frame.naturalVisualSize()
        }
        resolved = resolve(safe, profile, availability, measuredSizes).associateBy { it.id }
        frames.forEach { (id, frame) ->
            val element = resolved[id]
            frame.setGeometry(element)
            frame.setActive(element != null)
            if (element != null) {
                frame.applyPresentationScale(element.effectiveScale)
                frame.measure(
                    MeasureSpec.makeMeasureSpec(element.hitRect.width.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(element.hitRect.height.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                )
            }
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !is HudElementFrame) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
                )
            }
        }
        setMeasuredDimension(
            resolveSize(measuredWidth, widthMeasureSpec),
            resolveSize(measuredHeight, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child is HudElementFrame) {
                val rect = resolved[(child.tag as? String)?.let { runCatching { HudElementId.valueOf(it) }.getOrNull() }]
                    ?.hitRect
                if (rect != null) {
                    child.layout(rect.left.roundToInt(), rect.top.roundToInt(), rect.right.roundToInt(), rect.bottom.roundToInt())
                } else {
                    child.layout(0, 0, 0, 0)
                }
            } else {
                child.layout(0, 0, width, height)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!editing) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editorSelected = resolved.values.lastOrNull { it.hitRect.contains(event.x, event.y) }?.id
                editorSelected?.let { id ->
                    parent?.requestDisallowInterceptTouchEvent(true)
                    editorStartX = event.x
                    editorStartY = event.y
                    editorStartProfile = profile
                    editorStartPlacement = profile.placements[id]
                        ?: defaultPlacement(id)
                    editorDragging = false
                    editorOnSelected?.invoke(id)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = editorSelected ?: return false
                val start = editorStartPlacement ?: return true
                val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                if (!editorDragging && hypot(event.x - editorStartX, event.y - editorStartY) > slop) {
                    editorDragging = true
                    editorOnDragStarted?.invoke(id)
                }
                if (editorDragging) {
                    val next = clampEditorPlacement(
                        id,
                        start.copy(
                            x = start.x + (event.x - editorStartX) / safeRect(width.toFloat(), height.toFloat()).width.coerceAtLeast(1f),
                            y = start.y + (event.y - editorStartY) / safeRect(width.toFloat(), height.toFloat()).height.coerceAtLeast(1f),
                        ),
                    )
                    updateEditorPlacement(id, next)
                    editorOnMoved?.invoke(id, next)
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val id = editorSelected
                val canceled = event.actionMasked == MotionEvent.ACTION_CANCEL
                if (id != null && editorDragging) {
                    if (canceled) editorStartProfile?.let { profile = it; requestLayout() }
                    editorOnDropped?.invoke(id, canceled, editorStartPlacement)
                }
                editorSelected = null
                editorStartPlacement = null
                editorStartProfile = null
                editorDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return id != null
            }
        }
        return false
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = if (!editing) {
        false
    } else {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> resolved.values.any { it.hitRect.contains(event.x, event.y) }
            else -> editorSelected != null
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams {
        val androidNamespace = "http://schemas.android.com/apk/res/android"
        val hasWidth = attrs.getAttributeValue(androidNamespace, "layout_width") != null
        val hasHeight = attrs.getAttributeValue(androidNamespace, "layout_height") != null
        return if (hasWidth && hasHeight) {
            MarginLayoutParams(context, attrs)
        } else {
            MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
    }

    override fun generateLayoutParams(params: ViewGroup.LayoutParams): LayoutParams =
        MarginLayoutParams(params)

    override fun checkLayoutParams(params: ViewGroup.LayoutParams): Boolean =
        params is MarginLayoutParams

    private fun resolve(
        safe: HudRect,
        value: HudProfile,
        visible: Set<HudElementId>,
        sizes: Map<HudElementId, HudSize> = frames.mapValues { it.value.naturalVisualSize() },
    ): List<ResolvedHudElement> = HudLayoutEngine(
        density = density,
        rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL,
        television = context.isTelevision(),
        televisionEdgePadding = resources.getDimension(R.dimen.tv_safe_horizontal),
    ).resolve(safe, orientation, value, sizes, availability = visible)

    private fun safeRect(rootWidth: Float, rootHeight: Float): HudRect {
        val viewport = videoViewport(rootWidth, rootHeight)
        val insets = safeInsetsOverride ?: safeInsets
        return HudRect(
        (viewport.left + insets.left).coerceAtMost(viewport.centerX),
        (viewport.top + insets.top).coerceAtMost(viewport.centerY),
        (viewport.right - insets.right).coerceAtLeast(viewport.centerX),
        (viewport.bottom - insets.bottom).coerceAtLeast(viewport.centerY),
        )
    }

    /**
     * The player overlay is a sibling of the aspect-ratio child. Use that
     * child's local bounds as the HUD coordinate plane so letterbox space never
     * becomes a place where controls can be positioned. The position is derived
     * from the same measured size and gravity that PlayerLayout will use.
     */
    private fun videoViewport(rootWidth: Float, rootHeight: Float): HudRect {
        val parentGroup = parent as? ViewGroup ?: return HudRect(0f, 0f, rootWidth, rootHeight)
        val video = parentGroup.findViewById<View>(R.id.aspectRatioFrameLayout)
            ?: return HudRect(0f, 0f, rootWidth, rootHeight)
        val videoWidth = video.measuredWidth.takeIf { it > 0 } ?: video.width
        val videoHeight = video.measuredHeight.takeIf { it > 0 } ?: video.height
        if (videoWidth <= 0 || videoHeight <= 0) return HudRect(0f, 0f, rootWidth, rootHeight)

        val params = video.layoutParams as? android.widget.FrameLayout.LayoutParams
        val gravity = params?.gravity ?: Gravity.TOP or Gravity.START
        val absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection)
        val horizontal = absoluteGravity and Gravity.HORIZONTAL_GRAVITY_MASK
        val vertical = gravity and Gravity.VERTICAL_GRAVITY_MASK
        val left = when (horizontal) {
            Gravity.CENTER_HORIZONTAL -> (rootWidth - videoWidth) / 2f
            Gravity.RIGHT -> rootWidth - videoWidth - (params?.rightMargin ?: 0)
            else -> (params?.leftMargin ?: 0).toFloat()
        }
        val top = when (vertical) {
            Gravity.CENTER_VERTICAL -> (rootHeight - videoHeight) / 2f
            Gravity.BOTTOM -> rootHeight - videoHeight - (params?.bottomMargin ?: 0)
            else -> (params?.topMargin ?: 0).toFloat()
        }
        val boundedLeft = left.coerceIn(0f, (rootWidth - videoWidth).coerceAtLeast(0f))
        val boundedTop = top.coerceIn(0f, (rootHeight - videoHeight).coerceAtLeast(0f))
        return HudRect(
            boundedLeft,
            boundedTop,
            (boundedLeft + videoWidth).coerceAtMost(rootWidth),
            (boundedTop + videoHeight).coerceAtMost(rootHeight),
        )
    }

    private fun runtimeAvailability(): Set<HudElementId> = HudElementId.entries.filterTo(mutableSetOf()) { id ->
        val frame = frames[id] ?: return@filterTo false
        when (id) {
            HudElementId.STREAM_INFO -> listOf(R.id.channelAvatar, R.id.channel, R.id.title, R.id.category, R.id.viewersLayout).any(::isShown)
            HudElementId.TIMELINE -> listOf(R.id.progressBar, R.id.position, R.id.duration, R.id.liveTimeGroup).any(::isShown)
            HudElementId.CAPTIONS -> listOf(R.id.liveCaptions, R.id.subtitles).any(::isShown)
            else -> hasBoundAction(frame)
        }
    }

    private fun isShown(id: Int): Boolean = findViewById<View>(id)?.visibility == VISIBLE

    private fun hasBoundAction(view: View): Boolean {
        if (view.visibility != VISIBLE) return false
        if (view.hasOnClickListeners() || view.hasOnLongClickListeners()) return true
        if (view !is ViewGroup) return false
        return (0 until view.childCount).any { hasBoundAction(view.getChildAt(it)) }
    }

    private fun defaultPlacement(id: HudElementId): HudPlacement {
        val defaultProfile = PlayerHudDefaults.config().profile(orientation).copy(
            globalScale = profile.globalScale,
            defaultPolicyVersion = profile.defaultPolicyVersion,
        )
        return resolve(safeRect(width.toFloat(), height.toFloat()), defaultProfile, HudElementId.entries.toSet())
            .firstOrNull { it.id == id }
            ?.let { element ->
                val safe = safeRect(width.toFloat(), height.toFloat())
                val spec = HudElementRegistry.get(id)
                val x = when (spec.pivot) {
                    HudPivot.TOP_START -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) element.visualRect.right else element.visualRect.left
                    else -> element.visualRect.centerX
                }
                val y = when (spec.pivot) {
                    HudPivot.TOP_START -> element.visualRect.top
                    HudPivot.BOTTOM_CENTER -> element.visualRect.bottom
                    HudPivot.CENTER -> element.visualRect.centerY
                }
                HudPlacement(true, (x - safe.left) / safe.width.coerceAtLeast(1f), (y - safe.top) / safe.height.coerceAtLeast(1f), 1f)
            }
            ?: HudDefaultLayout.semanticFallback(id, orientation, profile.defaultPolicyVersion).copy(
                enabled = false,
                scale = profile.placements[id]?.scale ?: 1f,
            )
    }

    private fun applyMetadataWidth(safe: HudRect) {
        val compositionWidth = HudLayoutEngine(
            density = density,
            rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL,
            television = context.isTelevision(),
            televisionEdgePadding = resources.getDimension(R.dimen.tv_safe_horizontal),
        ).metadataWidthBudget(safe, orientation, profile, availability)
            .coerceAtMost(safe.width)
            .coerceAtLeast(1f)
        val avatar = findViewById<View>(R.id.channelAvatar)
        val avatarParams = avatar?.layoutParams as? ViewGroup.MarginLayoutParams
        val avatarWidth = if (avatar?.visibility == View.GONE) {
            0f
        } else {
            (avatarParams?.width ?: (40f * density).roundToInt()).toFloat() +
                (avatarParams?.rightMargin ?: (10f * density).roundToInt())
        }
        val textWidth = (compositionWidth - avatarWidth).coerceAtLeast(40f * density).roundToInt()

        // Give the metadata composition one deterministic width. The details
        // row is packed: Playing and the viewer target keep their measured
        // widths, while category is the only field allowed to shrink.
        findViewById<LinearLayout>(R.id.topLeftLayout)?.updateLayoutParams<ViewGroup.LayoutParams> {
            width = compositionWidth.roundToInt().coerceAtLeast(1)
        }
        findViewById<LinearLayout>(R.id.infoLayout)?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = 0
            weight = 1f
        }
        findViewById<LinearLayout>(R.id.titleAndViewersLayout)?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            weight = 0f
        }
        findViewById<LinearLayout>(R.id.streamDetailsLayout)?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            weight = 0f
        }

        val playing = findViewById<TextView>(R.id.playingLabel)
        val category = findViewById<TextView>(R.id.category)
        val viewers = findViewById<View>(R.id.viewersLayout)
        playing?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            weight = 0f
            marginEnd = (2f * density).roundToInt()
        }
        category?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            weight = 0f
            marginStart = 0
            marginEnd = (4f * density).roundToInt()
        }
        val fixedDetailsWidth = measuredWrapContentWidth(playing, safe.height) +
            measuredWrapContentWidth(viewers, safe.height)
        val categoryMargins = (category?.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.leftMargin + it.rightMargin
        } ?: 0
        val categoryMaxWidth = (textWidth - fixedDetailsWidth - categoryMargins).coerceAtLeast(1)

        listOf(R.id.channel, R.id.title).forEach { id ->
            findViewById<TextView>(id)?.let { textView ->
                if (textView.maxWidth != textWidth) textView.maxWidth = textWidth
            }
        }
        category?.let {
            it.maxWidth = categoryMaxWidth
            it.maxLines = 1
            it.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        findViewById<TextView>(R.id.viewersText)?.apply {
            maxLines = 1
            ellipsize = null
        }
    }

    private fun measuredWrapContentWidth(view: View?, availableHeight: Float): Int {
        if (view == null || view.visibility != VISIBLE) return 0
        view.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(
                availableHeight.roundToInt().coerceAtLeast(1),
                View.MeasureSpec.AT_MOST,
            ),
        )
        val margins = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.leftMargin + it.rightMargin
        } ?: 0
        return view.measuredWidth + margins
    }

    private fun applyCompactMetrics(compact: Boolean) {
        val buttonSize = (if (compact) 36 else 40) * density
        val seekSize = (if (compact) 44 else 48) * density
        val playSize = (if (compact) 56 else 60) * density
        listOf(
            R.id.follow, R.id.aspectRatio, R.id.volume, R.id.clip, R.id.liveCaptions, R.id.subtitles,
            R.id.toggleChat, R.id.fullscreen, R.id.menu, R.id.interactionLock, R.id.minimize,
            R.id.download, R.id.speed, R.id.vodGames, R.id.restart, R.id.seekLive,
            R.id.audioOnly, R.id.audioCompressor, R.id.toggleChatInput, R.id.sleepTimer,
        ).forEach { id -> resizeView(id, buttonSize.roundToInt()) }
        resizeView(R.id.rewind, seekSize.roundToInt())
        resizeView(R.id.fastForward, seekSize.roundToInt())
        resizeView(R.id.playPause, playSize.roundToInt())
        findViewById<TextView>(R.id.channel)?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 13f else 15f)
        findViewById<TextView>(R.id.title)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 15f)
            maxLines = 2
        }
        findViewById<TextView>(R.id.playingLabel)?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)
        findViewById<TextView>(R.id.category)?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)
        findViewById<TextView>(R.id.viewersText)?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 12f)
        val avatarSize = (if (compact) 32 else 40) * density
        resizeView(R.id.channelAvatar, avatarSize.roundToInt())
    }

    private fun resizeView(id: Int, size: Int) {
        findViewById<View>(id)?.updateLayoutParams<ViewGroup.LayoutParams> {
            width = size
            height = size
        }
    }

    private fun showPreviewContent() {
        findViewById<ImageView>(R.id.channelAvatar)?.apply {
            setImageDrawable(null)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF5B516B.toInt())
            }
            visibility = VISIBLE
        }
        findViewById<TextView>(R.id.channel)?.apply { visibility = VISIBLE; text = "sample_channel" }
        findViewById<TextView>(R.id.title)?.apply { visibility = VISIBLE; text = "A sample stream title" }
        findViewById<TextView>(R.id.playingLabel)?.visibility = VISIBLE
        findViewById<TextView>(R.id.category)?.apply { visibility = VISIBLE; text = "Just Chatting" }
        findViewById<View>(R.id.viewersLayout)?.visibility = VISIBLE
        findViewById<TextView>(R.id.viewersText)?.apply { visibility = VISIBLE; text = "for 12,345 viewers" }
        // Keep the XML hierarchy's ordinary controls as the preview's
        // canonical presentation. Only data-bearing fields need synthetic
        // content; recursively forcing every descendant visible makes preview
        // availability disagree with runtime (notably captions).
        findViewById<View>(R.id.position)?.visibility = GONE
        findViewById<View>(R.id.duration)?.visibility = GONE
        findViewById<View>(R.id.liveCaptions)?.visibility = VISIBLE
        findViewById<View>(R.id.subtitles)?.visibility = GONE
        findViewById<TextView>(R.id.liveTimeGroup)?.apply {
            visibility = VISIBLE
            text = PREVIEW_LIVE_TIME
            contentDescription = context.getString(R.string.player_position, PREVIEW_LIVE_TIME)
        }
        findViewById<HudTimelineContent>(R.id.timelineContent)?.apply {
            setLiveRewindTimePosition(store.loadTimelineTimePosition())
            setLiveRewindEnabled(true)
        }
    }

    private fun restoreRuntimeContent() {
        findViewById<View>(R.id.channelAvatar)?.background = null
        findViewById<View>(R.id.liveTimeGroup)?.visibility = GONE
        findViewById<HudTimelineContent>(R.id.timelineContent)?.setLiveRewindEnabled(false)
        refreshAvailability()
    }

    private fun refreshTimelineSettings() {
        findViewById<HudTimelineContent>(R.id.timelineContent)
            ?.setLiveRewindTimePosition(store.loadTimelineTimePosition())
    }

    private fun currentOrientation(): HudOrientation = if (
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    ) HudOrientation.PORTRAIT else HudOrientation.LANDSCAPE

    private fun hypot(x: Float, y: Float): Float = kotlin.math.sqrt(x * x + y * y)

}
