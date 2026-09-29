package com.github.andreyasadchy.xtra.ui.common

import android.util.Log
import android.view.View
import android.view.SurfaceView
import androidx.media3.common.Player
import com.github.andreyasadchy.xtra.BuildConfig
import java.security.MessageDigest

internal fun diagnosticToken(value: String?): String = value?.let {
    val digest = MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))
    digest.take(8).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
} ?: "none"

internal fun Any?.identityId(): String =
    if (this == null) "null" else System.identityHashCode(this).toString(16)

internal fun logVideoSurfaceBinding(
    action: String,
    player: Player?,
    target: View?,
    targetPlayer: Player? = null,
) {
    if (!BuildConfig.DEBUG) return

    Log.d(
        "VideoSurface",
        "$action player=${player.identityId()} target=${target.identityId()} " +
            "targetType=${target?.javaClass?.simpleName} " +
            "target.player=${targetPlayer.identityId()} " +
            "attached=${target?.isAttachedToWindow} visible=${target?.visibility} " +
            "surfaceValid=${(target as? SurfaceView)?.holder?.surface?.isValid} " +
            "playerState=${player?.playbackState} playWhenReady=${player?.playWhenReady} " +
            "itemToken=${diagnosticToken(player?.currentMediaItem?.mediaId)} " +
            "positionMs=${player?.currentPosition} bufferedPositionMs=${player?.bufferedPosition} " +
            "size=${target?.width}x${target?.height} xy=${target?.x},${target?.y}",
    )
}
