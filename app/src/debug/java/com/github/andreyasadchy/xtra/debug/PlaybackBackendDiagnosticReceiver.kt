package com.github.andreyasadchy.xtra.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.andreyasadchy.xtra.util.PlaybackRuntimeDiagnostic

/** On-demand debug-only diagnostic for the player currently attached to MainActivity. */
class PlaybackBackendDiagnosticReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return

        val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        Log.i(
            TAG,
            "playback_runtime_diagnostic token=$token state=${PlaybackRuntimeDiagnostic.currentBackendName()}",
        )
    }

    companion object {
        const val ACTION = "com.github.andreyasadchy.xtra.debug.DUMP_PLAYBACK_BACKEND"
        const val EXTRA_TOKEN = "token"

        private const val TAG = "PlaybackBackendDiagnostic"
    }
}
