package com.github.andreyasadchy.xtra.util.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.andreyasadchy.xtra.XtraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val repository = (context.applicationContext as? XtraApp)?.xtraModule?.updateRepository
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (id >= 0L) repository?.handleDownloadComplete(id)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
