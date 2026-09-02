package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor

@UnstableApi
class LiveCaptionRenderersFactory(
    context: Context,
    private val audioBufferSink: TeeAudioProcessor.AudioBufferSink,
    private val presentationDelayMs: () -> Int,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        // Caption-capable playback always uses decoded PCM. The no-context
        // builder is required because Media3 ignores setAudioCapabilities when
        // the context builder is used and derives capabilities from the device.
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            // Keep integer PCM so the capture and bounded presentation-delay paths
            // have one predictable frame format.
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(
                arrayOf(
                    TeeAudioProcessor(audioBufferSink),
                    CaptionPresentationDelayAudioProcessor(presentationDelayMs),
                ),
            )
            .build()
    }
}
