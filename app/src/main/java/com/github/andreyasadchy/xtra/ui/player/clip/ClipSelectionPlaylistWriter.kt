package com.github.andreyasadchy.xtra.ui.player.clip

import java.io.File

/** Creates a segment-aligned VOD playlist from the prepared structured segment metadata. */
internal object ClipSelectionPlaylistWriter {
    fun write(
        prepared: ClipPreparationRepository.PreparedLiveClip,
        output: File,
        startIndex: Int,
        endIndexExclusive: Int,
    ): File {
        require(startIndex in prepared.segments.indices)
        require(endIndexExclusive in (startIndex + 1)..prepared.segments.size)
        val selectedSegments = prepared.segments.subList(startIndex, endIndexExclusive)
        return LocalHlsPlaylistWriter.write(
            directory = prepared.directory,
            segments = selectedSegments,
            outputName = output.name,
        )
    }

    fun write(
        directory: File,
        output: File,
        startIndex: Int,
        endIndexExclusive: Int,
    ): File = write(
        prepared = ClipPreparationRepository.PreparedLiveClip.read(directory),
        output = output,
        startIndex = startIndex,
        endIndexExclusive = endIndexExclusive,
    )
}
