package com.github.andreyasadchy.xtra.ui.player.captions

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCaptionAudioSinkInstrumentationTest {
    @Test
    fun captionSinkRejectsEncodedDirectFormats() {
        val sink = DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .build()
        try {
            val encoded = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).build()
            val pcm = Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelCount(2)
                .build()
            assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(encoded))
            assertTrue(sink.getFormatSupport(pcm) != AudioSink.SINK_FORMAT_UNSUPPORTED)
        } finally {
            sink.release()
        }
    }
}
