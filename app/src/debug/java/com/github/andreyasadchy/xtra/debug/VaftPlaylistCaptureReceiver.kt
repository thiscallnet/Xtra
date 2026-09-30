package com.github.andreyasadchy.xtra.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.andreyasadchy.xtra.player.hls.VaftPlaylistCapture
import java.io.File

class VaftPlaylistCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val enabled = intent.getBooleanExtra("enabled", true)
        context.getSharedPreferences("vaft_playlist_capture", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled).apply()
        VaftPlaylistCapture.setDirectory(if (enabled) File(context.cacheDir, "vaft-playlists") else null)
        Log.i("VaftPlaylistCapture", "enabled=$enabled; private rolling capture, maximum 512 records / 32 MiB")
    }
}
