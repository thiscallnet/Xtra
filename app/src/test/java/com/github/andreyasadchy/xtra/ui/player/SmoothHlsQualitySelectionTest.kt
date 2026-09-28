package com.github.andreyasadchy.xtra.ui.player

import androidx.media3.common.Format
import com.github.andreyasadchy.xtra.model.VideoQuality
import org.junit.Assert.assertEquals
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
    fun namedQualityMatchesAcrossSourceBitrateChange() {
        assertTrue(
            DesiredHlsQuality("480p", 1_200_000, "avc1.4D401F,mp4a.40.2")
                .matches(
                    Format.Builder()
                        .setLabel("480p")
                        .setWidth(852)
                        .setHeight(480)
                        .setFrameRate(30f)
                        .setAverageBitrate(1_427_999)
                        .setCodecs("avc1.4D401F")
                        .build(),
                ),
        )
    }

    @Test
    fun dimensionsMatchAcrossSourceBitrateChangeWhenLabelDiffers() {
        assertTrue(
            DesiredHlsQuality("480p", 1_200_000, "avc1.4D401F,mp4a.40.2")
                .matches(
                    Format.Builder()
                        .setLabel("480p30")
                        .setWidth(852)
                        .setHeight(480)
                        .setFrameRate(30f)
                        .setAverageBitrate(1_427_999)
                        .setCodecs("avc1.4D401F")
                        .build(),
                ),
        )
    }

    @Test
    fun missingFormatCodecRetainsPermissiveMatching() {
        assertTrue(
            desired(codecs = "avc1.4D401F,mp4a.40.2")
                .matches(format(codecs = null)),
        )
    }

    @Test
    fun resumptionRestoresManualQualityMetadata() {
        listOf(
            VideoQuality("720p60", "avc1.4D401F,mp4a.40.2", 3_400_000),
            VideoQuality("source", "avc1.4D401F,mp4a.40.2", 9_000_000),
        ).forEach { quality ->
            assertEquals(
                DesiredHlsQuality(quality.name!!, quality.bitrate, quality.codecs),
                resumptionHlsQuality(quality),
            )
        }
    }

    @Test
    fun resumptionResetsAdaptiveAndVideoDisabledModesToAuto() {
        listOf(
            null,
            VideoQuality(name = null, bitrate = 3_400_000, codecs = "avc1.4D401F"),
            VideoQuality("auto", bitrate = 3_400_000, codecs = "avc1.4D401F"),
            VideoQuality("audio_only", bitrate = 160_000, codecs = "mp4a.40.2"),
            VideoQuality("chat_only", bitrate = 160_000, codecs = "mp4a.40.2"),
        ).forEach { quality ->
            assertEquals(DesiredHlsQuality("auto"), resumptionHlsQuality(quality))
        }
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
