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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: AlwaysPlayingPlayer
        instrumentation.runOnMainSync {
            player = AlwaysPlayingPlayer(app)
        }
        val service = PlaybackService().apply {
            xtraModule = module
            setViewingMetadata(
                ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                "stream-integration",
                Bundle().apply {
                    putString(PlaybackService.CHANNEL_ID, "channel-integration")
                    putString(PlaybackService.CHANNEL_LOGIN, "channel-integration")
                    putString(PlaybackService.CHANNEL_NAME, "Integration channel")
                    putString(PlaybackService.GAME_ID, "game-1")
                    putString(PlaybackService.GAME_NAME, "League of Legends")
                    putString(PlaybackService.TITLE, "First title")
                },
            )
        }

        try {
            instrumentation.runOnMainSync {
                service.handleViewingMetadataCommand(
                    Bundle().apply {
                        putString(PlaybackService.STREAM_ID, "stream-integration")
                        putString(PlaybackService.GAME_ID, "game-1")
                        putString(PlaybackService.GAME_NAME, "League of Legends")
                        putString(PlaybackService.TITLE, "First title")
                    },
                    player,
                )
            }
            recorder.awaitIdle()
            delay(30)
            instrumentation.runOnMainSync {
                service.handleViewingMetadataCommand(
                    Bundle().apply {
                        putString(PlaybackService.STREAM_ID, "stream-integration")
                        putString(PlaybackService.GAME_ID, "game-2")
                        putString(PlaybackService.GAME_NAME, "Just Chatting")
                        putString(PlaybackService.TITLE, "Second title")
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
            assertEquals(2, intervals.size)
            assertEquals(setOf("game-1", "game-2"), intervals.map { it.categoryId }.toSet())
            assertEquals(1L, module.database.viewingStats().getOverview(0L, System.currentTimeMillis() + 1_000L).sessionCount)
            assertTrue(intervals.all { it.watchedMs > 0L })
        } finally {
            recorder.release("playback-service:primary")
            recorder.reset()
            instrumentation.runOnMainSync {
                player.release()
            }
        }
    }

    @Test
    fun partialLiveMetadataRefreshKeepsExistingAttribution() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as XtraApp
        val module = app.xtraModule
        val recorder = module.viewingStatsRecorder
        recorder.reset()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var player: AlwaysPlayingPlayer
        instrumentation.runOnMainSync {
            player = AlwaysPlayingPlayer(app)
        }
        val service = PlaybackService().apply {
            xtraModule = module
            setViewingMetadata(
                ViewingPlaybackMetadata.CONTENT_TYPE_LIVE,
                "stream-partial",
                Bundle().apply {
                    putString(PlaybackService.CHANNEL_ID, "channel-partial")
                    putString(PlaybackService.CHANNEL_LOGIN, "channel-partial")
                    putString(PlaybackService.CHANNEL_NAME, "Partial channel")
                    putString(PlaybackService.GAME_ID, "game-1")
                    putString(PlaybackService.GAME_NAME, "League of Legends")
                    putString(PlaybackService.TITLE, "First title")
                },
            )
        }

        try {
            instrumentation.runOnMainSync {
                service.handleViewingMetadataCommand(
                    Bundle().apply {
                        putString(PlaybackService.STREAM_ID, "stream-partial")
                        putString(PlaybackService.GAME_ID, "game-1")
                        putString(PlaybackService.GAME_NAME, "League of Legends")
                        putString(PlaybackService.TITLE, "First title")
                    },
                    player,
                )
            }
            recorder.awaitIdle()
            delay(30)
            // A PubSub refresh may contain only one category field. Both
            // fields must remain from the existing category identity.
            instrumentation.runOnMainSync {
                service.handleViewingMetadataCommand(
                    Bundle().apply {
                        putString(PlaybackService.STREAM_ID, "stream-partial")
                        putString(PlaybackService.GAME_NAME, "Just Chatting")
                        putString(PlaybackService.TITLE, "Updated title")
                    },
                    player,
                )
            }
            recorder.awaitIdle()
            delay(30)
            instrumentation.runOnMainSync {
                service.handleViewingMetadataCommand(
                    Bundle().apply {
                        putString(PlaybackService.STREAM_ID, "stream-partial")
                        putString(PlaybackService.GAME_ID, "game-2")
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
