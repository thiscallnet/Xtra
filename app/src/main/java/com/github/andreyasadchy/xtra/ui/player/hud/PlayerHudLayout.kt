package com.github.andreyasadchy.xtra.ui.player.hud

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ImageView
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
    private var editorOnMoved: ((HudElementId, HudPlacement) -> Unit)? = null
    private var editorOnDropped: ((HudElementId, Boolean) -> Unit)? = null

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
        onMoved: ((HudElementId, HudPlacement) -> Unit)? = null,
        onDropped: ((HudElementId, Boolean) -> Unit)? = null,
    ) {
        editing = enabled
        editorOnSelected = onSelected
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

    fun snapEditorPlacement(id: HudElementId): HudPlacement {
        val current = profile.placements[id] ?: defaultPlacement(id)
        val safe = safeRect(width.toFloat(), height.toFloat())
        val selected = resolved[id] ?: return current
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
        val guideDistance = 8f * density
        val xGuides = buildList {
            add(safe.left)
            add(safe.centerX)
            add(safe.right)
            resolved.values.filter { it.id != id }.forEach { add(it.visualRect.centerX) }
        }
        val yGuides = buildList {
            add(safe.top)
            add(safe.centerY)
            add(safe.bottom)
            add(safe.bottom - 48f * density)
            resolved.values.filter { it.id != id }.forEach { add(it.visualRect.centerY) }
        }
        val snappedX = xGuides.minByOrNull { abs(it - currentX) }
            ?.takeIf { abs(it - currentX) <= guideDistance }
            ?: currentX
        val snappedY = yGuides.minByOrNull { abs(it - currentY) }
            ?.takeIf { abs(it - currentY) <= guideDistance }
            ?: currentY
        val next = current.copy(
            x = ((snappedX - safe.left) / safe.width.coerceAtLeast(1f)).coerceIn(0f, 1f),
            y = ((snappedY - safe.top) / safe.height.coerceAtLeast(1f)).coerceIn(0f, 1f),
        )
        return clampEditorPlacement(id, next)
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

    fun editorDropHasCollision(id: HudElementId, placement: HudPlacement): Boolean {
        val safe = safeRect(width.toFloat(), height.toFloat())
        val candidateProfile = profile.copy(
            mode = HudProfileMode.CUSTOM,
            placements = profile.placements + (id to placement),
        )
        val interactive = resolve(safe, candidateProfile, HudElementId.entries.toSet())
            .filter { HudElementRegistry.get(it.id).isInteractive }
        val candidate = interactive
            .associateBy { it.id }
            .getValue(id)
        return interactive
            .any { it.id != id && it.hitRect.overlaps(candidate.hitRect, density * 4f) }
    }

    fun editorProfileHasCollision(candidateProfile: HudProfile): Boolean {
        val elements = resolve(
            safeRect(width.toFloat(), height.toFloat()),
            candidateProfile.copy(mode = HudProfileMode.CUSTOM),
            HudElementId.entries.toSet(),
        ).filter { HudElementRegistry.get(it.id).isInteractive }
        return elements.withIndex().any { (index, element) ->
            elements.drop(index + 1).any { other ->
                element.hitRect.overlaps(other.hitRect, density * 4f)
            }
        }
    }

    fun collisionFreeEditorPlacement(id: HudElementId, placement: HudPlacement): HudPlacement? {
        val semantic = HudDefaultLayout.semanticFallback(id, orientation).copy(
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
        if (compactMetricsApplied != compact) {
            applyCompactMetrics(compact)
            compactMetricsApplied = compact
        }
        applyMetadataWidth(safe)
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
                if (!editorDragging && hypot(event.x - editorStartX, event.y - editorStartY) > slop) editorDragging = true
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
                    editorOnDropped?.invoke(id, canceled)
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
        val insets = safeInsetsOverride ?: safeInsets
        return HudRect(
        insets.left.toFloat().coerceAtMost(rootWidth / 2f),
        insets.top.toFloat().coerceAtMost(rootHeight / 2f),
        (rootWidth - insets.right).coerceAtLeast(rootWidth / 2f),
        (rootHeight - insets.bottom).coerceAtLeast(rootHeight / 2f),
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
        val defaultProfile = PlayerHudDefaults.config().profile(orientation).copy(globalScale = profile.globalScale)
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
            ?: HudDefaultLayout.semanticFallback(id, orientation).copy(
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
            .coerceAtLeast(80f * density)
        val avatarWidth = findViewById<View>(R.id.channelAvatar)?.layoutParams?.width?.toFloat() ?: 40f * density
        val textWidth = (compositionWidth - avatarWidth - 10f * density).coerceAtLeast(40f * density).roundToInt()
        listOf(R.id.channel, R.id.title, R.id.category).forEach { id ->
            findViewById<TextView>(id)?.let { textView ->
                if (textView.maxWidth != textWidth) textView.maxWidth = textWidth
            }
        }
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
        frames.values.forEach { frame ->
            setDescendantsVisible(frame, true)
        }
        findViewById<View>(R.id.liveTimeGroup)?.visibility = GONE
    }

    private fun restoreRuntimeContent() {
        findViewById<View>(R.id.channelAvatar)?.background = null
        findViewById<View>(R.id.liveTimeGroup)?.visibility = GONE
        refreshAvailability()
    }

    private fun refreshTimelineSettings() {
        findViewById<HudTimelineContent>(R.id.timelineContent)
            ?.setLiveRewindTimePosition(store.loadTimelineTimePosition())
    }

    private fun setDescendantsVisible(view: View, visible: Boolean) {
        if (view.id != id && view.id != R.id.timelineContent) view.visibility = if (visible) VISIBLE else view.visibility
        if (view is ViewGroup) (0 until view.childCount).forEach { setDescendantsVisible(view.getChildAt(it), visible) }
    }

    private fun currentOrientation(): HudOrientation = if (
        resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    ) HudOrientation.PORTRAIT else HudOrientation.LANDSCAPE

    private fun hypot(x: Float, y: Float): Float = kotlin.math.sqrt(x * x + y * y)

}
