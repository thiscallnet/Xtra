package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

internal enum class LiveNotificationProcessOwner {
    FAST,
    PERSISTENT_FALLBACK,
}

internal fun shouldContinueProcessRunner(
    owner: LiveNotificationProcessOwner,
    mode: String,
    notificationsEnabled: Boolean,
    persistentServiceRunning: Boolean,
): Boolean = notificationsEnabled && when (owner) {
    LiveNotificationProcessOwner.FAST -> mode == C.LIVE_NOTIFICATIONS_MODE_FAST
    LiveNotificationProcessOwner.PERSISTENT_FALLBACK ->
        mode == C.LIVE_NOTIFICATIONS_MODE_PERSISTENT && !persistentServiceRunning
}

/** Owns the shared process runner for Fast mode or a failed Persistent-service start. */
object LiveNotificationRealtimeEngine {

    private val lock = Any()
    private var current: OwnedRunner? = null

    internal fun start(
        context: Context,
        owner: LiveNotificationProcessOwner = LiveNotificationProcessOwner.FAST,
    ) {
        synchronized(lock) {
            val applicationContext = context.applicationContext
            if (current?.owner != owner) {
                current?.runner?.stop()
                current = null
            }
            val ownedRunner = current ?: OwnedRunner(
                owner = owner,
                runner = LiveNotificationRunner(
                    context = applicationContext,
                    shouldContinue = {
                        shouldContinueProcessRunner(
                            owner = owner,
                            mode = LiveNotificationScheduler.mode(applicationContext),
                            notificationsEnabled = applicationContext.prefs()
                                .getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
                            persistentServiceRunning = LiveNotificationService.isRunning(),
                        )
                    },
                ),
            ).also { current = it }
            ownedRunner.runner.start()
        }
    }

    fun stop() {
        synchronized(lock) {
            current?.runner?.stop()
            current = null
        }
    }

    internal fun hasHealthyOwner(): Boolean = synchronized(lock) {
        current?.runner?.isHealthy() == true
    }

    internal fun requestImmediateReconciliation(reason: String): Boolean = synchronized(lock) {
        current?.runner?.requestImmediateReconciliation(reason) == true
    }

    private data class OwnedRunner(
        val owner: LiveNotificationProcessOwner,
        val runner: LiveNotificationRunner,
    )
}
