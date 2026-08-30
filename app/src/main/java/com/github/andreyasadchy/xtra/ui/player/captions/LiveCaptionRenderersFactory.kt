package com.github.andreyasadchy.xtra.ui.player.captions

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor

@UnstableApi
class LiveCaptionRenderersFactory(
    context: Context,
    private val audioBufferSink: TeeAudioProcessor.AudioBufferSink,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            // Keep integer PCM so the caption conversion path is predictable.
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(
                arrayOf(
                    TeeAudioProcessor(audioBufferSink),
                ),
            )
            .build()
    }
}
