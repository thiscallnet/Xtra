package com.github.andreyasadchy.xtra.util.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

data class UpdateInstallSessionSnapshot(
    val committed: Boolean,
    val sealed: Boolean,
    val terminal: Boolean,
    val commitStateKnown: Boolean = true,
)

interface UpdateInstallSessionStore {
    fun inspect(sessionId: Int): UpdateInstallSessionSnapshot?

    /** Re-submit commit with a fresh updater callback when old platforms cannot expose commit state. */
    fun recommit(sessionId: Int, releaseId: String): Boolean = false

    fun abandon(sessionId: Int)
}

class AndroidUpdateInstallSessionStore(private val context: Context) : UpdateInstallSessionStore {
    override fun inspect(sessionId: Int): UpdateInstallSessionSnapshot? = runCatching {
        val info = context.packageManager.packageInstaller.getSessionInfo(sessionId) ?: return@runCatching null
        UpdateInstallSessionSnapshot(
            committed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isCommitted,
            sealed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && info.isSealed,
            terminal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isStaged &&
                (info.isStagedSessionFailed || info.isStagedSessionApplied),
            commitStateKnown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
        )
    }.getOrNull()

    override fun recommit(sessionId: Int, releaseId: String): Boolean {
        val session = runCatching {
            context.packageManager.packageInstaller.openSession(sessionId)
        }.getOrNull() ?: return false
        return try {
            val resultIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                action = UpdateInstallReceiver.ACTION_INSTALL_RESULT
                putExtra(UpdateInstallReceiver.EXTRA_RELEASE_ID, releaseId)
                putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, sessionId)
            }
            val resultSender = PendingIntent.getBroadcast(
                context,
                sessionId,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0,
            ).intentSender
            session.commit(resultSender)
            true
        } catch (error: Throwable) {
            android.util.Log.w(TAG, "Could not recover update install session $sessionId", error)
            false
        } finally {
            session.close()
        }
    }

    override fun abandon(sessionId: Int) {
        context.packageManager.packageInstaller.abandonSession(sessionId)
    }

    private companion object {
        const val TAG = "UpdateInstallSessionStore"
    }
}
