package com.github.andreyasadchy.xtra.ui.common

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import com.github.andreyasadchy.xtra.BuildConfig

private const val VIDEO_TRACK_TAG = "XtraVideoTrack"

internal fun logVideoTracks(
    reason: String,
    player: Player?,
) {
    if (!BuildConfig.DEBUG || player == null) return

    var foundVideo = false
    var foundSelected = false

    player.currentTracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != C.TRACK_TYPE_VIDEO) {
            return@forEachIndexed
        }

        foundVideo = true

        for (trackIndex in 0 until group.length) {
            val format = group.mediaTrackGroup.getFormat(trackIndex)
            val selected = group.isTrackSelected(trackIndex)

            if (selected) {
                foundSelected = true
            }

            Log.d(
                VIDEO_TRACK_TAG,
                buildString {
                    append("reason=")
                    append(reason)
                    append(" group=")
                    append(groupIndex)
                    append(" track=")
                    append(trackIndex)
                    append(" selected=")
                    append(selected)
                    append(" size=")
                    append(format.width)
                    append('x')
                    append(format.height)
                    append(" fps=")
                    append(format.frameRate)
                    append(" bitrate=")
                    append(format.bitrate)
                    append(" codecs=")
                    append(format.codecs)
                    append(" mime=")
                    append(format.sampleMimeType)
                    append(" label=")
                    append(format.label)
                    append(" id=")
                    append(format.id)
                },
            )
        }
    }

    if (!foundVideo || !foundSelected) {
        Log.d(
            VIDEO_TRACK_TAG,
            "reason=$reason videoGroup=$foundVideo selectedVideo=$foundSelected",
        )
    }
}
