package com.github.andreyasadchy.xtra.util.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.github.andreyasadchy.xtra.XtraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val repository = (context.applicationContext as? XtraApp)?.xtraModule?.updateRepository
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pending = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
        } else {
            null
        }
        val releaseId = intent.getStringExtra(EXTRA_RELEASE_ID)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1).takeIf { it >= 0 }
            ?: intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository?.handleInstallResult(status, releaseId, sessionId, pending)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.github.andreyasadchy.xtra.action.UPDATE_INSTALL_RESULT"
        const val EXTRA_RELEASE_ID = "update_release_id"
        const val EXTRA_SESSION_ID = "update_session_id"
    }
}
