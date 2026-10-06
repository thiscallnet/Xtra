package com.github.andreyasadchy.xtra.ui.player

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Trace
import com.github.andreyasadchy.xtra.BuildConfig
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/** Reuses unchanged live notifications while Media3 still performs every lifecycle update. */
internal class LiveMediaNotificationProvider(
    private val context: Context,
    private val delegate: DefaultMediaNotificationProvider,
) : MediaNotification.Provider {
    private data class Key(
        val session: MediaSession,
        val factory: MediaNotification.ActionFactory,
        val commands: Player.Commands,
        val buttons: ImmutableList<CommandButton>,
        val metadata: MediaMetadata,
        val activity: android.app.PendingIntent?,
        val loader: androidx.media3.common.util.BitmapLoader,
        val showPauseButton: Boolean,
        val configuration: Configuration,
    )
    private var key: Key? = null
    private var cached: MediaNotification? = null
    private var latestCallback: MediaNotification.Provider.Callback? = null
    private var generation = 0L

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val player = mediaSession.player
        // Replay notifications have a position-based chronometer. Keep the normal provider there.
        if (!player.isCurrentMediaItemDynamic) {
            generation++
            key = null
            cached = null
            latestCallback = null
            return delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory, onNotificationChangedCallback)
        }
        val nextKey = Key(
            mediaSession, actionFactory, player.availableCommands, mediaButtonPreferences,
            notificationMetadata(player.mediaMetadata), mediaSession.sessionActivity, mediaSession.bitmapLoader,
            !Util.shouldShowPlayButton(player, mediaSession.showPlayButtonIfPlaybackIsSuppressed),
            Configuration(context.resources.configuration),
        )
        latestCallback = onNotificationChangedCallback
        if (BuildConfig.PERF_DIAGNOSTICS && key != nextKey) {
            val previous = key
            val reason = when {
                previous == null -> "Xtra.Player.notificationMiss.initial"
                previous.session != nextKey.session -> "Xtra.Player.notificationMiss.session"
                previous.factory != nextKey.factory -> "Xtra.Player.notificationMiss.factory"
                previous.commands != nextKey.commands -> "Xtra.Player.notificationMiss.commands"
                previous.buttons != nextKey.buttons -> "Xtra.Player.notificationMiss.buttons"
                previous.metadata != nextKey.metadata -> "Xtra.Player.notificationMiss.metadata"
                previous.activity != nextKey.activity -> "Xtra.Player.notificationMiss.activity"
                previous.loader != nextKey.loader -> "Xtra.Player.notificationMiss.loader"
                previous.showPauseButton != nextKey.showPauseButton -> "Xtra.Player.notificationMiss.playPause"
                else -> "Xtra.Player.notificationMiss.configuration"
            }
            Trace.beginSection(reason)
            Trace.endSection()
        }
        if (key == nextKey) cached?.let {
            if (BuildConfig.PERF_DIAGNOSTICS) {
                Trace.beginSection("Xtra.Player.notificationReuse")
                Trace.endSection()
            }
            return it
        }
        key = nextKey
        val requestGeneration = ++generation
        if (BuildConfig.PERF_DIAGNOSTICS) Trace.beginSection("Xtra.Player.notificationBuild")
        return try {
            delegate.createNotification(mediaSession, mediaButtonPreferences, actionFactory) { notification ->
                if (requestGeneration == generation) {
                    cached = notification
                    // Artwork completion must use the manager's latest update sequence, including hits.
                    latestCallback?.onNotificationChanged(notification)
                }
            }.also { cached = it }
        } finally {
            if (BuildConfig.PERF_DIAGNOSTICS) Trace.endSection()
        }
    }

    // DefaultMediaNotificationProvider displays title/artist and loads artwork from these
    // fields. Live window duration and other session metadata change on playlist refreshes
    // without changing the notification. The session still receives the original metadata.
    private fun notificationMetadata(metadata: MediaMetadata): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(metadata.title)
            .setArtist(metadata.artist)
            .setArtworkUri(metadata.artworkUri)
            .setArtworkData(metadata.artworkData, metadata.artworkDataType)
            .build()

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle) =
        delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo() = delegate.notificationChannelInfo
}
