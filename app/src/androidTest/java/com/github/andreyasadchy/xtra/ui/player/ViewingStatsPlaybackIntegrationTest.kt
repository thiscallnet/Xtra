package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.stats.ViewingPlaybackMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewingStatsPlaybackIntegrationTest {

    @Test
    fun playbackServiceMetadataRefreshSplitsLiveAttributionWithoutStartingSession() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as XtraApp
        val module = app.xtraModule
        val recorder = module.viewingStatsRecorder
        recorder.reset()

        val service = TestPlaybackService().apply {
            xtraModule = module
            type = BasePlaybackService.STREAM
            streamId = "stream-integration"
            channelId = "channel-integration"
            channelLogin = "channel-integration"
            channelName = "Integration channel"
        }

        try {
            service.updateViewingMetadata("game-1", "League of Legends", "First title")
            recorder.awaitIdle()
            delay(30)
            service.updateViewingMetadata("game-2", "Just Chatting", "Second title")
            recorder.awaitIdle()
            delay(30)
            service.stopViewingStats()
            recorder.awaitIdle()

            val intervals = module.database.viewingStats().getRecentIntervals(
                fromInclusive = 0L,
                toExclusive = System.currentTimeMillis() + 1_000L,
                limit = 10,
            )
            assertEquals(2, intervals.size)
            assertEquals(setOf("game-1", "game-2"), intervals.map { it.categoryId }.toSet())
            assertEquals(1L, module.database.viewingStats().getOverview(0L, System.currentTimeMillis() + 1_000L).sessionCount)
            assertTrue(intervals.all { it.watchedMs > 0L })
        } finally {
            service.stopViewingStats()
            recorder.reset()
        }
    }

    @Test
    fun partialLiveMetadataRefreshKeepsExistingAttribution() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as XtraApp
        val module = app.xtraModule
        val recorder = module.viewingStatsRecorder
        recorder.reset()

        val service = TestPlaybackService().apply {
            xtraModule = module
            type = BasePlaybackService.STREAM
            streamId = "stream-partial"
            channelId = "channel-partial"
            channelLogin = "channel-partial"
            channelName = "Partial channel"
        }

        try {
            service.updateViewingMetadata("game-1", "League of Legends", "First title")
            recorder.awaitIdle()
            delay(30)
            // A PubSub refresh may contain only one category field. Both
            // fields must remain from the existing category identity.
            service.updateViewingMetadata(null, "Just Chatting", "Updated title")
            recorder.awaitIdle()
            delay(30)
            service.updateViewingMetadata("game-2", null, null)
            recorder.awaitIdle()
            delay(30)
            service.stopViewingStats()
            recorder.awaitIdle()

            val intervals = module.database.viewingStats().getRecentIntervals(
                fromInclusive = 0L,
                toExclusive = System.currentTimeMillis() + 1_000L,
                limit = 10,
            )
            assertEquals(1, intervals.size)
            assertEquals("game-1", intervals.single().categoryId)
            assertEquals("League of Legends", intervals.single().categoryName)
            assertEquals("Updated title", intervals.single().streamTitle)
            assertEquals(
                1L,
                module.database.viewingStats().getOverview(0L, System.currentTimeMillis() + 1_000L).sessionCount,
            )
        } finally {
            service.stopViewingStats()
            recorder.reset()
        }
    }

    @Test
    fun media3MetadataCommandPatchKeepsExistingAttribution() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as XtraApp
        val module = app.xtraModule
        val recorder = module.viewingStatsRecorder
        recorder.reset()

        val service = PlaybackService().apply {
            xtraModule = module
            setViewingMetadata(
                ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                "stream-media3-partial",
                Bundle().apply {
                    putString(PlaybackService.STREAM_ID, "stream-media3-partial")
                    putString(PlaybackService.CHANNEL_ID, "channel-media3-partial")
                    putString(PlaybackService.CHANNEL_LOGIN, "channel-media3-partial")
                    putString(PlaybackService.CHANNEL_NAME, "Media3 channel")
                    putString(PlaybackService.GAME_ID, "game-1")
                    putString(PlaybackService.GAME_NAME, "League of Legends")
                    putString(PlaybackService.TITLE, "First title")
                },
            )
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: AlwaysPlayingPlayer
        instrumentation.runOnMainSync {
            player = AlwaysPlayingPlayer(app)
        }

        try {
            recorder.update(
                sourceId = "playback-service:primary",
                metadata = ViewingPlaybackMetadata(
                    channelId = "channel-media3-partial",
                    channelLogin = "channel-media3-partial",
                    channelName = "Media3 channel",
                    channelImage = null,
                    categoryId = "game-1",
                    categoryName = "League of Legends",
                    contentType = ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                    contentId = "stream-media3-partial",
                    title = "First title",
                ),
                isPlaying = true,
                isBuffering = false,
            )
            recorder.awaitIdle()

            // This is the exact command path used by Media3Fragment. The
            // omitted category fields must not erase the current category.
            instrumentation.runOnMainSync {
                service.handleViewingMetadataCommand(
                    Bundle().apply {
                        putString(PlaybackService.STREAM_ID, "stream-media3-partial")
                        putString(PlaybackService.TITLE, "Updated title")
                    },
                    player,
                )
                // An incomplete category pair must be ignored as well.
                service.handleViewingMetadataCommand(
                    Bundle().apply {
                        putString(PlaybackService.STREAM_ID, "stream-media3-partial")
                        putString(PlaybackService.GAME_NAME, "Just Chatting")
                    },
                    player,
                )
            }
            recorder.awaitIdle()
            delay(30)
            recorder.release("playback-service:primary")
            recorder.awaitIdle()

            val intervals = module.database.viewingStats().getRecentIntervals(
                fromInclusive = 0L,
                toExclusive = System.currentTimeMillis() + 1_000L,
                limit = 10,
            )
            assertEquals(1, intervals.size)
            assertEquals("game-1", intervals.single().categoryId)
            assertEquals("League of Legends", intervals.single().categoryName)
            assertEquals("Updated title", intervals.single().streamTitle)
            assertEquals(
                1L,
                module.database.viewingStats().getOverview(0L, System.currentTimeMillis() + 1_000L).sessionCount,
            )
        } finally {
            recorder.release("playback-service:primary")
            recorder.reset()
            instrumentation.runOnMainSync {
                player.release()
            }
        }
    }

    private class TestPlaybackService : BasePlaybackService() {
        override fun isViewingPlaybackPlaying(): Boolean = true

        fun stopViewingStats() = releaseViewingStats()
    }

    private class AlwaysPlayingPlayer(context: Context) : ForwardingSimpleBasePlayer(
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("https://example.invalid/test.m3u8"))
        },
    ) {
        override fun getState(): State {
            return super.getState().buildUpon()
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
    }
}
