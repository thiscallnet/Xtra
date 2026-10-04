package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
open class PlaybackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        val firstRenderer = out.size
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        if (Build.HARDWARE != "ranchu" && Build.HARDWARE != "goldfish") return
        // Keep extension renderers and their ordering from the default factory.
        val videoRendererIndex = (firstRenderer until out.size).firstOrNull {
            out[it].javaClass == MediaCodecVideoRenderer::class.java
        } ?: return
        out[videoRendererIndex] = GoldfishVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
        )
    }

    private class GoldfishVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
        override fun canReuseCodec(
            codecInfo: MediaCodecInfo,
            oldFormat: Format,
            newFormat: Format,
            isAdaptiveFormatChange: Boolean,
        ): DecoderReuseEvaluation {
            val evaluation = super.canReuseCodec(codecInfo, oldFormat, newFormat, isAdaptiveFormatChange)
            if (codecInfo.name == "c2.goldfish.h264.decoder" &&
                (oldFormat.width != newFormat.width || oldFormat.height != newFormat.height)
            ) {
                // Goldfish retains the previous output buffer dimensions when reused
                // across resolutions, rendering a smaller frame over stale pixels.
                // Keep adaptive track support, but recreate this decoder at the switch.
                return DecoderReuseEvaluation(
                    codecInfo.name, oldFormat, newFormat,
                    DecoderReuseEvaluation.REUSE_RESULT_NO,
                    evaluation.discardReasons or DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND,
                )
            }
            return evaluation
        }
    }
}
