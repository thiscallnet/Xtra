package com.github.andreyasadchy.xtra.ui.player.hud

import kotlin.math.roundToInt

object HudDefaultLayout {
    const val NORMAL_EDGE_PADDING = 12f
    const val TV_EDGE_PADDING = 48f
    const val SPACING = 4f
    const val COMPACT_HORIZONTAL_PADDING = 4f
    const val COMPACT_VERTICAL_PADDING = 4f
    const val COMPACT_BOTTOM_VISUAL_CLEARANCE = 8f
    const val DEFAULT_BOTTOM_CONTROL_SIZE = 48f
    const val TIMELINE_TOUCH_TARGET_HEIGHT = 48f
    const val BOTTOM_CONTROL_TIMELINE_CLEARANCE = 8f
    const val TRANSPORT_GAP = 20f
    // The compact transport needs no artificial gap. Keeping visible geometry
    // tight leaves room for the fixed middle-left lock on narrow phones.
    const val COMPACT_TRANSPORT_GAP = 0f
    private const val RESPONSIVE_COMPACT_WIDTH = 360f

    private val COMPACT_PHONE_V3_DEFAULT_ELEMENTS = setOf(
        HudElementId.STREAM_INFO,
        HudElementId.TIME_STATUS,
        HudElementId.SEEK_BACK,
        HudElementId.PLAY_PAUSE,
        HudElementId.SEEK_FORWARD,
        HudElementId.QUALITY,
        HudElementId.CHAT,
        HudElementId.FULLSCREEN,
        HudElementId.MORE,
    )
    private val LEGACY_DEFAULT_ELEMENTS = setOf(
        HudElementId.STREAM_INFO,
        HudElementId.TIME_STATUS,
        HudElementId.SEEK_BACK,
        HudElementId.PLAY_PAUSE,
        HudElementId.SEEK_FORWARD,
        HudElementId.FOLLOW,
        HudElementId.QUALITY,
        HudElementId.ASPECT_RATIO,
        HudElementId.VOLUME,
        HudElementId.CLIP,
        HudElementId.CAPTIONS,
        HudElementId.CHAT,
        HudElementId.FULLSCREEN,
        HudElementId.MORE,
    )
    private val FIXED_PLAYER_CHROME_COMPACT_ELEMENTS = setOf(
        HudElementId.STREAM_INFO,
        HudElementId.TIME_STATUS,
        HudElementId.SEEK_BACK,
        HudElementId.PLAY_PAUSE,
        HudElementId.SEEK_FORWARD,
        HudElementId.CAPTIONS,
        HudElementId.QUALITY,
        HudElementId.CHAT,
        HudElementId.FULLSCREEN,
        HudElementId.MORE,
        HudElementId.INTERACTION_LOCK,
    )
    private val RESPONSIVE_COMPLETE_DEFAULT_ELEMENTS = LEGACY_DEFAULT_ELEMENTS +
        HudElementId.INTERACTION_LOCK

    fun defaultEnabledElements(defaultPolicyVersion: Int = HudDefaultPolicy.CURRENT): Set<HudElementId> =
        when {
            defaultPolicyVersion == HudDefaultPolicy.COMPACT_PHONE_V3 -> COMPACT_PHONE_V3_DEFAULT_ELEMENTS
            defaultPolicyVersion >= HudDefaultPolicy.RESPONSIVE_COMPLETE_V4 -> RESPONSIVE_COMPLETE_DEFAULT_ELEMENTS
            else -> LEGACY_DEFAULT_ELEMENTS
        }

    private fun usesCompactPhonePolicy(defaultPolicyVersion: Int): Boolean =
        defaultPolicyVersion == HudDefaultPolicy.COMPACT_PHONE_V3

    private fun usesResponsiveCompletePolicy(defaultPolicyVersion: Int): Boolean =
        defaultPolicyVersion >= HudDefaultPolicy.RESPONSIVE_COMPLETE_V4

    private fun usesFixedPlayerChromePolicy(defaultPolicyVersion: Int): Boolean =
        defaultPolicyVersion >= HudDefaultPolicy.FIXED_PLAYER_CHROME_V5

    private fun isResponsiveCompact(
        defaultPolicyVersion: Int,
        safeWidth: Float,
        compact: Boolean,
    ): Boolean = usesResponsiveCompletePolicy(defaultPolicyVersion) &&
        (compact || safeWidth < RESPONSIVE_COMPACT_WIDTH)

    fun defaultBottomRowIds(
        safeRect: HudRect,
        profile: HudProfile,
        availability: Set<HudElementId>,
        density: Float = 1f,
    ): Set<HudElementId> = defaultBottomRowElements(safeRect, profile, availability, density)
        .let { (start, end) -> (start + end).toSet() }

    private fun defaultBottomRowElements(
        safeRect: HudRect,
        profile: HudProfile,
        availability: Set<HudElementId>,
        density: Float,
    ): Pair<List<HudElementId>, List<HudElementId>> {
        val compact = safeRect.height < 260f * density
        val safeWidth = safeRect.width / density.coerceAtLeast(0.001f)
        val compactPhonePolicy = usesCompactPhonePolicy(profile.defaultPolicyVersion)
        val responsiveCompact = isResponsiveCompact(profile.defaultPolicyVersion, safeWidth, compact)
        val compactLayout = compactPhonePolicy || responsiveCompact
        val fixedPlayerChromeCompact = usesFixedPlayerChromePolicy(profile.defaultPolicyVersion) && responsiveCompact
        val bottomStartIds = if (compactLayout) {
            listOf(HudElementId.CHAT)
        } else {
            listOf(HudElementId.VOLUME, HudElementId.CLIP)
        }
        val bottomEndIds = if (fixedPlayerChromeCompact) {
            listOf(HudElementId.FULLSCREEN)
        } else if (compactLayout) {
            emptyList()
        } else {
            listOf(
                HudElementId.MORE,
                HudElementId.CAPTIONS,
                HudElementId.CHAT,
                HudElementId.FULLSCREEN,
            )
        }
        val bottomStart = bottomStartIds.filter { it in availability }
        val bottomEnd = bottomEndIds.filter {
            it in availability &&
                (!compact || compactPhonePolicy || fixedPlayerChromeCompact || it == HudElementId.MORE)
        }
        return bottomStart to bottomEnd
    }

    fun enabledByDefault(
        id: HudElementId,
        orientation: HudOrientation,
        safeWidth: Float,
        compact: Boolean = false,
        defaultPolicyVersion: Int = HudDefaultPolicy.CURRENT,
    ): Boolean {
        // Both orientations use the same visual language. Width and height
        // only decide which controls can fit, never a separate portrait model.
        if (usesCompactPhonePolicy(defaultPolicyVersion)) {
            return id in COMPACT_PHONE_V3_DEFAULT_ELEMENTS
        }

        if (isResponsiveCompact(defaultPolicyVersion, safeWidth, compact)) {
            return if (usesFixedPlayerChromePolicy(defaultPolicyVersion)) {
                id in FIXED_PLAYER_CHROME_COMPACT_ELEMENTS
            } else {
                id in COMPACT_PHONE_V3_DEFAULT_ELEMENTS
            }
        }

        val defaultElements = if (usesResponsiveCompletePolicy(defaultPolicyVersion)) {
            RESPONSIVE_COMPLETE_DEFAULT_ELEMENTS
        } else {
            LEGACY_DEFAULT_ELEMENTS
        }
        return id in defaultElements && when {
            defaultPolicyVersion < HudDefaultPolicy.FIXED_PLAYER_CHROME_V5 && compact && id in setOf(
                HudElementId.CLIP,
                HudElementId.CAPTIONS,
                HudElementId.CHAT,
                HudElementId.FULLSCREEN,
            ) -> false
            id == HudElementId.FOLLOW -> safeWidth >= 340f
            id == HudElementId.ASPECT_RATIO -> safeWidth >= 600f
            id == HudElementId.INTERACTION_LOCK -> safeWidth >= 600f
            else -> true
        }
    }

    fun topEndElements(
        orientation: HudOrientation,
        safeWidth: Float,
        defaultPolicyVersion: Int = HudDefaultPolicy.CURRENT,
        compact: Boolean = false,
    ): List<HudElementId> = buildList {
        if (usesCompactPhonePolicy(defaultPolicyVersion) ||
            isResponsiveCompact(defaultPolicyVersion, safeWidth, compact)
        ) {
            if (usesFixedPlayerChromePolicy(defaultPolicyVersion)) {
                // This list is placed from the right edge toward the left.
                // Keep overflow at the outside edge, with captions and
                // quality beside it, matching familiar player chrome.
                add(HudElementId.MORE)
                if (HudElementId.CAPTIONS in defaultEnabledElements(defaultPolicyVersion)) {
                    add(HudElementId.CAPTIONS)
                }
                add(HudElementId.QUALITY)
            } else {
                add(HudElementId.QUALITY)
                add(HudElementId.FULLSCREEN)
                add(HudElementId.MORE)
            }
        } else {
            if (safeWidth >= 340f) add(HudElementId.FOLLOW)
            add(HudElementId.QUALITY)
            if (safeWidth >= 600f) add(HudElementId.ASPECT_RATIO)
            if (usesResponsiveCompletePolicy(defaultPolicyVersion) && safeWidth >= 600f) {
                add(HudElementId.INTERACTION_LOCK)
            }
        }
    }

    fun semanticFallback(
        id: HudElementId,
        orientation: HudOrientation,
        defaultPolicyVersion: Int = HudDefaultPolicy.CURRENT,
    ): HudPlacement {
        val enabled = enabledByDefault(
            id,
            orientation,
            Float.POSITIVE_INFINITY,
            defaultPolicyVersion = defaultPolicyVersion,
        )
        val point = when (id) {
            HudElementId.STREAM_INFO -> 0f to 0f
            HudElementId.TIME_STATUS -> 0f to 1f
            HudElementId.SEEK_BACK -> .38f to .5f
            HudElementId.PLAY_PAUSE -> .5f to .5f
            HudElementId.SEEK_FORWARD -> .62f to .5f
            HudElementId.FOLLOW -> .72f to 0f
            HudElementId.QUALITY -> .84f to 0f
            HudElementId.ASPECT_RATIO -> .95f to 0f
            HudElementId.VOLUME -> .08f to .9f
            HudElementId.CLIP -> .24f to .9f
            HudElementId.MORE -> .92f to .9f
            HudElementId.CAPTIONS -> .76f to .9f
            HudElementId.CHAT -> .60f to .9f
            HudElementId.FULLSCREEN -> .44f to .9f
            else -> .5f to .5f
        }
        return HudPlacement(enabled, point.first, point.second, 1f)
    }

    fun resolve(
        safeRect: HudRect,
        orientation: HudOrientation,
        profile: HudProfile,
        measuredVisualSizes: Map<HudElementId, HudSize>,
        availability: Set<HudElementId>,
        minimumHitSizes: Map<HudElementId, HudSize> = emptyMap(),
        density: Float = 1f,
        rtl: Boolean = false,
        television: Boolean = false,
        televisionEdgePadding: Float = TV_EDGE_PADDING * density,
        liveTimePosition: HudTimelineTimePosition = HudTimelineTimePosition.LEFT,
    ): Map<HudElementId, HudPlacement> {
        val compact = safeRect.height < 260f * density
        val edge = if (television) {
            televisionEdgePadding
        } else if (compact) {
            COMPACT_HORIZONTAL_PADDING * density
        } else {
            NORMAL_EDGE_PADDING * density
        }
        val edgePadding = edge.coerceAtMost(safeRect.width / 2f)
        val inner = safeRect.inset(
            edgePadding,
            if (compact) COMPACT_VERTICAL_PADDING * density else edgePadding,
        )
        val gap = SPACING * density
        val safeWidth = safeRect.width / density.coerceAtLeast(0.001f)
        val responsiveCompact = isResponsiveCompact(profile.defaultPolicyVersion, safeWidth, compact)
        val fixedPlayerChromeCompact = usesFixedPlayerChromePolicy(profile.defaultPolicyVersion) && responsiveCompact
        val (bottomStart, bottomEnd) = defaultBottomRowElements(safeRect, profile, availability, density)
        val bottomRowIds = (bottomStart + bottomEnd).toSet()
        val placements = mutableMapOf<HudElementId, HudPlacement>()
        val globalScale = HudScale.clampGlobal(profile.globalScale)

        fun scaledSize(id: HudElementId): HudSize {
            val spec = HudElementRegistry.get(id)
            val size = if (id == HudElementId.STREAM_INFO || id == HudElementId.TIME_STATUS) {
                measuredVisualSizes[id] ?: spec.visualSize(compact).let { HudSize(it.width * density, it.height * density) }
            } else if (id in bottomRowIds && id !in profile.placements) {
                HudSize(DEFAULT_BOTTOM_CONTROL_SIZE * density, DEFAULT_BOTTOM_CONTROL_SIZE * density)
            } else {
                spec.visualSize(compact).let { HudSize(it.width * density, it.height * density) }
            }
            val scale = HudScale.effective(
                globalScale,
                if (spec.isMovable) spec.clampScale(profile.placements[id]?.scale ?: 1f) else 1f,
            )
            return HudSize(size.width * scale, size.height * scale)
        }

        fun fittedSize(id: HudElementId): HudSize {
            val size = scaledSize(id)
            val fit = listOf(
                if (size.width > 0f) safeRect.width / size.width else 1f,
                if (size.height > 0f) safeRect.height / size.height else 1f,
                1f,
            ).minOrNull()?.coerceAtMost(1f) ?: 1f
            return HudSize(size.width * fit, size.height * fit)
        }

        fun hitSize(id: HudElementId): HudSize {
            // A control's interaction box must describe the control the user
            // can see. The old independent minimum made compact controls own
            // invisible space and caused adjacent buttons to feel broken.
            return scaledSize(id)
        }

        fun add(id: HudElementId, x: Float, y: Float) {
            if (id !in availability || !enabledByDefault(
                    id,
                    orientation,
                    safeRect.width / density.coerceAtLeast(0.001f),
                    compact,
                    defaultPolicyVersion = profile.defaultPolicyVersion,
                )) return
            val normalizedX = (x - safeRect.left) / safeRect.width.coerceAtLeast(1f)
            val normalizedY = (y - safeRect.top) / safeRect.height.coerceAtLeast(1f)
            placements[id] = HudPlacement(
                true,
                normalizedX.coerceIn(0f, 1f),
                normalizedY.coerceIn(0f, 1f),
                profile.placements[id]?.takeIf { HudElementRegistry.get(id).isMovable }?.scale ?: 1f,
            )
        }

        val topEnd = topEndElements(
            orientation,
            safeWidth,
            profile.defaultPolicyVersion,
            compact,
        )
            .filter {
                it in availability && enabledByDefault(
                    it,
                    orientation,
                    safeWidth,
                    compact,
                    profile.defaultPolicyVersion,
                )
            }
        var topEndX = if (rtl) inner.left else inner.right
        topEnd.forEach { id ->
            val visual = scaledSize(id)
            // Pack by the same rectangles that are drawn and touched. There is
            // no separate invisible minimum target to steal space from a
            // neighboring control.
            val center = if (rtl) topEndX + visual.width / 2f else topEndX - visual.width / 2f
            add(id, center, inner.top + visual.height / 2f)
            topEndX += if (rtl) visual.width + gap else -(visual.width + gap)
        }

        val metadata = HudElementId.STREAM_INFO
        if (metadata in availability && enabledByDefault(
                metadata,
                orientation,
                safeRect.width / density.coerceAtLeast(0.001f),
                compact,
                defaultPolicyVersion = profile.defaultPolicyVersion,
            )) {
            add(metadata, if (rtl) inner.right else inner.left, inner.top)
        }

        val playHit = hitSize(HudElementId.PLAY_PAUSE)
        val rewindHit = hitSize(HudElementId.SEEK_BACK)
        val forwardHit = hitSize(HudElementId.SEEK_FORWARD)
        val transportGap = if (compact) COMPACT_TRANSPORT_GAP * density else TRANSPORT_GAP * density
        val playCenterX = safeRect.centerX
        val playCenterY = safeRect.centerY
        val transportHitRects = buildList {
            if (HudElementId.SEEK_BACK in availability) add(
                HudRect(
                    playCenterX - playHit.width / 2f - transportGap - rewindHit.width,
                    playCenterY - rewindHit.height / 2f,
                    playCenterX - playHit.width / 2f - transportGap,
                    playCenterY + rewindHit.height / 2f,
                ),
            )
            if (HudElementId.PLAY_PAUSE in availability) add(
                HudRect(
                    playCenterX - playHit.width / 2f,
                    playCenterY - playHit.height / 2f,
                    playCenterX + playHit.width / 2f,
                    playCenterY + playHit.height / 2f,
                ),
            )
            if (HudElementId.SEEK_FORWARD in availability) add(
                HudRect(
                    playCenterX + playHit.width / 2f + transportGap,
                    playCenterY - forwardHit.height / 2f,
                    playCenterX + playHit.width / 2f + transportGap + forwardHit.width,
                    playCenterY + forwardHit.height / 2f,
                ),
            )
        }
        val interactionLockRect = if (
            fixedPlayerChromeCompact && HudElementId.INTERACTION_LOCK in availability
        ) {
            val lockHit = hitSize(HudElementId.INTERACTION_LOCK)
            val lockCenterX = if (rtl) {
                safeRect.right - lockHit.width / 2f
            } else {
                safeRect.left + lockHit.width / 2f
            }
            HudRect(
                lockCenterX - lockHit.width / 2f,
                safeRect.centerY - lockHit.height / 2f,
                lockCenterX + lockHit.width / 2f,
                safeRect.centerY + lockHit.height / 2f,
            )
        } else {
            null
        }
        val protectedControlRects = transportHitRects + listOfNotNull(interactionLockRect)
        if (HudElementId.PLAY_PAUSE in availability) add(HudElementId.PLAY_PAUSE, playCenterX, playCenterY)
        if (HudElementId.SEEK_BACK in availability) {
            add(HudElementId.SEEK_BACK, playCenterX - playHit.width / 2f - transportGap - rewindHit.width / 2f, playCenterY)
        }
        if (HudElementId.SEEK_FORWARD in availability) {
            add(HudElementId.SEEK_FORWARD, playCenterX + playHit.width / 2f + transportGap + forwardHit.width / 2f, playCenterY)
        }

        fun placeBottomRow(ids: List<HudElementId>, start: Boolean) {
            var x = if (start) {
                if (compact) safeRect.left else inner.left
            } else {
                if (compact) safeRect.right else inner.right
            }
            ids.forEach { id ->
                val hit = hitSize(id)
                val center = if (start) x + hit.width / 2f else x - hit.width / 2f
                // Keep bottom-row hit bounds above the fixed timeline scrub
                // lane so the two controls cannot compete for the same tap.
                val centerY = safeRect.bottom -
                    (TIMELINE_TOUCH_TARGET_HEIGHT + BOTTOM_CONTROL_TIMELINE_CLEARANCE) * density -
                    hit.height / 2f
                val candidate = HudRect(
                    center - hit.width / 2f,
                    centerY - hit.height / 2f,
                    center + hit.width / 2f,
                    centerY + hit.height / 2f,
                )
                if (!compact || protectedControlRects.none { it.overlaps(candidate) }) add(id, center, centerY)
                x += if (start) hit.width + gap else -(hit.width + gap)
            }
        }
        placeBottomRow(bottomStart, start = !rtl)
        placeBottomRow(bottomEnd, start = rtl)

        if (HudElementId.TIME_STATUS in availability && enabledByDefault(
                HudElementId.TIME_STATUS,
                orientation,
                safeRect.width / density.coerceAtLeast(0.001f),
                compact,
                defaultPolicyVersion = profile.defaultPolicyVersion,
            )) {
            val timeSize = scaledSize(HudElementId.TIME_STATUS)
            val startReservation = bottomStart.sumOf { id ->
                scaledSize(id).width.roundToInt() + gap.roundToInt()
            }.toFloat()
            val endReservation = bottomEnd.sumOf { id ->
                scaledSize(id).width.roundToInt() + gap.roundToInt()
            }.toFloat()
            val startEdge = if (compact) safeRect.left else inner.left
            val endEdge = if (compact) safeRect.right else inner.right
            val x = if (liveTimePosition == HudTimelineTimePosition.RIGHT) {
                // TIME_STATUS uses a TOP_START pivot. Keep it beside the
                // layout-end controls when the legacy preference requests the
                // right side; in RTL that side is physically left.
                if (rtl) {
                    startEdge + endReservation + timeSize.width
                } else {
                    endEdge - endReservation - timeSize.width
                }
            } else if (rtl) {
                endEdge - startReservation
            } else {
                startEdge + startReservation
            }
            val bottomClearance = if (compact) {
                COMPACT_BOTTOM_VISUAL_CLEARANCE * density
            } else {
                NORMAL_EDGE_PADDING * density
            }
            val y = safeRect.bottom - bottomClearance - timeSize.height
            add(HudElementId.TIME_STATUS, x, y)
        }

        if (fixedPlayerChromeCompact && HudElementId.INTERACTION_LOCK in availability) {
            val lockHit = hitSize(HudElementId.INTERACTION_LOCK)
            val lockCenterX = if (rtl) safeRect.right - lockHit.width / 2f else safeRect.left + lockHit.width / 2f
            add(HudElementId.INTERACTION_LOCK, lockCenterX, safeRect.centerY)
        }

        return placements
    }
}
