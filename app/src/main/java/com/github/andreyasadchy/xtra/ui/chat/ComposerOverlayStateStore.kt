package com.github.andreyasadchy.xtra.ui.chat



internal data class ComposerOverlaySnapshot<Overlay, RestoreState>(
    val overlay: Overlay,
    val input: String,
    val restoreState: RestoreState,
    val submissionPending: Boolean,
)

internal fun <Overlay, RestoreState> captureComposerOverlaySnapshot(
    overlay: Overlay?,
    existing: ComposerOverlaySnapshot<Overlay, RestoreState>?,
    pendingRestoreState: RestoreState?,
    pendingInput: String?,
    currentInput: String?,
    submissionPending: Boolean,
): ComposerOverlaySnapshot<Overlay, RestoreState>? {
    val retainedOverlay = overlay ?: existing?.overlay ?: return null
    val restoreState = pendingRestoreState ?: existing?.restoreState ?: return null
    val input = if (submissionPending) {
        pendingInput ?: existing?.input.orEmpty()
    } else {
        currentInput ?: existing?.input.orEmpty()
    }
    return ComposerOverlaySnapshot(
        overlay = retainedOverlay,
        input = input,
        restoreState = restoreState,
        submissionPending = submissionPending,
    )
}

internal class ComposerOverlayStateStore<Overlay, RestoreState> {
    var active: ComposerOverlaySnapshot<Overlay, RestoreState>? = null
        private set

    fun open(overlay: Overlay, restoreState: RestoreState) {
        active = ComposerOverlaySnapshot(overlay, "", restoreState, submissionPending = false)
    }

    fun submit(input: String): ComposerOverlaySnapshot<Overlay, RestoreState>? {
        active = active?.copy(input = input, submissionPending = true)
        return active
    }

    fun markFailed(input: String): ComposerOverlaySnapshot<Overlay, RestoreState>? {
        active = active?.copy(input = input, submissionPending = false)
        return active
    }

    fun set(snapshot: ComposerOverlaySnapshot<Overlay, RestoreState>) {
        active = snapshot
    }

    fun clear(): RestoreState? {
        val restoreState = active?.restoreState
        active = null
        return restoreState
    }
}
