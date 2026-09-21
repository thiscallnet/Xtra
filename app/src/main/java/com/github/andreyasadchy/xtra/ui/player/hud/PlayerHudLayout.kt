package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
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
        const val EDITOR_BOTTOM_CHROME_CLEARANCE_DP = 14f
        const val EDITOR_AUTONUDGE_GAP_DP = 2f
        const val EDITOR_SNAP_DISTANCE_DP = 12f
        const val EDITOR_MAX_AUTONUDGE_DP = 48f
        const val PREVIEW_LIVE_TIME = "32:23 · LIVE"
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
    private var edgeMarker: EdgeMarkerView? = null
    private var edgeMarkerBar: HudTimeBar? = null
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
    /** The player gesture layer may dispatch directly to this root. */
    var interactionLocked = false
        set(value) {
            field = value
            updateInteractionAccessibility()
            if (!value) interactionUnlockGesture = false
        }
    var interactionUnlockView: View? = null
    private val interactionUnlockHitRect = Rect()
    private var interactionUnlockGesture = false

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        // The scrim is non-accessible, but active HUD frames contain real
        // controls. This container is structural; expose the actionable child
        // views instead of creating a second, empty accessibility node.
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
        findViewById<LinearLayout>(R.id.topLeftLayout)?.apply {
            background = HudMetadataBackgroundDrawable(density)
            val horizontalPadding = (8f * density).roundToInt()
            val verticalPadding = (5f * density).roundToInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }
        frames.clear()
        for (index in 0 until childCount) {
            (getChildAt(index) as? HudElementFrame)?.let { frame ->
                val id = (frame.tag as? String)?.let { runCatching { HudElementId.valueOf(it) }.getOrNull() }
                if (id != null) frames[id] = frame
            }
        }
        findViewById<HudTimelineContent>(R.id.timelineContent)?.let { timeline ->
            edgeMarkerBar = timeline.findViewById(R.id.progressBar)
            edgeMarker = EdgeMarkerView(context).apply {
                visibility = GONE
                isClickable = false
                isFocusable = false
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(edgeMarker, indexOfChild(timeline) + 1)
            edgeMarkerBar?.setEdgeMarkerListener { updateEdgeMarker() }
        }
        findViewById<TextView>(R.id.liveTimeGroup)?.apply {
            gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        availability = runtimeAvailability()
        updateInteractionAccessibility()
    }

    fun setHudOrientation(value: HudOrientation) {
        orientation = value
        profile = store.load().profile(value)
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
        availability = if (preview) HudElementRegistry.activeIds else runtimeAvailability()
        if (preview) showPreviewContent() else restoreRuntimeContent()
        requestLayout()
    }

    fun refreshAvailability() {
        if (!refreshAvailabilityIfChanged()) requestLayout()
    }

    fun refreshAvailabilityIfChanged(): Boolean {
        val next = if (editing) HudElementRegistry.activeIds else runtimeAvailability()
        if (next == availability) return false
        availability = next
        requestLayout()
        return true
    }

    fun setLiveRewindEnabled(enabled: Boolean) {
        findViewById<HudTimelineContent>(R.id.timelineContent)?.apply {
            setLiveRewindEnabled(enabled)
        }
        if (!editing) availability = runtimeAvailability()
        requestLayout()
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
        availability = if (enabled) HudElementRegistry.activeIds else runtimeAvailability()
        requestLayout()
    }

    fun updateEditorPlacement(id: HudElementId, placement: HudPlacement) {
        if (!editing || !HudElementRegistry.get(id).isMovable) return
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

    /**
     * Returns controls omitted from the current direct layout to the More
     * sheet, while respecting an explicit hide in a CUSTOM profile.
     */
    fun canShowInOverflow(id: HudElementId): Boolean {
        if (interactionLocked && id != HudElementId.INTERACTION_LOCK) return false
        if (id !in availability || profile.placements[id]?.enabled == false || isElementActive(id)) {
            return false
        }
        // The fixed timeline status owns the live affordance whenever it is
        // present. Do not put a redundant "seek live" row in More at the edge
        // or while rewound.
        if (id == HudElementId.GO_LIVE &&
            findViewById<View>(R.id.liveTimeGroup)?.isShown == true
        ) {
            return false
        }
        return true
    }

    fun performAction(id: HudElementId): Boolean {
        if (interactionLocked && id != HudElementId.INTERACTION_LOCK) return false
        return frames[id]?.performAction() == true
    }

    fun actionContentDescription(id: HudElementId): CharSequence? {
        if (interactionLocked && id != HudElementId.INTERACTION_LOCK) return null
        return frames[id]?.actionContentDescription()
    }

    fun isActionEnabled(id: HudElementId): Boolean {
        if (interactionLocked && id != HudElementId.INTERACTION_LOCK) return false
        return frames[id]?.isActionEnabled() == true
    }

    private fun updateInteractionAccessibility() {
        frames.forEach { (id, frame) ->
            frame.setInteractionBlocked(interactionLocked && id != HudElementId.INTERACTION_LOCK)
        }
    }

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
        if (!HudElementRegistry.get(id).isMovable) {
            return HudEditorSnapPreview(raw, raw, emptyList())
        }
        val elements = editorElements(editorProfileWith(id, raw))
        val selected = elements.firstOrNull { it.id == id } ?: return HudEditorSnapPreview(raw, raw, emptyList())
        val (currentX, currentY) = editorPlacementAnchor(id, selected)
        val siblingElements = elements.filter { it.id != id }
        val xGuides = buildList {
            add(
                HudEditorGuide(
                    HudEditorGuideAxis.VERTICAL,
                    safe.left,
                    HudEditorGuideKind.SAFE_EDGE,
                    snapCoordinate = editorAnchorForVerticalEdge(id, selected, safe.left, leftEdge = true),
                ),
            )
            add(
                HudEditorGuide(
                    HudEditorGuideAxis.VERTICAL,
                    safe.centerX,
                    HudEditorGuideKind.SAFE_CENTER,
                    snapCoordinate = editorAnchorForVisualCenter(id, selected, safe.centerX, selected.visualRect.centerY).first,
                ),
            )
            add(
                HudEditorGuide(
                    HudEditorGuideAxis.VERTICAL,
                    safe.right,
                    HudEditorGuideKind.SAFE_EDGE,
                    snapCoordinate = editorAnchorForVerticalEdge(id, selected, safe.right, leftEdge = false),
                ),
            )
            listOf(1f / 3f, 2f / 3f).forEach { fraction ->
                val coordinate = safe.left + safe.width * fraction
                add(
                    HudEditorGuide(
                        HudEditorGuideAxis.VERTICAL,
                        coordinate,
                        HudEditorGuideKind.SAFE_DIVISION,
                        snapCoordinate = editorAnchorForVisualCenter(id, selected, coordinate, selected.visualRect.centerY).first,
                    ),
                )
            }
            siblingElements.forEach { sibling ->
                add(
                    HudEditorGuide(
                        HudEditorGuideAxis.VERTICAL,
                        sibling.visualRect.centerX,
                        HudEditorGuideKind.SIBLING_CENTER,
                        sibling.id,
                        editorAnchorForVisualCenter(id, selected, sibling.visualRect.centerX, selected.visualRect.centerY).first,
                    ),
                )
                add(
                    HudEditorGuide(
                        HudEditorGuideAxis.VERTICAL,
                        sibling.visualRect.left,
                        HudEditorGuideKind.SIBLING_EDGE,
                        sibling.id,
                        editorAnchorForVerticalEdge(id, selected, sibling.visualRect.left, leftEdge = true),
                    ),
                )
                add(
                    HudEditorGuide(
                        HudEditorGuideAxis.VERTICAL,
                        sibling.visualRect.right,
                        HudEditorGuideKind.SIBLING_EDGE,
                        sibling.id,
                        editorAnchorForVerticalEdge(id, selected, sibling.visualRect.right, leftEdge = false),
                    ),
                )
            }
        }
        val yGuides = buildList {
            add(
                HudEditorGuide(
                    HudEditorGuideAxis.HORIZONTAL,
                    safe.top,
                    HudEditorGuideKind.SAFE_EDGE,
                    snapCoordinate = editorAnchorForHorizontalEdge(id, selected, safe.top, topEdge = true),
                ),
            )
            add(
                HudEditorGuide(
                    HudEditorGuideAxis.HORIZONTAL,
                    safe.centerY,
                    HudEditorGuideKind.SAFE_CENTER,
                    snapCoordinate = editorAnchorForVisualCenter(id, selected, selected.visualRect.centerX, safe.centerY).second,
                ),
            )
            val bottomGuide = safe.bottom - EDITOR_BOTTOM_CHROME_CLEARANCE_DP * density
            add(
                HudEditorGuide(
                    HudEditorGuideAxis.HORIZONTAL,
                    bottomGuide,
                    HudEditorGuideKind.CONTROL_BASELINE,
                    snapCoordinate = editorAnchorForHorizontalEdge(id, selected, bottomGuide, topEdge = false),
                ),
            )
            listOf(1f / 3f, 2f / 3f).forEach { fraction ->
                val coordinate = safe.top + safe.height * fraction
                add(
                    HudEditorGuide(
                        HudEditorGuideAxis.HORIZONTAL,
                        coordinate,
                        HudEditorGuideKind.SAFE_DIVISION,
                        snapCoordinate = editorAnchorForVisualCenter(id, selected, selected.visualRect.centerX, coordinate).second,
                    ),
                )
            }
            siblingElements.forEach { sibling ->
                add(
                    HudEditorGuide(
                        HudEditorGuideAxis.HORIZONTAL,
                        sibling.visualRect.centerY,
                        HudEditorGuideKind.SIBLING_CENTER,
                        sibling.id,
                        editorAnchorForVisualCenter(id, selected, selected.visualRect.centerX, sibling.visualRect.centerY).second,
                    ),
                )
                add(
                    HudEditorGuide(
                        HudEditorGuideAxis.HORIZONTAL,
                        sibling.visualRect.top,
                        HudEditorGuideKind.SIBLING_EDGE,
                        sibling.id,
                        editorAnchorForHorizontalEdge(id, selected, sibling.visualRect.top, topEdge = true),
                    ),
                )
                add(
                    HudEditorGuide(
                        HudEditorGuideAxis.HORIZONTAL,
                        sibling.visualRect.bottom,
                        HudEditorGuideKind.SIBLING_EDGE,
                        sibling.id,
                        editorAnchorForHorizontalEdge(id, selected, sibling.visualRect.bottom, topEdge = false),
                    ),
                )
            }
        }
        val guideDistance = EDITOR_SNAP_DISTANCE_DP * density
        val vertical = nearestGuide(currentX, xGuides, guideDistance)
        val horizontal = nearestGuide(currentY, yGuides, guideDistance)
        val snapped = raw.copy(
            x = ((vertical?.snapCoordinate ?: currentX) - safe.left) / safe.width.coerceAtLeast(1f),
            y = ((horizontal?.snapCoordinate ?: currentY) - safe.top) / safe.height.coerceAtLeast(1f),
        ).let { clampEditorPlacement(id, it) }
        return HudEditorSnapPreview(
            raw = raw,
            snapped = snapped,
            guides = listOfNotNull(vertical, horizontal),
        )
    }

    /** The profile coordinate is an element-specific pivot, not always its center. */
    private fun editorPlacementAnchor(id: HudElementId, element: ResolvedHudElement): Pair<Float, Float> {
        val spec = HudElementRegistry.get(id)
        return when (spec.pivot) {
            HudPivot.TOP_START -> {
                val x = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                    element.visualRect.right
                } else {
                    element.visualRect.left
                }
                x to element.visualRect.top
            }
            HudPivot.BOTTOM_CENTER -> element.visualRect.centerX to element.visualRect.bottom
            HudPivot.CENTER -> element.visualRect.centerX to element.visualRect.centerY
        }
    }

    private fun editorAnchorForVisualCenter(
        id: HudElementId,
        element: ResolvedHudElement,
        centerX: Float,
        centerY: Float,
    ): Pair<Float, Float> {
        val spec = HudElementRegistry.get(id)
        val x = when (spec.pivot) {
            HudPivot.TOP_START -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                centerX + element.visualRect.width / 2f
            } else {
                centerX - element.visualRect.width / 2f
            }
            else -> centerX
        }
        val y = when (spec.pivot) {
            HudPivot.TOP_START -> centerY - element.visualRect.height / 2f
            HudPivot.BOTTOM_CENTER -> centerY + element.visualRect.height / 2f
            HudPivot.CENTER -> centerY
        }
        return x to y
    }

    private fun editorAnchorForVerticalEdge(
        id: HudElementId,
        element: ResolvedHudElement,
        coordinate: Float,
        leftEdge: Boolean,
    ): Float {
        val spec = HudElementRegistry.get(id)
        return when (spec.pivot) {
            HudPivot.TOP_START -> if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                if (leftEdge) coordinate + element.visualRect.width else coordinate
            } else {
                if (leftEdge) coordinate else coordinate - element.visualRect.width
            }
            else -> if (leftEdge) {
                coordinate + element.visualRect.width / 2f
            } else {
                coordinate - element.visualRect.width / 2f
            }
        }
    }

    private fun editorAnchorForHorizontalEdge(
        id: HudElementId,
        element: ResolvedHudElement,
        coordinate: Float,
        topEdge: Boolean,
    ): Float = when (HudElementRegistry.get(id).pivot) {
        HudPivot.TOP_START -> if (topEdge) coordinate else coordinate - element.visualRect.height
        HudPivot.BOTTOM_CENTER -> if (topEdge) coordinate + element.visualRect.height else coordinate
        HudPivot.CENTER -> if (topEdge) {
            coordinate + element.visualRect.height / 2f
        } else {
            coordinate - element.visualRect.height / 2f
        }
    }

    /** Returns a placement that centers the selected visual control on another element. */
    fun editorPlacementAlignedTo(id: HudElementId, targetId: HudElementId): HudPlacement? {
        if (id == targetId || !HudElementRegistry.get(id).isMovable) return null
        val current = profile.placements[id] ?: defaultPlacement(id)
        val elements = editorElements(editorProfileWith(id, current)).associateBy { it.id }
        val selected = elements[id] ?: return null
        val target = elements[targetId] ?: return null
        val (x, y) = editorAnchorForVisualCenter(
            id,
            selected,
            target.visualRect.centerX,
            target.visualRect.centerY,
        )
        val safe = safeRect(width.toFloat(), height.toFloat())
        return current.copy(
            enabled = true,
            x = ((x - safe.left) / safe.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
            y = ((y - safe.top) / safe.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
        ).let { clampEditorPlacement(id, it) }
    }

    /** Returns a placement aligned to the safe video canvas, using visual edges. */
    fun editorPlacementAlignedToCanvas(
        id: HudElementId,
        horizontal: HudEditorHorizontalAlignment? = null,
        vertical: HudEditorVerticalAlignment? = null,
    ): HudPlacement? {
        if (!HudElementRegistry.get(id).isMovable) return null
        val current = profile.placements[id] ?: defaultPlacement(id)
        val selected = editorElements(editorProfileWith(id, current)).firstOrNull { it.id == id } ?: return null
        val safe = safeRect(width.toFloat(), height.toFloat())
        val desiredCenterX = when (horizontal) {
            HudEditorHorizontalAlignment.LEFT -> safe.left + selected.visualRect.width / 2f
            HudEditorHorizontalAlignment.CENTER -> safe.centerX
            HudEditorHorizontalAlignment.RIGHT -> safe.right - selected.visualRect.width / 2f
            null -> selected.visualRect.centerX
        }
        val desiredCenterY = when (vertical) {
            HudEditorVerticalAlignment.TOP -> safe.top + selected.visualRect.height / 2f
            HudEditorVerticalAlignment.CENTER -> safe.centerY
            HudEditorVerticalAlignment.BOTTOM -> safe.bottom - selected.visualRect.height / 2f
            null -> selected.visualRect.centerY
        }
        val x = when (horizontal) {
            HudEditorHorizontalAlignment.LEFT -> editorAnchorForVerticalEdge(id, selected, safe.left, leftEdge = true)
            HudEditorHorizontalAlignment.RIGHT -> editorAnchorForVerticalEdge(id, selected, safe.right, leftEdge = false)
            else -> editorAnchorForVisualCenter(id, selected, desiredCenterX, desiredCenterY).first
        }
        val y = when (vertical) {
            HudEditorVerticalAlignment.TOP -> editorAnchorForHorizontalEdge(id, selected, safe.top, topEdge = true)
            HudEditorVerticalAlignment.BOTTOM -> editorAnchorForHorizontalEdge(id, selected, safe.bottom, topEdge = false)
            else -> editorAnchorForVisualCenter(id, selected, desiredCenterX, desiredCenterY).second
        }
        return current.copy(
            enabled = true,
            x = ((x - safe.left) / safe.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
            y = ((y - safe.top) / safe.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
        ).let { clampEditorPlacement(id, it) }
    }

    /**
     * Repairs a user's requested placement by moving only the selected control
     * the smallest useful distance. This is intentionally bounded: a drag can
     * be corrected around a nearby default control, but it can never silently
     * rearrange a carefully customized layout.
     */
    fun repairEditorPlacement(
        id: HudElementId,
        requested: HudPlacement,
        dragStart: HudPlacement? = null,
    ): HudPlacement? {
        if (!HudElementRegistry.get(id).isMovable) return null
        val raw = clampEditorPlacement(id, requested)
        if (!raw.enabled) return raw
        val rawProfile = editorProfileWith(id, raw)
        if (editorProfileIsLegal(rawProfile, affectedIds = setOf(id))) return raw

        val safe = safeRect(width.toFloat(), height.toFloat())
        val maximumCorrection = EDITOR_MAX_AUTONUDGE_DP * density
        val gap = EDITOR_AUTONUDGE_GAP_DP * density
        val bottomLimit = safe.bottom - EDITOR_BOTTOM_CHROME_CLEARANCE_DP * density
        val intendedDx = dragStart?.let { (raw.x - it.x) * safe.width } ?: 0f
        val intendedDy = dragStart?.let { (raw.y - it.y) * safe.height } ?: 0f

        data class Candidate(
            val placement: HudPlacement,
            val profile: HudProfile,
            val element: ResolvedHudElement,
            val blockers: Set<HudElementId>,
            val bottomViolation: Boolean,
            val correctionDistance: Float,
            val intentPenalty: Int,
        )

        data class Score(
            val collisions: Int,
            val intentPenalty: Int,
            val correctionDistance: Float,
        ) : Comparable<Score> {
            override fun compareTo(other: Score): Int =
                collisions.compareTo(other.collisions).takeIf { it != 0 }
                    ?: intentPenalty.compareTo(other.intentPenalty).takeIf { it != 0 }
                    ?: correctionDistance.compareTo(other.correctionDistance)
        }

        fun intentPenalty(dx: Float, dy: Float): Int {
            var penalty = 0
            if (intendedDx != 0f && dx * intendedDx < 0f) penalty++
            if (intendedDy != 0f && dy * intendedDy < 0f) penalty++
            return penalty
        }

        fun candidate(placement: HudPlacement): Candidate? {
            val clamped = clampEditorPlacement(id, placement.copy(enabled = true))
            val correctionX = (clamped.x - raw.x) * safe.width
            val correctionY = (clamped.y - raw.y) * safe.height
            val distance = kotlin.math.sqrt(correctionX * correctionX + correctionY * correctionY)
            if (distance > maximumCorrection + 0.5f) return null
            val profile = editorProfileWith(id, clamped)
            val element = editorElements(profile).firstOrNull { it.id == id } ?: return null
            return Candidate(
                placement = clamped,
                profile = profile,
                element = element,
                blockers = editorCollisionIds(id, clamped, profile),
                bottomViolation = element.visualRect.bottom > bottomLimit + 0.5f,
                correctionDistance = distance,
                intentPenalty = intentPenalty(correctionX, correctionY),
            )
        }

        fun score(value: Candidate): Score = Score(
            collisions = value.blockers.size + if (value.bottomViolation) 1 else 0,
            intentPenalty = value.intentPenalty,
            correctionDistance = value.correctionDistance,
        )

        fun isLegal(value: Candidate): Boolean =
            !value.bottomViolation && value.blockers.isEmpty() &&
                editorProfileIsLegal(value.profile, affectedIds = setOf(id))

        fun translated(value: Candidate, dx: Float, dy: Float): Candidate? {
            if (dx == 0f && dy == 0f) return null
            return candidate(
                value.placement.copy(
                    x = value.placement.x + dx / safe.width.coerceAtLeast(1f),
                    y = value.placement.y + dy / safe.height.coerceAtLeast(1f),
                ),
            )
        }

        fun neighbors(value: Candidate): List<Candidate> {
            val result = mutableListOf<Candidate>()
            value.blockers.forEach { blockerId ->
                val blocker = editorElements(value.profile).firstOrNull { it.id == blockerId } ?: return@forEach
                val selected = value.element.visualRect
                val blockerRect = blocker.visualRect
                result += listOfNotNull(
                    translated(value, blockerRect.left - gap - selected.right, 0f),
                    translated(value, blockerRect.right + gap - selected.left, 0f),
                    translated(value, 0f, blockerRect.top - gap - selected.bottom),
                    translated(value, 0f, blockerRect.bottom + gap - selected.top),
                )
            }
            if (value.bottomViolation) {
                result += listOfNotNull(translated(value, 0f, bottomLimit - value.element.visualRect.bottom))
            }
            return result
                .distinctBy { it.placement.x to it.placement.y }
                .sortedWith(Comparator { left, right -> score(left).compareTo(score(right)) })
        }

        val initial = candidate(raw) ?: return null
        val frontier = ArrayDeque<Candidate>().apply { add(initial) }
        val visited = mutableSetOf<Pair<Int, Int>>()
        fun key(value: HudPlacement): Pair<Int, Int> =
            (value.x * 10000f).roundToInt() to (value.y * 10000f).roundToInt()
        visited += key(initial.placement)
        var best = initial
        repeat(6) {
            val levelSize = frontier.size
            repeat(levelSize) {
                val current = frontier.removeFirst()
                if (score(current) < score(best)) best = current
                if (isLegal(current)) return current.placement
                neighbors(current).forEach { next ->
                    if (visited.add(key(next.placement))) frontier.addLast(next)
                }
            }
            if (frontier.isEmpty()) return@repeat
        }
        return best.takeIf(::isLegal)?.placement
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
        if (!HudElementRegistry.get(id).isMovable) {
            return HudEditorDropResult(
                profile = null,
                selectedPlacement = null,
                movedElements = emptySet(),
                kind = HudEditorDropKind.REJECTED,
                guides = emptyList(),
                blockers = emptySet(),
                explanation = "Playback timeline is fixed to the bottom of the video",
            )
        }
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

        // Correct the selected control first. This is the common case when a
        // user drops a button on the edge of the permanent scrub chrome or a
        // nearby default control. Their explicit choice wins; implicit
        // defaults are not rearranged just to make a drop possible.
        val repaired = repairEditorPlacement(id, raw, dragStart)
        if (repaired != null) {
            val repairedProfile = editorProfileWith(id, repaired)
            return HudEditorDropResult(
                profile = repairedProfile,
                selectedPlacement = repaired,
                movedElements = changedElements(profile, repairedProfile),
                kind = HudEditorDropKind.NUDGED,
                guides = snap.guides,
                blockers = rawBlockers,
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
        if (!HudElementRegistry.get(id).isMovable) return placement
        val safe = safeRect(width.toFloat(), height.toFloat())
        val testProfile = profile.copy(mode = HudProfileMode.CUSTOM, placements = profile.placements + (id to placement))
        val test = resolve(safe, testProfile, HudElementRegistry.activeIds).firstOrNull { it.id == id } ?: return placement
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
        return resolve(safe, candidateProfile.copy(mode = HudProfileMode.CUSTOM), HudElementRegistry.activeIds)
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
            if (!selected.visualRect.isInside(safe) || editorViolatesBottomChrome(selected)) return false
            return editorCollisionIds(selectedId, selectedPlacement(selectedId, selected, candidateProfile), candidateProfile).isEmpty()
        }
        return elements.all { element ->
            element.visualRect.isInside(safe) &&
                (affectedIds == null || element.id !in affectedIds || !editorViolatesBottomChrome(element))
        } &&
            elements.withIndex().none { (index, element) ->
                elements.drop(index + 1).any { other ->
                    (affectedIds == null || element.id in affectedIds || other.id in affectedIds) &&
                        element.visualRect.overlaps(other.visualRect)
                }
            }
    }

    private fun editorViolatesBottomChrome(element: ResolvedHudElement): Boolean =
        element.visualRect.bottom >
            safeRect(width.toFloat(), height.toFloat()).bottom - EDITOR_BOTTOM_CHROME_CLEARANCE_DP * density + 0.5f

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
            .filter { it.id != id && it.visualRect.overlaps(candidate.visualRect) }
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
                if (editorProfileIsLegal(candidate.first, affectedIds = affected + id)) return candidate
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
        val selectedCenter = if (horizontal) selected.visualRect.centerX else selected.visualRect.centerY
        val blockerCenter = blockers.mapNotNull { elements[it] }
            .map { if (horizontal) it.visualRect.centerX else it.visualRect.centerY }
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
        repeat(HudElementRegistry.activeIds.size) {
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
                            candidateElements[movedId]?.visualRect?.overlaps(element.visualRect) == true
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
                if (horizontal) compareBy<ResolvedHudElement> { it.visualRect.left }
                else compareBy<ResolvedHudElement> { it.visualRect.top },
            )
        if (lane.isEmpty()) return null

        val spacing = HudDefaultLayout.SPACING * density
        val translation = if (horizontal) {
            if (direction < 0) {
                selected.visualRect.left - spacing - lane.maxOf { it.visualRect.right }
            } else {
                selected.visualRect.right + spacing - lane.minOf { it.visualRect.left }
            }
        } else if (direction < 0) {
            selected.visualRect.top - spacing - lane.maxOf { it.visualRect.bottom }
        } else {
            selected.visualRect.bottom + spacing - lane.minOf { it.visualRect.top }
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
        HudElementRegistry.get(id).isMovable &&
            id != HudElementId.STREAM_INFO &&
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
                safeRect(width.toFloat(), height.toFloat()).height < 260f * density,
            ) -> 0
            id == HudElementId.SEEK_BACK ||
                id == HudElementId.PLAY_PAUSE ||
                id == HudElementId.SEEK_FORWARD -> 1
            id == HudElementId.VOLUME ||
                id == HudElementId.CLIP ||
            id == HudElementId.MORE ||
            id == HudElementId.CAPTIONS ||
            id == HudElementId.CHAT ||
            id == HudElementId.FULLSCREEN ||
            id == HudElementId.INTERACTION_LOCK -> 2
            else -> null
        }
    }

    private fun defaultLaneIsHorizontal(lane: Int): Boolean = when (lane) {
        0, 1, 2 -> true
        else -> false
    }

    private fun changedElements(before: HudProfile, after: HudProfile): Set<HudElementId> =
        HudElementRegistry.activeIds.filterTo(linkedSetOf()) { id ->
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
            HudEditorGuideKind.SAFE_DIVISION -> 2
            HudEditorGuideKind.SIBLING_CENTER -> 3
            HudEditorGuideKind.SIBLING_EDGE -> 4
            HudEditorGuideKind.CONTROL_BASELINE -> 5
        }
        return guides.minWithOrNull(
            compareBy<HudEditorGuide>(
                { abs(it.snapCoordinate - coordinate) },
                { priority(it) },
                { it.source?.ordinal ?: -1 },
                { it.snapCoordinate },
            ),
        )?.takeIf { abs(it.snapCoordinate - coordinate) <= threshold }
    }

    fun editorDropHasCollision(id: HudElementId, placement: HudPlacement): Boolean {
        return editorCollisionIds(id, placement).isNotEmpty()
    }

    fun editorProfileHasCollision(candidateProfile: HudProfile): Boolean {
        val elements = resolve(
            safeRect(width.toFloat(), height.toFloat()),
            candidateProfile.copy(mode = HudProfileMode.CUSTOM),
            HudElementRegistry.activeIds,
        )
        return elements.withIndex().any { (index, element) ->
            elements.drop(index + 1).any { other ->
                element.visualRect.overlaps(other.visualRect)
            }
        }
    }

    fun collisionFreeEditorPlacement(id: HudElementId, placement: HudPlacement): HudPlacement? {
        repairEditorPlacement(id, placement.copy(enabled = true))?.let { return it }
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
            .map { clampEditorPlacement(id, it.copy(enabled = true)) }
            .mapNotNull { candidate ->
                repairEditorPlacement(id, candidate) ?: candidate.takeIf {
                    editorProfileIsLegal(editorProfileWith(id, it), affectedIds = setOf(id))
                }
            }
            .firstOrNull { editorProfileIsLegal(editorProfileWith(id, it), affectedIds = setOf(id)) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
        val safe = safeRect(measuredWidth.toFloat(), measuredHeight.toFloat())
        val compact = safe.height < 260f * density
        findViewById<HudTimelineContent>(R.id.timelineContent)?.measure(
            MeasureSpec.makeMeasureSpec(safe.width.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(
                (HudDefaultLayout.TIMELINE_TOUCH_TARGET_HEIGHT * density).roundToInt()
                    .coerceAtMost(safe.height.roundToInt().coerceAtLeast(1)),
                MeasureSpec.EXACTLY,
            ),
        )
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
            frame.naturalVisualSize()
        }
        resolved = resolve(safe, profile, availability, measuredSizes).associateBy { it.id }
        frames.forEach { (id, frame) ->
            val element = resolved[id]
            frame.setGeometry(element)
            frame.setActive(element != null)
            frame.setInteractionBlocked(interactionLocked && id != HudElementId.INTERACTION_LOCK)
            if (element != null) {
                frame.applyPresentationScale(element.effectiveScale)
                frame.measure(
                    MeasureSpec.makeMeasureSpec(element.hitRect.width.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(element.hitRect.height.roundToInt().coerceAtLeast(1), MeasureSpec.EXACTLY),
                )
            }
        }
        edgeMarker?.let { marker ->
            val radius = edgeMarkerBar?.edgeMarkerRadiusPx() ?: 0f
            val size = (radius * 2f).roundToInt().coerceAtLeast(0)
            marker.measure(
                MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            )
        }
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !is HudElementFrame && child !is HudTimelineContent && child !== edgeMarker) {
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
        val fixedTimeline = findViewById<HudTimelineContent>(R.id.timelineContent)
        val safe = safeRect(width.toFloat(), height.toFloat())
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
            } else if (child === fixedTimeline) {
                child.layout(
                    safe.left.roundToInt(),
                    (safe.bottom - child.measuredHeight).roundToInt(),
                    safe.right.roundToInt(),
                    safe.bottom.roundToInt(),
                )
            } else if (child === edgeMarker) {
                child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            } else {
                child.layout(0, 0, width, height)
            }
        }
        updateEdgeMarker()
    }

    private fun updateEdgeMarker() {
        val marker = edgeMarker ?: return
        val bar = edgeMarkerBar ?: run {
            marker.visibility = GONE
            return
        }
        val fraction = bar.edgeMarkerFraction()
        if (bar.visibility != VISIBLE || fraction == null || bar.width <= 0 || bar.height <= 0) {
            marker.visibility = GONE
            return
        }
        val radius = bar.edgeMarkerRadiusPx()
        if (radius <= 0f) {
            marker.visibility = GONE
            return
        }
        val barRect = Rect(0, 0, bar.width, bar.height)
        offsetDescendantRectToMyCoords(bar, barRect)
        val centerX = barRect.left + bar.edgeMarkerCenterX(fraction)
        val size = (radius * 2f).roundToInt().coerceAtLeast(1)
        marker.setMarkerColor(bar.edgeMarkerColor())
        marker.visibility = VISIBLE
        val markerLeft = (centerX - size / 2f).roundToInt()
        // The line is painted on the last pixels of the video. Center the knob
        // on the line itself, like a conventional video player, instead of
        // floating it wholly above the boundary. PlayerHudLayout and its
        // player/preview hosts deliberately allow this small overflow.
        val lineCenterY = barRect.top + bar.edgeMarkerLineCenterY()
        val markerTop = (lineCenterY - size / 2f).roundToInt()
        marker.layout(markerLeft, markerTop, markerLeft + size, markerTop + size)
    }

    private class EdgeMarkerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fun setMarkerColor(color: Int) {
            if (paint.color != color) {
                paint.color = color
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val radius = minOf(width, height) / 2f
            if (radius > 0f) canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (interactionLocked) {
            return dispatchLockedTouchEvent(event)
        }
        if (!editing) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editorSelected = resolved.values
                    .asSequence()
                    .filter { HudElementRegistry.get(it.id).isMovable && it.visualRect.contains(event.x, event.y) }
                    .minWithOrNull(
                        compareBy<ResolvedHudElement>(
                            { distanceToRect(it.visualRect, event.x, event.y) },
                            // Later children are visually on top when two
                            // controls are intentionally touching.
                            { -it.id.ordinal },
                        ),
                    )
                    ?.id
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

    /**
     * Keep the lock effective even when the player gesture layer sends a
     * motion event directly to this root instead of through PlayerLayout.
     */
    private fun dispatchLockedTouchEvent(event: MotionEvent): Boolean {
        val unlock = interactionUnlockView?.takeIf { it.isShown }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interactionUnlockGesture = unlock != null && isInsideInteractionUnlock(event, unlock)
            }
            MotionEvent.ACTION_POINTER_DOWN -> interactionUnlockGesture = false
        }
        if (!interactionUnlockGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                interactionUnlockGesture = false
            }
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            interactionUnlockGesture = false
        }
        return handled || true
    }

    private fun isInsideInteractionUnlock(event: MotionEvent, unlock: View): Boolean {
        unlock.getDrawingRect(interactionUnlockHitRect)
        offsetDescendantRectToMyCoords(unlock, interactionUnlockHitRect)
        val minimumTarget = (48f * density).roundToInt()
        if (interactionUnlockHitRect.width() < minimumTarget || interactionUnlockHitRect.height() < minimumTarget) {
            val centerX = interactionUnlockHitRect.centerX()
            val centerY = interactionUnlockHitRect.centerY()
            val width = maxOf(interactionUnlockHitRect.width(), minimumTarget)
            val height = maxOf(interactionUnlockHitRect.height(), minimumTarget)
            interactionUnlockHitRect.set(
                centerX - width / 2,
                centerY - height / 2,
                centerX + (width + 1) / 2,
                centerY + (height + 1) / 2,
            )
        }
        return interactionUnlockHitRect.contains(event.x.roundToInt(), event.y.roundToInt())
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = if (!editing) {
        false
    } else {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> resolved.values.any {
                HudElementRegistry.get(it.id).isMovable && it.visualRect.contains(event.x, event.y)
            }
            else -> editorSelected != null
        }
    }

    private fun distanceToRect(rect: HudRect, x: Float, y: Float): Float {
        val dx = when {
            x < rect.left -> rect.left - x
            x > rect.right -> x - rect.right
            else -> 0f
        }
        val dy = when {
            y < rect.top -> rect.top - y
            y > rect.bottom -> y - rect.bottom
            else -> 0f
        }
        return kotlin.math.sqrt(dx * dx + dy * dy)
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
    ).resolve(
        safe,
        orientation,
        value,
        sizes,
        availability = visible,
        liveTimePosition = store.loadTimelineTimePosition(),
    )

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
        val aspect = parentGroup.findViewById<View>(R.id.aspectRatioFrameLayout)
            ?: return HudRect(0f, 0f, rootWidth, rootHeight)
        val output = listOf(
            parentGroup.findViewById<View>(R.id.playerSurface),
            parentGroup.findViewById<View>(R.id.playerTextureView),
        ).firstOrNull { it?.isShown == true && it.measuredWidth > 0 && it.measuredHeight > 0 }

        fun offsetToAncestor(view: View): Pair<Float, Float>? {
            var current: View? = view
            var x = 0f
            var y = 0f
            while (current != null && current !== parentGroup) {
                x += current.left - current.scrollX
                y += current.top - current.scrollY
                current = current.parent as? View
            }
            return (x to y).takeIf { current === parentGroup }
        }

        val viewport = output?.let { rendered ->
            val renderedOffset = offsetToAncestor(rendered)
            val hudOffset = offsetToAncestor(this)
            if (renderedOffset != null && hudOffset != null) {
                val left = renderedOffset.first - hudOffset.first
                val top = renderedOffset.second - hudOffset.second
                HudRect(left, top, left + rendered.width, top + rendered.height)
            } else {
                null
            }
        } ?: run {
            val videoWidth = aspect.measuredWidth.takeIf { it > 0 } ?: aspect.width
            val videoHeight = aspect.measuredHeight.takeIf { it > 0 } ?: aspect.height
            val aspectOffset = offsetToAncestor(aspect)
            val hudOffset = offsetToAncestor(this)
            if (videoWidth <= 0 || videoHeight <= 0 || aspectOffset == null || hudOffset == null) {
                return HudRect(0f, 0f, rootWidth, rootHeight)
            }
            HudRect(
                aspectOffset.first - hudOffset.first,
                aspectOffset.second - hudOffset.second,
                aspectOffset.first - hudOffset.first + videoWidth,
                aspectOffset.second - hudOffset.second + videoHeight,
            )
        }

        val boundedLeft = viewport.left.coerceIn(0f, rootWidth)
        val boundedTop = viewport.top.coerceIn(0f, rootHeight)
        val boundedRight = viewport.right.coerceIn(boundedLeft, rootWidth)
        val boundedBottom = viewport.bottom.coerceIn(boundedTop, rootHeight)
        return HudRect(boundedLeft, boundedTop, boundedRight, boundedBottom)
    }

    private fun runtimeAvailability(): Set<HudElementId> = HudElementRegistry.activeIds.filterTo(mutableSetOf()) { id ->
        val frame = frames[id] ?: return@filterTo false
        when (id) {
            HudElementId.STREAM_INFO -> listOf(R.id.channelAvatar, R.id.channel, R.id.title, R.id.category, R.id.viewersLayout).any(::isShown)
            HudElementId.TIME_STATUS -> isShown(R.id.liveTimeGroup) || isShown(R.id.bufferHealthGroup)
            HudElementId.CAPTIONS -> listOf(R.id.liveCaptions, R.id.subtitles).any(::isShown)
            else -> hasBoundAction(frame)
        }
    }

    private fun isShown(id: Int): Boolean = findViewById<View>(id)?.visibility == VISIBLE

    private fun hasBoundAction(view: View): Boolean {
        if (view.visibility != VISIBLE) return false
        if (view.hasOnClickListeners() || view.isLongClickable) return true
        if (view !is ViewGroup) return false
        return (0 until view.childCount).any { hasBoundAction(view.getChildAt(it)) }
    }

    private fun defaultPlacement(id: HudElementId): HudPlacement {
        val defaultProfile = PlayerHudDefaults.config().profile(orientation).copy(
            globalScale = profile.globalScale,
            defaultPolicyVersion = profile.defaultPolicyVersion,
        )
        return resolve(safeRect(width.toFloat(), height.toFloat()), defaultProfile, HudElementRegistry.activeIds)
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
        val topLeft = findViewById<LinearLayout>(R.id.topLeftLayout)
        val horizontalBackgroundPadding = (topLeft?.paddingLeft ?: 0) + (topLeft?.paddingRight ?: 0)
        val avatar = findViewById<View>(R.id.channelAvatar)
        val avatarParams = avatar?.layoutParams as? ViewGroup.MarginLayoutParams
        val avatarWidth = if (avatar?.visibility == View.GONE) {
            0f
        } else {
            (avatarParams?.width ?: (40f * density).roundToInt()).toFloat() +
                (avatarParams?.rightMargin ?: (10f * density).roundToInt())
        }
        val textWidth = (compositionWidth - horizontalBackgroundPadding - avatarWidth)
            .coerceAtLeast(40f * density)
            .roundToInt()

        // Give the metadata composition one deterministic width. The details
        // row is packed: Playing and the viewer target keep their measured
        // widths, while category is the only field allowed to shrink.
        topLeft?.updateLayoutParams<ViewGroup.LayoutParams> {
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
        val compact = safe.height < 260f * density
        findViewById<LinearLayout>(R.id.streamDetailsLayout)?.orientation =
            if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        findViewById<LinearLayout>(R.id.titleAndViewersLayout)?.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = ((if (compact) 1f else 2f) * density).roundToInt()
        }
        findViewById<LinearLayout>(R.id.streamDetailsLayout)?.updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = ((if (compact) 1f else 2f) * density).roundToInt()
        }
        findViewById<LinearLayout>(R.id.playingCategoryLayout)?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = if (compact) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            weight = 0f
        }
        viewers?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            weight = 0f
        }

        playing?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            weight = 0f
            marginEnd = ((if (compact) 1f else 2f) * density).roundToInt()
        }
        category?.updateLayoutParams<LinearLayout.LayoutParams> {
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            weight = 0f
            marginStart = 0
            marginEnd = ((if (compact) 2f else 4f) * density).roundToInt()
        }
        val fixedDetailsWidth = measuredWrapContentWidth(playing, safe.height) +
            if (compact) 0 else measuredWrapContentWidth(viewers, safe.height)
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
        findViewById<TextView>(R.id.channel)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 12f else 15f)
            includeFontPadding = !compact
        }
        findViewById<TextView>(R.id.title)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 11f else 15f)
            includeFontPadding = !compact
            // The compact player has usable vertical space before the
            // transport row. A third line keeps long stream descriptions
            // readable without widening the metadata into the top-right
            // controls.
            maxLines = if (compact) 3 else 2
        }
        findViewById<TextView>(R.id.playingLabel)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 10f else 12f)
            includeFontPadding = !compact
        }
        findViewById<TextView>(R.id.category)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 10f else 12f)
            includeFontPadding = !compact
        }
        findViewById<TextView>(R.id.viewersText)?.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (compact) 10f else 12f)
            includeFontPadding = !compact
        }
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
        findViewById<TextView>(R.id.bufferHealthGroup)?.apply {
            visibility = VISIBLE
            text = "5s↓ / 8s"
            contentDescription = "5 seconds buffered, decreasing, 8 seconds behind live"
        }
        findViewById<HudTimelineContent>(R.id.timelineContent)?.apply {
            setLiveRewindEnabled(true)
        }
    }

    private fun restoreRuntimeContent() {
        findViewById<View>(R.id.channelAvatar)?.background = null
        findViewById<View>(R.id.liveTimeGroup)?.visibility = GONE
        findViewById<View>(R.id.bufferHealthGroup)?.visibility = GONE
        findViewById<HudTimelineContent>(R.id.timelineContent)?.setLiveRewindEnabled(false)
        refreshAvailability()
    }

    private fun currentOrientation(): HudOrientation = if (
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    ) HudOrientation.PORTRAIT else HudOrientation.LANDSCAPE

    private fun hypot(x: Float, y: Float): Float = kotlin.math.sqrt(x * x + y * y)

}
