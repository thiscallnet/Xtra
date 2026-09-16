package com.github.andreyasadchy.xtra.ui.player.hud

object HudDefaultLayout {
    const val NORMAL_EDGE_PADDING = 12f
    const val TV_EDGE_PADDING = 48f
    const val SPACING = 4f
    const val COMPACT_HORIZONTAL_PADDING = 4f
    const val COMPACT_VERTICAL_PADDING = 4f
    const val COMPACT_BOTTOM_VISUAL_CLEARANCE = 2f
    const val TRANSPORT_GAP = 20f
    // The 48dp touch targets can overlap, so the visible transport needs no
    // extra gap. This leaves the fixed middle-left lock visible even when
    // display cutouts reduce the safe width to the narrow-phone floor.
    const val COMPACT_TRANSPORT_GAP = 0f
    private const val RESPONSIVE_COMPACT_WIDTH = 360f

    private val COMPACT_PHONE_V3_DEFAULT_ELEMENTS = setOf(
        HudElementId.STREAM_INFO,
        HudElementId.TIMELINE,
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
        HudElementId.TIMELINE,
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
        HudElementId.TIMELINE,
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
            HudElementId.TIMELINE -> .5f to 1f
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
        val placements = mutableMapOf<HudElementId, HudPlacement>()
        val globalScale = HudScale.clampGlobal(profile.globalScale)

        fun scaledSize(id: HudElementId): HudSize {
            val spec = HudElementRegistry.get(id)
            val size = if (id == HudElementId.STREAM_INFO || id == HudElementId.TIMELINE) {
                measuredVisualSizes[id] ?: spec.visualSize(compact).let { HudSize(it.width * density, it.height * density) }
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
            val visual = scaledSize(id)
            val minimum = minimumHitSizes[id]
                ?: HudElementRegistry.get(id).minimumHitSize.let { HudSize(it.width * density, it.height * density) }
            return HudSize(maxOf(visual.width, minimum.width), maxOf(visual.height, minimum.height))
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

        val safeWidth = safeRect.width / density.coerceAtLeast(0.001f)
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
            // Pack the visible controls by their presentation rectangles. The
            // frame still owns the larger minimum hit target, so touch
            // affordance is preserved without adding visible dead space.
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
        if (HudElementId.PLAY_PAUSE in availability) add(HudElementId.PLAY_PAUSE, playCenterX, playCenterY)
        if (HudElementId.SEEK_BACK in availability) {
            add(HudElementId.SEEK_BACK, playCenterX - playHit.width / 2f - transportGap - rewindHit.width / 2f, playCenterY)
        }
        if (HudElementId.SEEK_FORWARD in availability) {
            add(HudElementId.SEEK_FORWARD, playCenterX + playHit.width / 2f + transportGap + forwardHit.width / 2f, playCenterY)
        }

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
        val timelineBand = if (HudElementId.TIMELINE in availability) {
            fittedSize(HudElementId.TIMELINE).height
                .coerceAtLeast(24f * density)
        } else {
            0f
        }
        val timelineReservation = if (HudElementId.TIMELINE in availability) {
            maxOf(timelineBand, hitSize(HudElementId.TIMELINE).height)
        } else {
            0f
        }
        val timelineAvailable = timelineReservation > 0f
        // Playback chrome owns the bottom edge. Custom placements are merged
        // around it by HudLayoutEngine, so a legacy timeline coordinate can no
        // longer lift the progress bar into the action row.
        val timelineBottom = if (timelineAvailable) safeRect.bottom else inner.bottom
        fun placeBottomRow(ids: List<HudElementId>, start: Boolean) {
            var x = if (start) {
                if (compact) safeRect.left else inner.left
            } else {
                if (compact) safeRect.right else inner.right
            }
            ids.forEach { id ->
                val hit = hitSize(id)
                val center = if (start) x + hit.width / 2f else x - hit.width / 2f
                val centerY = if (compact) {
                    // Keep the visible icon on the last few pixels above the
                    // fixed timeline. The hit rectangle may overlap the time
                    // bar; PlayerHudLayout routes an edge tap to the nearest
                    // visible action, while the middle of the bar remains
                    // scrubable.
                    val visual = scaledSize(id)
                    safeRect.bottom - COMPACT_BOTTOM_VISUAL_CLEARANCE * density - visual.height / 2f
                } else if (timelineAvailable) {
                    safeRect.bottom - timelineReservation - hit.height / 2f - gap
                } else {
                    inner.bottom - hit.height / 2f
                }
                val candidate = HudRect(
                    center - hit.width / 2f,
                    centerY - hit.height / 2f,
                    center + hit.width / 2f,
                    centerY + hit.height / 2f,
                )
                if (!compact || transportHitRects.none { it.overlaps(candidate) }) add(id, center, centerY)
                x += if (start) hit.width + gap else -(hit.width + gap)
            }
        }
        placeBottomRow(bottomStart, start = !rtl)
        placeBottomRow(bottomEnd, start = rtl)

        if (fixedPlayerChromeCompact && HudElementId.INTERACTION_LOCK in availability) {
            val lockHit = hitSize(HudElementId.INTERACTION_LOCK)
            val lockCenterX = if (rtl) safeRect.right - lockHit.width / 2f else safeRect.left + lockHit.width / 2f
            add(HudElementId.INTERACTION_LOCK, lockCenterX, safeRect.centerY)
        }

        if (HudElementId.TIMELINE in availability && enabledByDefault(
                HudElementId.TIMELINE,
                orientation,
                safeRect.width / density.coerceAtLeast(0.001f),
                compact,
                defaultPolicyVersion = profile.defaultPolicyVersion,
            )) {
            add(HudElementId.TIMELINE, safeRect.centerX, timelineBottom)
        }

        return placements
    }
}
