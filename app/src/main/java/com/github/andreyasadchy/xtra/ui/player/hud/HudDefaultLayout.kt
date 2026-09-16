package com.github.andreyasadchy.xtra.ui.player.hud

object HudDefaultLayout {
    const val NORMAL_EDGE_PADDING = 12f
    const val TV_EDGE_PADDING = 48f
    const val SPACING = 8f
    const val COMPACT_VERTICAL_PADDING = 8f
    const val TRANSPORT_GAP = 20f
    const val COMPACT_TRANSPORT_GAP = 12f

    private val COMPACT_PHONE_DEFAULT_ELEMENTS = setOf(
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

    fun defaultEnabledElements(defaultPolicyVersion: Int = HudDefaultPolicy.CURRENT): Set<HudElementId> =
        if (defaultPolicyVersion >= HudDefaultPolicy.COMPACT_PHONE_V3) {
            COMPACT_PHONE_DEFAULT_ELEMENTS
        } else LEGACY_DEFAULT_ELEMENTS

    private fun usesCompactPhonePolicy(defaultPolicyVersion: Int): Boolean =
        defaultPolicyVersion >= HudDefaultPolicy.COMPACT_PHONE_V3

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
            return id in COMPACT_PHONE_DEFAULT_ELEMENTS
        }

        return id in defaultEnabledElements(HudDefaultPolicy.LEGACY_V2) && when {
            compact && id in setOf(
                HudElementId.CLIP,
                HudElementId.CAPTIONS,
                HudElementId.CHAT,
                HudElementId.FULLSCREEN,
            ) -> false
            id == HudElementId.FOLLOW -> safeWidth >= 340f
            id == HudElementId.ASPECT_RATIO -> safeWidth >= 600f
            else -> true
        }
    }

    fun topEndElements(
        orientation: HudOrientation,
        safeWidth: Float,
        defaultPolicyVersion: Int = HudDefaultPolicy.CURRENT,
    ): List<HudElementId> = buildList {
        if (usesCompactPhonePolicy(defaultPolicyVersion)) {
            add(HudElementId.QUALITY)
            add(HudElementId.FULLSCREEN)
        } else {
            if (safeWidth >= 340f) add(HudElementId.FOLLOW)
            add(HudElementId.QUALITY)
            if (safeWidth >= 600f) add(HudElementId.ASPECT_RATIO)
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
        val edge = if (television) televisionEdgePadding else NORMAL_EDGE_PADDING * density
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
            val scale = HudScale.effective(globalScale, spec.clampScale(profile.placements[id]?.scale ?: 1f))
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
            placements[id] = HudPlacement(true, normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f), profile.placements[id]?.scale ?: 1f)
        }

        val safeWidth = safeRect.width / density.coerceAtLeast(0.001f)
        val topEnd = topEndElements(orientation, safeWidth, profile.defaultPolicyVersion)
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
            val hit = hitSize(id)
            val center = if (rtl) topEndX + hit.width / 2f else topEndX - hit.width / 2f
            val visual = scaledSize(id)
            add(id, center, inner.top + visual.height / 2f)
            topEndX += if (rtl) hit.width + gap else -(hit.width + gap)
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
        val bottomStartIds = if (compactPhonePolicy) {
            listOf(HudElementId.CHAT)
        } else {
            listOf(HudElementId.VOLUME, HudElementId.CLIP)
        }
        val bottomEndIds = if (compactPhonePolicy) {
            listOf(HudElementId.MORE)
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
            it in availability && (compactPhonePolicy || !compact || it == HudElementId.MORE)
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
        val bottomActionIds = (bottomStart + bottomEnd).distinct()
        val bottomActionBand = bottomActionIds.maxOfOrNull { hitSize(it).height } ?: 0f
        val actionBottom = if (compact) safeRect.bottom else inner.bottom
        val timelineBottomCandidate = actionBottom - bottomActionBand - gap
        val candidateTimelineBand = HudRect(
            safeRect.left,
            timelineBottomCandidate - timelineReservation,
            safeRect.right,
            timelineBottomCandidate,
        )
        val timelineAboveActions = compactPhonePolicy &&
            timelineReservation > 0f &&
            bottomActionIds.isNotEmpty() &&
            transportHitRects.none { it.overlaps(candidateTimelineBand) }
        val timelineBottom = if (timelineAboveActions) {
            timelineBottomCandidate
        } else if (compact) {
            safeRect.bottom
        } else {
            inner.bottom
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
                val centerY = if (timelineAboveActions) {
                    actionBottom - hit.height / 2f
                } else if (compact) {
                    safeRect.bottom - timelineReservation - hit.height / 2f
                } else {
                    inner.bottom - timelineReservation - hit.height / 2f - gap
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
