package com.github.andreyasadchy.xtra.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.player.MediaPlayerService
import com.github.andreyasadchy.xtra.ui.player.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val receiverContext = context ?: return
        val receiverIntent = intent ?: return
        if (receiverIntent.action != Intent.ACTION_MEDIA_BUTTON) return

        val keyEvent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            receiverIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            receiverIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
        if (keyEvent == null || keyEvent.action != KeyEvent.ACTION_DOWN || keyEvent.repeatCount != 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyEvent.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY
            && keyEvent.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            && keyEvent.keyCode != KeyEvent.KEYCODE_HEADSETHOOK
        ) {
            return
        }

        val app = receiverContext.applicationContext as XtraApp
        val pendingResult = goAsync()
        launchMediaButtonPlayback(
            scope = app.applicationScope,
            playbackStateLookup = { app.xtraModule.playbackPersistence.getPlaybackStatesAndWait() },
            onPlaybackAvailable = {
                val serviceIntent = when (receiverContext.prefs().getString(C.PLAYER, C.EXOPLAYER)) {
                    C.MEDIA_PLAYER -> Intent(receiverContext, MediaPlayerService::class.java)
                    else -> Intent(receiverContext, PlaybackService::class.java)
                }.apply {
                    fillIn(receiverIntent, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    receiverContext.startForegroundService(serviceIntent)
                } else {
                    receiverContext.startService(serviceIntent)
                }
            },
            onFinished = pendingResult::finish,
        )
    }
}

internal fun launchMediaButtonPlayback(
    scope: CoroutineScope,
    playbackStateLookup: suspend () -> List<PlaybackState>,
    onPlaybackAvailable: () -> Unit,
    onFinished: () -> Unit,
): Job = scope.launch(Dispatchers.IO) {
    try {
        if (playbackStateLookup().isNotEmpty()) {
            onPlaybackAvailable()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Unable to handle media button", e)
    } finally {
        onFinished()
    }
}

private const val TAG = "MediaButtonReceiver"
