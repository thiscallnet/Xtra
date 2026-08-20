package com.github.andreyasadchy.xtra.ui.player.clip

/**
 * Resolves the prepared directory retained by the parent and the directory carried by a
 * restored editor child. The child value is authoritative because the editor's arguments and
 * local player refer to that directory.
 */
internal class ClipEditorRestorationState(
    savedDirectoryPath: String?,
    childDirectoryPath: String?,
) {
    private val savedDirectory = savedDirectoryPath?.takeIf { it.isNotBlank() }
    private val childDirectory = childDirectoryPath?.takeIf { it.isNotBlank() }

    val directoryPath: String?
        get() = childDirectory ?: savedDirectory

    val shouldRestoreEditor: Boolean
        get() = childDirectory != null && directoryPath != null

    val orphanDirectoryPath: String?
        get() = if (childDirectory == null) savedDirectory else null

    /** A stale parent-owned directory that must be released when the child has another path. */
    val staleParentDirectoryPath: String?
        get() = savedDirectory?.takeIf { childDirectory != null && it != childDirectory }

    val hasState: Boolean
        get() = savedDirectory != null || childDirectory != null
}
