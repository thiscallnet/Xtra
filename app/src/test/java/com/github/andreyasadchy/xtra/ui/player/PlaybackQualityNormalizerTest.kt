package com.github.andreyasadchy.xtra.ui.player

import com.github.andreyasadchy.xtra.model.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQualityNormalizerTest {

    @Test
    fun rawAudioOnlyVariantKeepsItsPlaybackMetadata() {
        val normalized = normalizePlaybackQualities(
            listOf(
                VideoQuality(
                    BasePlaybackService.AUDIO_ONLY_QUALITY,
                    codecs = "mp4a.40.2",
                    bitrate = 128_000,
                    url = "audio-url",
                )
            )
        )

        val audio = normalized.last()

        assertEquals(BasePlaybackService.AUDIO_ONLY_QUALITY, audio.name)
        assertEquals("mp4a.40.2", audio.codecs)
        assertEquals(128_000, audio.bitrate)
        assertEquals("audio-url", audio.url)
    }

    @Test
    fun rawVariantsBecomeTheStableUiQualityContract() {
        val audio = VideoQuality("audio_only_raw", codecs = "audio", bitrate = 128_000, url = "audio")
        val source = VideoQuality("Source", codecs = "h264", bitrate = 8_000_000, url = "source")

        val normalized = normalizePlaybackQualities(
            listOf(
                VideoQuality("720p60", bitrate = 4_000_000, url = "720"),
                audio,
                source,
                VideoQuality("1080p60", bitrate = 6_000_000, url = "1080"),
                VideoQuality(BasePlaybackService.AUTO_QUALITY),
            )
        )

        assertEquals(
            listOf(
                BasePlaybackService.AUTO_QUALITY,
                BasePlaybackService.SOURCE_QUALITY,
                "1080p60",
                "720p60",
                BasePlaybackService.AUDIO_ONLY_QUALITY,
            ),
            normalized.map { it.name },
        )
        assertEquals("source", normalized[1].url)
        assertEquals("audio", normalized.last().codecs)
        assertEquals("audio", normalized.last().url)
    }

    @Test
    fun normalizingAReattachedStateDoesNotDuplicateSyntheticEntries() {
        val first = normalizePlaybackQualities(
            listOf(
                VideoQuality(BasePlaybackService.AUTO_QUALITY),
                VideoQuality(BasePlaybackService.SOURCE_QUALITY, url = "source"),
                VideoQuality("720p60", url = "720"),
                VideoQuality(BasePlaybackService.AUDIO_ONLY_QUALITY, url = "audio"),
            )
        )

        val second = normalizePlaybackQualities(first)

        assertEquals(first.map { it.name }, second.map { it.name })
        assertEquals(1, second.count { it.name == BasePlaybackService.AUTO_QUALITY })
        assertEquals(1, second.count { it.name == BasePlaybackService.SOURCE_QUALITY })
        assertEquals(1, second.count { it.name == BasePlaybackService.AUDIO_ONLY_QUALITY })
    }

    @Test
    fun alternateSourceThenUiReattachmentKeepsAutoSourceAndAudioOnly() {
        val primary = normalizePlaybackQualities(
            listOf(
                VideoQuality("Source", url = "primary-source"),
                VideoQuality("1080p60", url = "primary-1080"),
                VideoQuality("audio", url = "primary-audio"),
            )
        )
        val reattached = normalizePlaybackQualities(
            listOf(
                VideoQuality("Source", url = "alternate-source"),
                VideoQuality("720p60", url = "alternate-720"),
                VideoQuality("audio", url = "alternate-audio"),
            )
        )

        assertEquals(
            listOf(
                BasePlaybackService.AUTO_QUALITY,
                BasePlaybackService.SOURCE_QUALITY,
                "720p60",
                BasePlaybackService.AUDIO_ONLY_QUALITY,
            ),
            reattached.map { it.name },
        )
        assertEquals("primary-1080", primary[2].url)
        assertEquals("alternate-source", reattached[1].url)
        assertEquals("alternate-audio", reattached.last().url)
    }
}
