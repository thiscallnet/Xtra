package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.Format
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothHlsQualitySelectionTest {

    @Test
    fun variantVideoAndAudioCodecsMatchVideoTrackCodec() {
        assertTrue(
            desired(codecs = "avc1.4D401F,mp4a.40.2")
                .matches(format(codecs = "avc1.4D401F")),
        )
    }

    @Test
    fun codecTokenOrderAndCaseDoNotAffectMatch() {
        assertTrue(
            desired(codecs = "mp4a.40.2, AVC1.4d401f")
                .matches(format(codecs = "avc1.4D401F")),
        )
    }

    @Test
    fun formatWithMultipleCodecsMatchesWhenVideoCodecIntersects() {
        assertTrue(
            desired(codecs = "avc1.4D401F,mp4a.40.2")
                .matches(format(codecs = "mp4a.40.2,avc1.4D401F,ec-3")),
        )
    }

    @Test
    fun codecProfilesRemainDistinct() {
        assertFalse(
            desired(codecs = "avc1.4D401F,mp4a.40.2")
                .matches(format(codecs = "avc1.640020")),
        )
    }

    @Test
    fun differentVideoCodecDoesNotMatch() {
        assertFalse(
            desired(codecs = "avc1.4D401F,mp4a.40.2")
                .matches(format(codecs = "hvc1.1.6.L93.B0")),
        )
    }

    @Test
    fun videoBitrateAboveRequestedCeilingDoesNotMatch() {
        assertFalse(
            desired(codecs = "avc1.4D401F,mp4a.40.2", bitrate = 200_000)
                .matches(format(codecs = "avc1.4D401F", bitrate = 230_000)),
        )
    }

    @Test
    fun missingFormatCodecRetainsPermissiveMatching() {
        assertTrue(
            desired(codecs = "avc1.4D401F,mp4a.40.2")
                .matches(format(codecs = null)),
        )
    }

    private fun desired(
        codecs: String,
        bitrate: Int = 500_000,
    ) = DesiredHlsQuality("160p", bitrate, codecs)

    private fun format(
        codecs: String?,
        bitrate: Int = 230_000,
    ) = Format.Builder()
        .setLabel("160p")
        .setWidth(284)
        .setHeight(160)
        .setFrameRate(30f)
        .setAverageBitrate(bitrate)
        .setCodecs(codecs)
        .build()
}
