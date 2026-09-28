package com.github.andreyasadchy.xtra.ui.saved.clips

import android.graphics.Rect
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.andreyasadchy.xtra.ui.common.PlayerOverlaySafeSide
import com.github.andreyasadchy.xtra.ui.common.findPlayerOverlaySafeRegion
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps the Clips empty state and its controls out of the free-position mini-player. */
internal class ClipsPlayerOverlayAvoidance(
    lifecycleOwner: LifecycleOwner,
    private val root: View,
    private val controlsHost: FrameLayout,
    private val controlsContent: LinearLayout,
    private val clipsInfo: LinearLayout,
    private val clipCount: TextView,
    private val autoplaySummary: TextView,
    private val autoplayCompactLabel: TextView,
    private val autoplay: TextView,
    private val contentContainer: FrameLayout,
    private val emptyState: View,
    private val emptyStateContent: LinearLayout,
    private val emptyStateTitle: TextView,
    private val playerContainer: View,
) : AutoCloseable {

    private data class BaseEmptyGeometry(
        val bounds: Rect,
        val naturalWidth: Int,
        val naturalHeight: Int,
    )

    private data class EmptyContentMeasure(
        val textHeight: Int,
    )

    private val controlsBaseParams = FrameLayout.LayoutParams(controlsContent.layoutParams as FrameLayout.LayoutParams)
    private val controlsHostBaseParams = LinearLayout.LayoutParams(controlsHost.layoutParams as LinearLayout.LayoutParams)
    private val infoBaseParams = LinearLayout.LayoutParams(clipsInfo.layoutParams as LinearLayout.LayoutParams)
    private val autoplayBaseParams = LinearLayout.LayoutParams(autoplay.layoutParams as LinearLayout.LayoutParams)
    private val emptyBaseParams = FrameLayout.LayoutParams(emptyStateContent.layoutParams as FrameLayout.LayoutParams)
    private val originalEmptyContentVisibility = emptyStateContent.visibility
    private val originalEmptyPaddingStart = emptyStateContent.paddingStart
    private val originalEmptyPaddingTop = emptyStateContent.paddingTop
    private val originalEmptyPaddingEnd = emptyStateContent.paddingEnd
    private val originalEmptyPaddingBottom = emptyStateContent.paddingBottom
    private val originalControlsOrientation = controlsContent.orientation
    private val originalControlsGravity = controlsContent.gravity
    private val originalAutoplayMaxWidth = autoplay.maxWidth
    private val originalCompactLabelMaxWidth = autoplayCompactLabel.maxWidth
    private val originalAutoplayText = autoplay.text?.toString().orEmpty()
    private val originalAutoplayContentDescription = autoplay.contentDescription
    private val originalClipCountTextSize = clipCount.textSize
    private val originalSummaryTextSize = autoplaySummary.textSize
    private val density = root.resources.displayMetrics.density
    private val gapPx = (8f * density).roundToInt()
    private val widthStepPx = (8f * density).roundToInt().coerceAtLeast(1)
    private val horizontalSafeSides = setOf(PlayerOverlaySafeSide.LEFT, PlayerOverlaySafeSide.RIGHT)
    private val pageBounds = Rect()
    private val overlayBounds = Rect()
    private val controlsBounds = Rect()
    private val controlsPageBounds = Rect()
    private val infoBounds = Rect()
    private val autoplayBounds = Rect()
    private var naturalSwitchWidth = 0
    private var naturalSwitchHeight = 0
    private var naturalControlsHostHeight = 0
    private var cachedBaseParentWidth = -1
    private var cachedBaseParentHeight = -1
    private var cachedBaseNaturalWidth = 0
    private var cachedBaseNaturalHeight = 0
    private val emptyContentMeasureCache = LinkedHashMap<Int, EmptyContentMeasure>(8, 0.75f, true)
    private var preferredControlsSide: PlayerOverlaySafeSide? = null
    private var preferredControlsHostSide: PlayerOverlaySafeSide? = null
    private var preferredEmptySide: PlayerOverlaySafeSide? = null
    private var autoplayCompactClickListenerInstalled = false
    private val autoplayCompactClickListener = View.OnClickListener { autoplay.performClick() }
    private var activeObserver: ViewTreeObserver? = null
    private var hasPreDrawListener = false
    private var isClosed = false

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        updateLayout()
        true
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = attachToTreeObserver()

        override fun onViewDetachedFromWindow(view: View) = detachFromTreeObserver()
    }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            close()
            owner.lifecycle.removeObserver(this)
        }
    }

    init {
        root.addOnAttachStateChangeListener(attachListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        if (root.isAttachedToWindow) attachToTreeObserver()
    }

    private fun attachToTreeObserver() {
        val nextObserver = root.viewTreeObserver
        if (!nextObserver.isAlive) return
        if (activeObserver !== nextObserver) {
            detachFromTreeObserver()
            activeObserver = nextObserver
        }
        if (!hasPreDrawListener) {
            nextObserver.addOnPreDrawListener(preDrawListener)
            hasPreDrawListener = true
        }
        updateLayout()
    }

    private fun detachFromTreeObserver() {
        activeObserver?.let { observer ->
            if (hasPreDrawListener && observer.isAlive) observer.removeOnPreDrawListener(preDrawListener)
        }
        activeObserver = null
        hasPreDrawListener = false
    }

    private fun updateLayout() {
        if (isClosed) return
        val overlay = playerContainer.findViewById<View>(com.github.andreyasadchy.xtra.R.id.playerLayout)
        if (!root.isShown || overlay == null || !overlay.isShown || !isScaledOverlay(overlay)) {
            restoreControlsHostPosition()
            restoreControls()
            positionEmptyStateInCurrentPageOrRestore()
            preferredControlsSide = null
            preferredControlsHostSide = null
            preferredEmptySide = null
            return
        }
        if (!overlay.getGlobalVisibleRect(overlayBounds)) {
            restoreControls()
            positionEmptyStateInCurrentPageOrRestore()
            return
        }

        val controlsVisible = controlsHost.getGlobalVisibleRect(controlsBounds)
        if (controlsVisible && root.getGlobalVisibleRect(controlsPageBounds)) {
            if (naturalSwitchWidth == 0) naturalSwitchWidth = autoplay.measuredWidth
            if (naturalSwitchHeight == 0) naturalSwitchHeight = autoplay.measuredHeight
            if (naturalControlsHostHeight == 0) naturalControlsHostHeight = controlsHost.measuredHeight
            val hostParams = controlsHost.layoutParams as? LinearLayout.LayoutParams
            val hostVerticalShift = (hostParams?.topMargin ?: controlsHostBaseParams.topMargin) -
                controlsHostBaseParams.topMargin
            val baselineControlsBounds = Rect(controlsBounds).apply { offset(0, -hostVerticalShift) }
            val expandedPlayer = Rect(overlayBounds).apply { inset(-gapPx, -gapPx) }
            if (!Rect.intersects(baselineControlsBounds, expandedPlayer)) {
                restoreControlsHostPosition()
                restoreControls()
                preferredControlsHostSide = null
            } else if (moveControlsHostToSafeVerticalRegion(baselineControlsBounds, overlayBounds)) {
                restoreControlsGroup()
                preferredControlsSide = null
            } else {
                restoreControlsHostPosition()
                updateControls(baselineControlsBounds, overlayBounds)
            }
        }
        if (emptyState.visibility == View.VISIBLE && contentContainer.getGlobalVisibleRect(pageBounds)) {
            val baseGeometry = getBaseEmptyGeometry(pageBounds)
            val expandedPlayer = Rect(overlayBounds).apply { inset(-gapPx, -gapPx) }
            if (Rect.intersects(baseGeometry.bounds, expandedPlayer)) {
                updateEmptyState(pageBounds, overlayBounds, baseGeometry)
            } else {
                positionEmptyStateInPage(pageBounds, baseGeometry)
                preferredEmptySide = null
            }
        } else {
            restoreEmptyState()
            preferredEmptySide = null
        }
    }

    private fun updateControls(hostBounds: Rect, playerBounds: Rect) {
        val rtl = controlsHost.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val physicalStartPadding = if (rtl) controlsContent.paddingEnd else controlsContent.paddingStart
        val physicalEndPadding = if (rtl) controlsContent.paddingStart else controlsContent.paddingEnd
        val baseContentLeft = hostBounds.left + physicalStartPadding
        val baseContentRight = hostBounds.right - physicalEndPadding
        val switchWidth = naturalSwitchWidth.takeIf { it > 0 } ?: autoplay.measuredWidth
        if (switchWidth <= 0) return

        val baseSwitchBounds = if (rtl) {
            Rect(baseContentLeft, hostBounds.top, baseContentLeft + switchWidth, hostBounds.bottom)
        } else {
            Rect(baseContentRight - switchWidth, hostBounds.top, baseContentRight, hostBounds.bottom)
        }
        val baseInfoBounds = if (rtl) {
            Rect(baseSwitchBounds.right, hostBounds.top, baseContentRight, hostBounds.bottom)
        } else {
            Rect(baseContentLeft, hostBounds.top, baseSwitchBounds.left, hostBounds.bottom)
        }
        val expandedPlayer = Rect(playerBounds).apply { inset(-gapPx, -gapPx) }
        val infoOverlapped = Rect.intersects(baseInfoBounds, expandedPlayer)
        val autoplayOverlapped = Rect.intersects(baseSwitchBounds, expandedPlayer)

        if (autoplayOverlapped || infoOverlapped) {
            val trackWidth = getSwitchTrackWidth(rtl)
            val switchTouchWidth = max(trackWidth, dp(48))
            val switchTouchHeight = max(naturalSwitchHeight, dp(48))
            val baseTrackBounds = Rect(baseSwitchBounds).apply {
                if (rtl) {
                    right = (left + trackWidth).coerceAtMost(baseSwitchBounds.right)
                } else {
                    left = (right - trackWidth).coerceAtLeast(baseSwitchBounds.left)
                }
            }
            val baseTrackLeft = if (rtl) baseTrackBounds.left else baseTrackBounds.right - trackWidth
            val baseTouchLeft = if (rtl) baseTrackLeft else baseTrackLeft + trackWidth - switchTouchWidth
            val baseTouchTop = hostBounds.top + (hostBounds.height() - switchTouchHeight) / 2
            val baseTouchBounds = Rect(
                baseTouchLeft,
                baseTouchTop,
                baseTouchLeft + switchTouchWidth,
                baseTouchTop + switchTouchHeight,
            )
            updateControlsWithSwitch(
                hostBounds = hostBounds,
                playerBounds = playerBounds,
                trackWidth = trackWidth,
                switchTouchWidth = switchTouchWidth,
                switchTouchHeight = switchTouchHeight,
                baseTrackBounds = baseTrackBounds,
                baseTouchBounds = baseTouchBounds,
                touchTargetOverlapped = Rect.intersects(baseTouchBounds, expandedPlayer),
                rtl = rtl,
            )
            return
        }
        restoreControlsGroup()
        restoreInfo()
    }

    private fun updateControlsWithSwitch(
        hostBounds: Rect,
        playerBounds: Rect,
        trackWidth: Int,
        switchTouchWidth: Int,
        switchTouchHeight: Int,
        baseTrackBounds: Rect,
        baseTouchBounds: Rect,
        touchTargetOverlapped: Boolean,
        rtl: Boolean,
    ) {
        val minInfoWidth = minimumCompactInfoWidth()
        val expandedPlayer = Rect(playerBounds).apply { inset(-gapPx, -gapPx) }
        val allowedSides = if (touchTargetOverlapped) {
            horizontalSafeSides
        } else {
            horizontalSafeSides.filterTo(mutableSetOf()) { side ->
                val safeRegion = when (side) {
                    PlayerOverlaySafeSide.LEFT -> Rect(
                        hostBounds.left,
                        hostBounds.top,
                        expandedPlayer.left.coerceIn(hostBounds.left, hostBounds.right),
                        hostBounds.bottom,
                    )
                    PlayerOverlaySafeSide.RIGHT -> Rect(
                        expandedPlayer.right.coerceIn(hostBounds.left, hostBounds.right),
                        hostBounds.top,
                        hostBounds.right,
                        hostBounds.bottom,
                    )
                    PlayerOverlaySafeSide.TOP, PlayerOverlaySafeSide.BOTTOM -> return@filterTo false
                }
                if (Rect.intersects(safeRegion, baseTouchBounds)) {
                    if (side == PlayerOverlaySafeSide.LEFT) {
                        safeRegion.right = min(safeRegion.right, baseTouchBounds.left - gapPx)
                    } else {
                        safeRegion.left = max(safeRegion.left, baseTouchBounds.right + gapPx)
                    }
                }
                safeRegion.width() >= minInfoWidth
            }
        }
        val selected = findPlayerOverlaySafeRegion(
            pageBounds = hostBounds,
            playerBounds = playerBounds,
            gapPx = gapPx,
            minimumWidthPx = minInfoWidth +
                if (touchTargetOverlapped) switchTouchWidth + dp(8) +
                    controlsContent.paddingStart + controlsContent.paddingEnd else 0,
            minimumHeightPx = 1,
            preferredSide = preferredControlsSide,
            allowedSides = allowedSides,
        ) ?: run {
            restoreControlsGroup()
            restoreInfo()
            return
        }

        preferredControlsSide = selected.side
        var infoBounds = Rect(selected.bounds)
        if (!touchTargetOverlapped) {
            if (selected.side == PlayerOverlaySafeSide.LEFT && baseTouchBounds.left < infoBounds.right) {
                infoBounds.right = min(infoBounds.right, baseTouchBounds.left - gapPx)
            } else if (selected.side == PlayerOverlaySafeSide.RIGHT && baseTouchBounds.right > infoBounds.left) {
                infoBounds.left = max(infoBounds.left, baseTouchBounds.right + gapPx)
            }
            if (infoBounds.width() < minInfoWidth) {
                restoreControlsGroup()
                restoreInfo()
                return
            }
        }

        var frameContentLeft = hostBounds.left
        var frameContentRight = hostBounds.right
        if (touchTargetOverlapped) {
            val frameWidth = quantizeDown(selected.width)
            val infoWidth = quantizeDown(
                frameWidth - controlsContent.paddingStart - controlsContent.paddingEnd - switchTouchWidth - dp(8),
            )
            if (infoWidth < minInfoWidth) return
            val controlsParams = (controlsContent.layoutParams as? FrameLayout.LayoutParams)
                ?.let { FrameLayout.LayoutParams(it) } ?: return
            val desiredGravity = when (selected.side) {
                PlayerOverlaySafeSide.LEFT -> Gravity.LEFT or Gravity.CENTER_VERTICAL
                PlayerOverlaySafeSide.RIGHT -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
                PlayerOverlaySafeSide.TOP, PlayerOverlaySafeSide.BOTTOM -> Gravity.CENTER
            }
            if (controlsParams.width != frameWidth ||
                controlsParams.height != controlsBaseParams.height ||
                controlsParams.gravity != desiredGravity ||
                controlsParams.leftMargin != 0 || controlsParams.rightMargin != 0 ||
                controlsParams.topMargin != 0 || controlsParams.bottomMargin != 0
            ) {
                controlsParams.width = frameWidth
                controlsParams.height = controlsBaseParams.height
                controlsParams.gravity = desiredGravity
                controlsParams.leftMargin = 0
                controlsParams.rightMargin = 0
                controlsParams.topMargin = 0
                controlsParams.bottomMargin = 0
                controlsContent.layoutParams = controlsParams
            }
            frameContentLeft = if (selected.side == PlayerOverlaySafeSide.LEFT) {
                hostBounds.left
            } else {
                hostBounds.right - frameWidth
            }
            frameContentRight = frameContentLeft + frameWidth
            infoBounds = if (rtl) {
                Rect(frameContentRight - controlsContent.paddingEnd - infoWidth, hostBounds.top,
                    frameContentRight - controlsContent.paddingEnd, hostBounds.bottom)
            } else {
                Rect(frameContentLeft + controlsContent.paddingStart, hostBounds.top,
                    frameContentLeft + controlsContent.paddingStart + infoWidth, hostBounds.bottom)
            }
        } else {
            restoreControlsFrameParams()
            val width = quantizeDown(infoBounds.width())
            if (width < minInfoWidth) return
            infoBounds.right = infoBounds.left + width
        }

        val width = infoBounds.width()
        if (width <= 0) return
        val infoParams = (clipsInfo.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) } ?: return
        if (infoParams.width != width || infoParams.weight != 0f) {
            infoParams.width = width
            infoParams.weight = 0f
            clipsInfo.layoutParams = infoParams
        }

        val rawInfoLeft = if (rtl) frameContentRight - controlsContent.paddingEnd - width
        else frameContentLeft + controlsContent.paddingStart
        val desiredInfoTranslationX = (infoBounds.left - rawInfoLeft).toFloat()
        if (clipsInfo.translationX != desiredInfoTranslationX) clipsInfo.translationX = desiredInfoTranslationX
        if (autoplayCompactLabel.visibility != View.VISIBLE) {
            autoplayCompactLabel.visibility = View.VISIBLE
        }
        if (!autoplayCompactClickListenerInstalled) {
            autoplayCompactLabel.setOnClickListener(autoplayCompactClickListener)
            autoplayCompactClickListenerInstalled = true
        }
        val maxTextWidth = (width - clipsInfo.paddingStart - clipsInfo.paddingEnd).coerceAtLeast(1)
        if (autoplayCompactLabel.maxWidth != maxTextWidth) autoplayCompactLabel.maxWidth = maxTextWidth

        val compactClipCountTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            root.resources.displayMetrics,
        )
        val compactSummaryTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            root.resources.displayMetrics,
        )
        if (clipCount.textSize != compactClipCountTextSize) {
            clipCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
        if (autoplaySummary.textSize != compactSummaryTextSize) {
            autoplaySummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        if (autoplay.text.isNotEmpty()) autoplay.text = ""
        if (autoplay.contentDescription != originalAutoplayText) {
            autoplay.contentDescription = originalAutoplayText
        }

        val currentAutoplayParams = autoplay.layoutParams as? LinearLayout.LayoutParams ?: return
        val autoplayParams = LinearLayout.LayoutParams(currentAutoplayParams)
        autoplayParams.width = switchTouchWidth
        autoplayParams.height = switchTouchHeight
        if (!sameLinearLayoutParams(currentAutoplayParams, autoplayParams, includeGravity = true)) {
            autoplay.layoutParams = autoplayParams
        }
        val desiredTrackLeft = if (touchTargetOverlapped) {
            if (rtl) frameContentLeft + controlsContent.paddingStart else frameContentRight - controlsContent.paddingEnd - trackWidth
        } else {
            if (rtl) baseTrackBounds.left else baseTrackBounds.right - trackWidth
        }
        val desiredTouchLeft = if (rtl) {
            desiredTrackLeft
        } else {
            desiredTrackLeft + trackWidth - switchTouchWidth
        }
        val rawSwitchLeft = if (rtl) frameContentLeft + controlsContent.paddingStart
        else frameContentLeft + controlsContent.paddingStart + width
        val desiredSwitchTranslationX = (desiredTouchLeft - rawSwitchLeft).toFloat()
        if (autoplay.translationX != desiredSwitchTranslationX) autoplay.translationX = desiredSwitchTranslationX
    }

    private fun moveControlsHostToSafeVerticalRegion(hostBounds: Rect, playerBounds: Rect): Boolean {
        val selected = findPlayerOverlaySafeRegion(
            pageBounds = controlsPageBounds,
            playerBounds = playerBounds,
            gapPx = gapPx,
            minimumWidthPx = hostBounds.width(),
            minimumHeightPx = naturalControlsHostHeight.coerceAtLeast(1),
            preferredSide = preferredControlsHostSide,
            allowedSides = setOf(PlayerOverlaySafeSide.TOP, PlayerOverlaySafeSide.BOTTOM),
        ) ?: return false
        val params = (controlsHost.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) } ?: return false
        val desiredTopMargin = controlsHostBaseParams.topMargin + selected.bounds.top - hostBounds.top
        if (params.topMargin != desiredTopMargin) {
            params.topMargin = desiredTopMargin
            controlsHost.layoutParams = params
        }
        preferredControlsHostSide = selected.side
        return true
    }

    private fun restoreControlsHostPosition() {
        val params = (controlsHost.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) } ?: return
        if (params.topMargin != controlsHostBaseParams.topMargin) {
            params.topMargin = controlsHostBaseParams.topMargin
            controlsHost.layoutParams = params
        }
    }

    private fun getSwitchTrackWidth(rtl: Boolean): Int {
        val compoundPadding = if (rtl) autoplay.compoundPaddingLeft else autoplay.compoundPaddingRight
        val viewPadding = if (rtl) autoplay.paddingLeft else autoplay.paddingRight
        return (compoundPadding - viewPadding).coerceIn(1, naturalSwitchWidth.coerceAtLeast(1))
    }

    private fun minimumCompactInfoWidth(): Int {
        val minimumTextSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            18f,
            root.resources.displayMetrics,
        )
        val textPaint = TextPaint(clipCount.paint).apply { textSize = minimumTextSize }
        val compactCountWidth = (
            textPaint.measureText(clipCount.text?.toString().orEmpty())
        ).roundToInt()
        val compactLabelWidth = autoplayCompactLabel.paint
            .measureText(autoplayCompactLabel.text?.toString().orEmpty())
            .roundToInt()
        return max(
            dp(48),
            compactCountWidth + dp(8) + compactLabelWidth +
                clipsInfo.paddingStart + clipsInfo.paddingEnd,
        )
    }

    private fun getBaseEmptyGeometry(page: Rect): BaseEmptyGeometry {
        val parentWidth = page.width().coerceAtLeast(1)
        val parentHeight = page.height().coerceAtLeast(1)
        if (parentWidth != cachedBaseParentWidth || parentHeight != cachedBaseParentHeight) {
            restoreEmptyContentAppearance()
            emptyStateContent.measure(
                View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(parentHeight, View.MeasureSpec.AT_MOST),
            )
            cachedBaseParentWidth = parentWidth
            cachedBaseParentHeight = parentHeight
            cachedBaseNaturalWidth = emptyStateContent.measuredWidth
            cachedBaseNaturalHeight = emptyStateContent.measuredHeight
        }
        val naturalWidth = cachedBaseNaturalWidth
        val naturalHeight = cachedBaseNaturalHeight
        val left = page.left + (parentWidth - naturalWidth) / 2
        val top = page.top + (parentHeight - naturalHeight) / 2
        return BaseEmptyGeometry(
            bounds = Rect(left, top, left + naturalWidth, top + naturalHeight),
            naturalWidth = naturalWidth,
            naturalHeight = naturalHeight,
        )
    }

    private fun positionEmptyStateInCurrentPageOrRestore() {
        if (root.isShown &&
            emptyState.visibility == View.VISIBLE &&
            contentContainer.getGlobalVisibleRect(pageBounds)
        ) {
            positionEmptyStateInPage(pageBounds, getBaseEmptyGeometry(pageBounds))
        } else {
            restoreEmptyState()
        }
    }

    private fun positionEmptyStateInPage(page: Rect, geometry: BaseEmptyGeometry) {
        restoreEmptyContentAppearance()
        val location = IntArray(2)
        emptyState.getLocationOnScreen(location)
        val left = (page.left + (page.width() - geometry.naturalWidth) / 2 - location[0]).coerceAtLeast(0)
        val top = (page.top + (page.height() - geometry.naturalHeight) / 2 - location[1]).coerceAtLeast(0)
        val params = (emptyStateContent.layoutParams as? FrameLayout.LayoutParams)
            ?.let { FrameLayout.LayoutParams(it) } ?: return
        if (params.width != geometry.naturalWidth ||
            params.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            params.gravity != (Gravity.TOP or Gravity.LEFT) ||
            params.leftMargin != left || params.topMargin != top ||
            params.rightMargin != 0 || params.bottomMargin != 0
        ) {
            params.width = geometry.naturalWidth
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.leftMargin = left
            params.topMargin = top
            params.rightMargin = 0
            params.bottomMargin = 0
            emptyStateContent.layoutParams = params
        }
    }

    private fun updateEmptyState(page: Rect, player: Rect, baseGeometry: BaseEmptyGeometry) {
        val minimumWidth = max(
            emptyStateTitle.paint.measureText(emptyStateTitle.text?.toString().orEmpty()).roundToInt() +
                emptyStateContent.paddingStart + emptyStateContent.paddingEnd,
            dp(120),
        )
        val selected = findPlayerOverlaySafeRegion(
            pageBounds = page,
            playerBounds = player,
            gapPx = gapPx,
            minimumWidthPx = minimumWidth,
            minimumHeightPx = 1,
            preferredSide = preferredEmptySide,
            minimumHeightForWidth = { width -> measureEmptyContentAtWidth(width).textHeight },
        ) ?: run {
            hideEmptyStateForUnsafePlayer()
            preferredEmptySide = null
            return
        }
        val width = selected.width.coerceAtLeast(1)
        val contentMeasure = measureEmptyContentAtWidth(width)
        val availablePadding = selected.height - contentMeasure.textHeight
        if (availablePadding < 0) {
            hideEmptyStateForUnsafePlayer()
            preferredEmptySide = null
            return
        }
        val compactPaddingTop = min(originalEmptyPaddingTop, availablePadding / 2)
        val compactPaddingBottom = min(originalEmptyPaddingBottom, availablePadding - compactPaddingTop)
        val contentHeight = contentMeasure.textHeight + compactPaddingTop + compactPaddingBottom
        val location = IntArray(2)
        emptyState.getLocationOnScreen(location)
        val left = (selected.bounds.left - location[0]).coerceAtLeast(0)
        val top = (selected.bounds.top + (selected.height - contentHeight) / 2 - location[1]).coerceAtLeast(0)
        val params = (emptyStateContent.layoutParams as? FrameLayout.LayoutParams)
            ?.let { FrameLayout.LayoutParams(it) } ?: return
        val paramsChanged = params.width != width ||
            params.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            params.gravity != (Gravity.TOP or Gravity.LEFT) ||
            params.leftMargin != left || params.topMargin != top ||
            params.rightMargin != 0 || params.bottomMargin != 0
        val paddingChanged = emptyStateContent.paddingTop != compactPaddingTop ||
            emptyStateContent.paddingBottom != compactPaddingBottom
        if (paddingChanged) {
            emptyStateContent.setPaddingRelative(
                originalEmptyPaddingStart,
                compactPaddingTop,
                originalEmptyPaddingEnd,
                compactPaddingBottom,
            )
        }
        if (paramsChanged) {
            params.width = width
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            params.gravity = Gravity.TOP or Gravity.LEFT
            params.leftMargin = left
            params.topMargin = top
            params.rightMargin = 0
            params.bottomMargin = 0
            emptyStateContent.layoutParams = params
        }
        preferredEmptySide = selected.side
    }

    private fun measureEmptyContentAtWidth(width: Int): EmptyContentMeasure {
        emptyContentMeasureCache[width]?.let { return it }
        emptyStateContent.setPaddingRelative(
            originalEmptyPaddingStart,
            originalEmptyPaddingTop,
            originalEmptyPaddingEnd,
            originalEmptyPaddingBottom,
        )
        emptyStateContent.measure(
            View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val normalHeight = emptyStateContent.measuredHeight
        val textHeight = (normalHeight - originalEmptyPaddingTop - originalEmptyPaddingBottom).coerceAtLeast(0)
        val result = EmptyContentMeasure(textHeight)
        emptyContentMeasureCache[width] = result
        while (emptyContentMeasureCache.size > 8) {
            val eldest = emptyContentMeasureCache.entries.iterator()
            eldest.next()
            eldest.remove()
        }
        return result
    }

    private fun isScaledOverlay(overlay: View): Boolean {
        var ancestor: View? = overlay
        while (ancestor != null) {
            if (ancestor.scaleX < 0.99f || ancestor.scaleY < 0.99f) return true
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun restoreControls() {
        restoreControlsGroup()
        restoreInfo()
        restoreAutoplayParams()
    }

    private fun restoreControlsGroup() {
        restoreControlsFrameParams()
        restoreAutoplayParams()
        restoreInfo()
        preferredControlsSide = null
    }

    private fun restoreControlsFrameParams() {
        val params = (controlsContent.layoutParams as? FrameLayout.LayoutParams)
            ?.let { FrameLayout.LayoutParams(it) } ?: return
        if (!sameFrameLayoutParams(params, controlsBaseParams)) {
            controlsContent.layoutParams = FrameLayout.LayoutParams(controlsBaseParams)
        }
        if (controlsContent.orientation != originalControlsOrientation) {
            controlsContent.orientation = originalControlsOrientation
        }
        if (controlsContent.gravity != originalControlsGravity) controlsContent.gravity = originalControlsGravity
    }

    private fun restoreInfo() {
        val params = (clipsInfo.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) } ?: return
        if (!sameLinearLayoutParams(params, infoBaseParams, includeGravity = true)) {
            clipsInfo.layoutParams = LinearLayout.LayoutParams(infoBaseParams)
        }
        if (clipsInfo.translationX != 0f) clipsInfo.translationX = 0f
        if (clipsInfo.translationY != 0f) clipsInfo.translationY = 0f
    }

    private fun restoreAutoplayParams() {
        if (autoplayCompactClickListenerInstalled) {
            autoplayCompactLabel.setOnClickListener(null)
            autoplayCompactClickListenerInstalled = false
        }
        if (autoplayCompactLabel.visibility != View.GONE) autoplayCompactLabel.visibility = View.GONE
        if (autoplayCompactLabel.maxWidth != originalCompactLabelMaxWidth) {
            autoplayCompactLabel.maxWidth = originalCompactLabelMaxWidth
        }
        if (autoplaySummary.textSize != originalSummaryTextSize) {
            autoplaySummary.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalSummaryTextSize)
        }
        if (clipCount.textSize != originalClipCountTextSize) {
            clipCount.setTextSize(TypedValue.COMPLEX_UNIT_PX, originalClipCountTextSize)
        }
        if (autoplay.text?.toString() != originalAutoplayText) autoplay.text = originalAutoplayText
        if (autoplay.contentDescription != originalAutoplayContentDescription) {
            autoplay.contentDescription = originalAutoplayContentDescription
        }
        val params = (autoplay.layoutParams as? LinearLayout.LayoutParams)
            ?.let { LinearLayout.LayoutParams(it) } ?: return
        if (!sameLinearLayoutParams(params, autoplayBaseParams, includeGravity = true)) {
            autoplay.layoutParams = LinearLayout.LayoutParams(autoplayBaseParams)
        }
        if (autoplay.maxWidth != originalAutoplayMaxWidth) autoplay.maxWidth = originalAutoplayMaxWidth
        if (autoplay.translationX != 0f) autoplay.translationX = 0f
        if (autoplay.translationY != 0f) autoplay.translationY = 0f
    }

    private fun restoreEmptyState() {
        restoreEmptyContentAppearance()
        restoreEmptyStateLayoutParams()
        if (emptyStateContent.translationX != 0f) emptyStateContent.translationX = 0f
        if (emptyStateContent.translationY != 0f) emptyStateContent.translationY = 0f
    }

    private fun restoreEmptyStateLayoutParams() {
        val params = (emptyStateContent.layoutParams as? FrameLayout.LayoutParams)
            ?.let { FrameLayout.LayoutParams(it) } ?: return
        if (!sameFrameLayoutParams(params, emptyBaseParams)) {
            emptyStateContent.layoutParams = FrameLayout.LayoutParams(emptyBaseParams)
        }
    }

    private fun restoreEmptyContentAppearance() {
        restoreEmptyContentVisibility()
        restoreEmptyContentPadding()
    }

    private fun restoreEmptyContentPadding() {
        if (emptyStateContent.paddingStart != originalEmptyPaddingStart ||
            emptyStateContent.paddingTop != originalEmptyPaddingTop ||
            emptyStateContent.paddingEnd != originalEmptyPaddingEnd ||
            emptyStateContent.paddingBottom != originalEmptyPaddingBottom
        ) {
            emptyStateContent.setPaddingRelative(
                originalEmptyPaddingStart,
                originalEmptyPaddingTop,
                originalEmptyPaddingEnd,
                originalEmptyPaddingBottom,
            )
        }
    }

    private fun restoreEmptyContentVisibility() {
        if (emptyStateContent.visibility != originalEmptyContentVisibility) {
            emptyStateContent.visibility = originalEmptyContentVisibility
        }
    }

    private fun hideEmptyStateForUnsafePlayer() {
        restoreEmptyContentPadding()
        restoreEmptyStateLayoutParams()
        if (emptyStateContent.translationX != 0f) emptyStateContent.translationX = 0f
        if (emptyStateContent.translationY != 0f) emptyStateContent.translationY = 0f
        if (emptyStateContent.visibility != View.GONE) emptyStateContent.visibility = View.GONE
    }

    private fun quantizeDown(width: Int): Int = (floor(width.toDouble() / widthStepPx) * widthStepPx).toInt()
        .coerceAtLeast(1)

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private fun sameFrameLayoutParams(first: FrameLayout.LayoutParams, second: FrameLayout.LayoutParams): Boolean =
        first.width == second.width && first.height == second.height && first.gravity == second.gravity &&
            first.leftMargin == second.leftMargin && first.topMargin == second.topMargin &&
            first.rightMargin == second.rightMargin && first.bottomMargin == second.bottomMargin

    private fun sameLinearLayoutParams(
        first: LinearLayout.LayoutParams,
        second: LinearLayout.LayoutParams,
        includeGravity: Boolean,
    ): Boolean = first.width == second.width && first.height == second.height && first.weight == second.weight &&
        first.leftMargin == second.leftMargin && first.topMargin == second.topMargin &&
        first.rightMargin == second.rightMargin && first.bottomMargin == second.bottomMargin &&
        (!includeGravity || first.gravity == second.gravity)

    override fun close() {
        if (isClosed) return
        isClosed = true
        root.removeOnAttachStateChangeListener(attachListener)
        detachFromTreeObserver()
        restoreControlsHostPosition()
        restoreControls()
        restoreEmptyState()
    }
}
