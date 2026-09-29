package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality

internal data class SourceSwitchQualityIdentity(
    val name: String,
    val codecs: String?,
    val bitrate: Int?,
) {
    fun resolve(
        qualities: List<VideoQuality>?,
        fallback: (String) -> VideoQuality?,
    ): VideoQuality? = qualities?.firstOrNull { quality ->
        quality.name.equals(name, ignoreCase = true) &&
            (name.equals("Source", ignoreCase = true) ||
                ((codecs == null || quality.codecs.equals(codecs, ignoreCase = true)) &&
                    (bitrate == null || quality.bitrate == bitrate)))
    } ?: fallback(name)

    /** Automatic recovery must not replace a missing manual rendition with another quality. */
    fun resolveExact(qualities: List<VideoQuality>?): VideoQuality? = qualities?.firstOrNull { quality ->
        quality.name.equals(name, ignoreCase = true) &&
            (codecs == null || quality.codecs.equals(codecs, ignoreCase = true)) &&
            (bitrate == null || quality.bitrate == bitrate)
    }
}

/**
 * Keeps a quality selection while a player source is being replaced.
 *
 * A reverse transition can begin after the first transition has already
 * cleared the live quality object. In that case a null capture must not erase
 * the selection that the reverse transition needs to restore.
 */
internal class SourceSwitchQualityState {
    private var pendingQuality: SourceSwitchQualityIdentity? = null

    fun capture(quality: VideoQuality?) {
        quality?.name?.let { name ->
            pendingQuality = SourceSwitchQualityIdentity(name, quality.codecs, quality.bitrate)
        }
    }

    fun consume(): SourceSwitchQualityIdentity? = pendingQuality.also { pendingQuality = null }

    fun clear() {
        pendingQuality = null
    }
}

/** Keeps the user's pre-ad quality intent while an alternate stream is active. */
internal class AdAvoidanceQualityState {
    private var active = false
    private var preferredQuality: SourceSwitchQualityIdentity? = null
    private var expectedPrimaryReturnUri: String? = null

    val isActive: Boolean
        get() = active

    val isAwaitingPrimaryReturn: Boolean
        get() = active && expectedPrimaryReturnUri != null

    val identityForPrimaryReturn: SourceSwitchQualityIdentity?
        get() = preferredQuality

    fun begin(primaryQuality: VideoQuality?) {
        if (active) return
        active = true
        preferredQuality = primaryQuality?.toIdentity()
    }

    /** The quality dialog can explicitly select the already-active alternate rung. */
    fun rememberExplicitSelection(quality: VideoQuality?) {
        if (!active) return
        quality?.let { preferredQuality = it.toIdentity() }
    }

    fun expectPrimaryReturn(uri: String) {
        if (active) expectedPrimaryReturnUri = uri
    }

    fun matchesPrimaryReturn(uri: String?): Boolean =
        isAwaitingPrimaryReturn && expectedPrimaryReturnUri == uri

    fun clear() {
        active = false
        preferredQuality = null
        expectedPrimaryReturnUri = null
    }

    private fun VideoQuality.toIdentity(): SourceSwitchQualityIdentity? =
        name?.let { SourceSwitchQualityIdentity(it, codecs, bitrate) }
}
